package io.forgetdm.discovery;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.config.ForgeProps;
import io.forgetdm.core.mask.StructuredMaskingCodec;
import io.forgetdm.core.util.PiiPatterns;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.datasource.SqlDialect;
import io.forgetdm.policy.MaskingPolicyEntity;
import io.forgetdm.policy.MaskingPolicyRepository;
import io.forgetdm.policy.MaskingRuleEntity;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.policy.PolicyNameRules;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.Reader;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Dual-signal PII discovery:
 *   confidence = 0.6 * columnNameSignal + 0.4 * sampledValueSignal
 * Results land in a human review queue (SUGGESTED -> APPROVED/REJECTED),
 * and approved findings compile straight into an executable masking policy
 * (IBM Governance-Catalog "drag a rule onto a column" made automatic).
 */
@Service
public class DiscoveryService {
    private static final ConcurrentMap<String, ReentrantLock> SCAN_SCOPE_LOCKS = new ConcurrentHashMap<>();
    private static final int MAX_SAMPLE_CELL_CHARS = 262_144;
    private static final Set<String> HOMOGENEOUS_LEAF_MASKS = Set.of(
            "PHONE", "EMAIL", "SSN", "CREDIT_CARD", "IBAN", "SWIFT_BIC", "BANK_ACCOUNT",
            "ROUTING", "IP_ADDRESS", "MAC_ADDRESS");

    /**
     * A scope rejection is intentionally distinct from a connection or scan failure.  Callers can
     * record a concise, credential-free audit event and must never turn an empty scope into a
     * successful zero-table scan.
     */
    static final class ScanScopeException extends ApiException {
        private final String reason;

        private ScanScopeException(String reason, String message) {
            super(org.springframework.http.HttpStatus.BAD_REQUEST, message);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }

    public interface ScanProgress {
        default void schemaResolved(String schemaName) {}
        default void tablesDiscovered(List<String> tableNames) {}
        default void tableStarted(String tableName, int tableIndex, int totalTables) {}
        default void tableColumns(String tableName, int totalColumns) {}
        default void columnScanned(String tableName, String columnName, int scannedColumns, int totalColumns) {}
        default void findingDiscovered(String tableName, String columnName, String piiType) {}
        default void tableCompleted(String tableName, int findingsForTable) {}
    }

    private final ClassificationRepository classifications;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final MaskingPolicyRepository policies;
    private final MaskingRuleRepository rules;
    private final AuditService audit;
    private final ForgeProps props;
    private final PiiPatternService customPatterns;

    public DiscoveryService(ClassificationRepository classifications, DataSourceService dataSources,
                            ConnectionFactory connections, MaskingPolicyRepository policies,
                            MaskingRuleRepository rules, AuditService audit, ForgeProps props,
                            PiiPatternService customPatterns) {
        this.classifications = classifications; this.dataSources = dataSources;
        this.connections = connections; this.policies = policies;
        this.rules = rules; this.audit = audit; this.props = props;
        this.customPatterns = customPatterns;
    }

    @Transactional
    public List<ClassificationEntity> scan(Long dataSourceId) {
        return scan(dataSourceId, null, null);
    }

    @Transactional
    public List<ClassificationEntity> scan(Long dataSourceId, String schemaName) {
        return scan(dataSourceId, schemaName, null);
    }

    /**
     * @param selectedTypes when non-empty, only these PII types are scanned for (e.g. just SSN, CREDIT_CARD,
     *                      FULL_NAME, ADDRESS). When empty/null, every known type is considered.
     */
    @Transactional
    public List<ClassificationEntity> scan(Long dataSourceId, String schemaName, Set<String> selectedTypes) {
        return scan(dataSourceId, schemaName, selectedTypes, null, null);
    }

    @Transactional
    public List<ClassificationEntity> scan(Long dataSourceId, String schemaName, Set<String> selectedTypes,
                                           ScanProgress progress) {
        return scan(dataSourceId, schemaName, selectedTypes, null, progress);
    }

    @Transactional
    public List<ClassificationEntity> scan(Long dataSourceId, String schemaName, Set<String> selectedTypes,
                                           Set<String> selectedTables, ScanProgress progress) {
        DataSourceEntity ds = dataSources.getSourceCapable(dataSourceId);
        List<ClassificationEntity> found = new ArrayList<>();
        ScanProgress scanProgress = progress == null ? new ScanProgress() {} : progress;
        String resolvedSchema = null;

        // Effective patterns = built-in, overlaid with the current user's custom patterns (user > group > global),
        // then narrowed to the user's selected PII types if any were chosen on the Scan Source page.
        PiiPatternService.Effective custom = customPatterns.resolveEffective();
        Map<String, Pattern> nameHints = new LinkedHashMap<>(PiiPatterns.NAME_HINTS);
        nameHints.putAll(custom.name());
        Map<String, Pattern> valueHints = new LinkedHashMap<>(PiiPatterns.VALUE_HINTS);
        valueHints.putAll(custom.value());
        Map<String, String> suggested = new LinkedHashMap<>(PiiPatterns.SUGGESTED);
        suggested.putAll(custom.suggested());
        Set<String> selected = normalizeTypes(selectedTypes);
        Set<String> selectedTableKeys = normalizeNames(selectedTables);
        if (!selected.isEmpty()) {
            nameHints.keySet().retainAll(selected);
            valueHints.keySet().retainAll(selected);
        }

        try (Connection c = connections.openPooled(ds)) {
            String schema = DataSourceService.normalizeSchema(c, schemaName);
            resolvedSchema = schema;
            validateRequestedSchemaVisible(c, schemaName, schema);
            ScanScopeLease scanScope = acquireScanScope(dataSourceId, schema);
            try {
            scanProgress.schemaResolved(schema);
            // Preserve human decisions across re-scans: keep APPROVED / REJECTED / manual classifications,
            // refresh only the machine SUGGESTED ones. (A re-scan must not wipe an analyst's review work.)
            List<ClassificationEntity> existing = upgradeStructuredRows(
                    classifications.findByDataSourceIdAndSchemaName(dataSourceId, schema));
            Set<String> locked = new HashSet<>();
            Map<String, ClassificationEntity> refreshable = new LinkedHashMap<>();
            for (ClassificationEntity e : existing) {
                boolean tableInScope = selectedTableKeys.isEmpty()
                        || selectedTableKeys.contains(normalizeName(e.getTableName()));
                boolean typeInScope = classificationMatchesTypes(e, selected);
                if (!tableInScope) continue;
                // A classification is unique per physical column. If a narrower profile excludes the
                // existing type, preserve and lock that column instead of inserting a second type into
                // the same unique key during regeneration.
                if (shouldLockExistingClassification(typeInScope, e.getStatus())) {
                    locked.add(colKey(e.getTableName(), e.getColumnName()));
                    if (typeInScope && !"SUGGESTED".equals(e.getStatus())) found.add(e);
                    continue;
                }
                refreshable.put(colKey(e.getTableName(), e.getColumnName()), e);
            }

            List<String> tables = selectScanTables(scannableTables(c, schema), selectedTableKeys, schema);
            scanProgress.tablesDiscovered(List.copyOf(tables));
            int tableIndex = 0;
            for (String table : tables) {
                tableIndex++;
                scanProgress.tableStarted(table, tableIndex, tables.size());
                Map<String, String> colTypes = new LinkedHashMap<>();
                try (ResultSet rs = c.getMetaData().getColumns(null, schema, table, "%")) {
                    while (rs.next()) colTypes.put(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"));
                }
                scanProgress.tableColumns(table, colTypes.size());
                int scannedColumns = 0;
                int tableFindings = 0;
                for (Map.Entry<String, String> col : colTypes.entrySet()) {
                    scannedColumns++;
                    if (locked.contains(colKey(table, col.getKey()))) {
                        scanProgress.columnScanned(table, col.getKey(), scannedColumns, colTypes.size());
                        continue;   // analyst already decided this column
                    }
                    Scored scored = classify(c, schema, table, col.getKey(), col.getValue(), nameHints, valueHints);
                    if (scored != null) {
                        String key = colKey(table, col.getKey());
                        // Refresh the existing machine suggestion in place so rescans never collide with uq_class.
                        ClassificationEntity e = refreshable.remove(key);
                        if (e == null) e = new ClassificationEntity();
                        e.setDataSourceId(dataSourceId);
                        e.setSchemaName(schema);
                        e.setTableName(table);
                        e.setColumnName(col.getKey());
                        e.setDataType(col.getValue());
                        String priorStatus = e.getStatus();
                        String priorStructuredReview = e.getStructuredReview();
                        boolean structuredFinding = scored.contentFormat != null && !scored.contentFormat.isBlank();
                        e.setPiiType(structuredFinding ? "STRUCTURED_DATA" : scored.piiType);
                        e.setConfidence(Math.round(scored.confidence * 100.0) / 100.0);
                        // Never assign a masker that is incompatible with the column's data type
                        // (e.g. a name/text function on a BIGINT or a DATE column).
                        String fn = structuredFinding ? "FORMAT_PRESERVE"
                                : typeSafeFunction(suggested.getOrDefault(scored.piiType, "FORMAT_PRESERVE"), col.getValue());
                        e.setSuggestedFunction(fn);
                        e.setSuggestedParam1(structuredFinding ? null : defaultParam1(fn, scored.piiType));
                        e.setSuggestedParam2(structuredFinding ? null : defaultParam2(fn, scored.piiType));
                        e.setSampleValue(scored.sample);
                        e.setContentFormat(scored.contentFormat);
                        e.setLogicalPaths(scored.logicalPaths);
                        e.setPathReviewRequired(scored.pathReviewRequired);
                        if (structuredFinding) {
                            List<StructuredReviewCodec.Field> fields = structuredReviewFromEvidence(
                                    scored.logicalPaths, scored.confidence, scored.sample, priorStatus,
                                    priorStructuredReview, suggested);
                            e.setStructuredReview(StructuredReviewCodec.encode(fields));
                            e.setStatus(aggregateStructuredStatus(fields));
                        } else {
                            e.setStructuredReview(null);
                            e.setStatus("SUGGESTED");
                        }
                        e.setDiscoveredAt(java.time.Instant.now());
                        found.add(classifications.save(e));
                        tableFindings++;
                        scanProgress.findingDiscovered(table, col.getKey(), e.getPiiType());
                    }
                    scanProgress.columnScanned(table, col.getKey(), scannedColumns, colTypes.size());
                }
                scanProgress.tableCompleted(table, tableFindings);
            }
            // Suggestions still present were in scope but were not rediscovered. Reviewed and
            // out-of-scope classifications were locked above and are never removed here.
            if (!refreshable.isEmpty()) classifications.deleteAll(refreshable.values());
            } finally {
                scanScope.close();
            }
        } catch (ScanScopeException e) {
            auditRejectedScope(dataSourceId, resolvedSchema == null ? schemaName : resolvedSchema, e);
            throw e;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Discovery scan failed: " + e.getMessage()); }

        audit.log("system", "DISCOVERY_SCAN", "datasource=" + ds.getName() + " schema=" + schemaName
                + " piiTypes=" + selected + " tables=" + selectedTableKeys + " findings=" + found.size());
        return found;
    }

    /**
     * A physical column has one classification row. Keep overlapping rescans serialized until the
     * transaction commits so two direct API calls cannot both observe a missing row and race into
     * the unique key. Unrelated data-source/schema scopes still scan concurrently.
     */
    private static ScanScopeLease acquireScanScope(Long dataSourceId, String schema) {
        String key = dataSourceId + "\u0000" + normalizeName(schema);
        ReentrantLock lock = SCAN_SCOPE_LOCKS.computeIfAbsent(key, ignored -> new ReentrantLock(true));
        lock.lock();
        boolean transactionManaged = TransactionSynchronizationManager.isSynchronizationActive();
        if (transactionManaged) {
            try {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        releaseScanScope(lock);
                    }
                });
            } catch (RuntimeException e) {
                releaseScanScope(lock);
                throw e;
            }
        }
        return new ScanScopeLease(lock, transactionManaged);
    }

    private static void releaseScanScope(ReentrantLock lock) {
        lock.unlock();
    }

    private record ScanScopeLease(ReentrantLock lock, boolean transactionManaged) implements AutoCloseable {
        @Override
        public void close() {
            if (!transactionManaged) releaseScanScope(lock);
        }
    }

    public List<String> validateScanScope(Long dataSourceId, String schemaName, Set<String> selectedTables) {
        DataSourceEntity ds = dataSources.getSourceCapable(dataSourceId);
        try (Connection c = connections.openPooled(ds)) {
            String schema = DataSourceService.normalizeSchema(c, schemaName);
            validateRequestedSchemaVisible(c, schemaName, schema);
            return List.copyOf(selectScanTables(scannableTables(c, schema), normalizeNames(selectedTables), schema));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Unable to validate discovery schema: " + e.getMessage());
        }
    }

    private static List<String> scannableTables(Connection c, String schema) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getTables(null, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String table = rs.getString("TABLE_NAME");
                if (!table.toLowerCase(Locale.ROOT).startsWith("flyway_")) tables.add(table);
            }
        }
        return tables;
    }

    /**
     * An explicit schema name must not silently behave like an empty schema when metadata can see
     * that it does not exist.  Drivers that cannot enumerate schemas are left to table metadata,
     * where an empty result remains a genuine no-scannable-tables rejection.
     */
    private static void validateRequestedSchemaVisible(Connection c, String requestedSchema, String resolvedSchema) throws SQLException {
        if (requestedSchema == null || requestedSchema.isBlank() || "__default__".equals(requestedSchema)) return;
        boolean metadataReturnedRows = false;
        boolean schemaVisible = false;
        try (ResultSet rs = c.getMetaData().getSchemas()) {
            while (rs.next()) {
                metadataReturnedRows = true;
                String physical = rs.getString("TABLE_SCHEM");
                if (physical != null && physical.equalsIgnoreCase(resolvedSchema)) {
                    schemaVisible = true;
                    break;
                }
            }
        } catch (SQLFeatureNotSupportedException ignored) {
            return;
        } catch (AbstractMethodError ignored) {
            return;
        }
        if (metadataReturnedRows && !schemaVisible) {
            throw scopeInvalid("SCHEMA_NOT_VISIBLE", "Schema " + auditSchema(resolvedSchema)
                    + " does not exist or is not visible to this connection.");
        }
    }

    private static List<String> selectScanTables(List<String> available, Set<String> selectedTableKeys, String schema) {
        List<String> tables = new ArrayList<>(available);
        if (!selectedTableKeys.isEmpty()) {
            Set<String> discovered = new HashSet<>();
            for (String table : tables) discovered.add(normalizeName(table));
            List<String> missing = selectedTableKeys.stream().filter(name -> !discovered.contains(name)).sorted().toList();
            if (!missing.isEmpty()) {
                throw scopeInvalid("MISSING_FOCUSED_TABLES", "Table focus contains table(s) not found in schema "
                        + auditSchema(schema) + ": " + String.join(", ", missing));
            }
            tables.removeIf(table -> !selectedTableKeys.contains(normalizeName(table)));
        }
        if (tables.isEmpty()) {
            throw scopeInvalid("NO_SCANNABLE_TABLES", "Schema " + auditSchema(schema)
                    + " contains no scannable tables. Add tables or select another schema before starting discovery.");
        }
        return tables;
    }

    private void auditRejectedScope(Long dataSourceId, String schema, ScanScopeException failure) {
        String safeSchema = auditSchema(schema);
        audit.record("system", "DISCOVERY_SCAN_REJECTED", "DISCOVERY", "DISCOVERY_SCOPE",
                "datasource:" + dataSourceId, safeSchema, "FAILURE", "reason=" + failure.reason(),
                "{\"schema\":\"" + safeSchema + "\",\"reason\":\"" + failure.reason() + "\"}");
    }

    private static ScanScopeException scopeInvalid(String reason, String message) {
        return new ScanScopeException(reason, message);
    }

    private static String auditSchema(String schema) {
        String value = schema == null || schema.isBlank() ? "default" : schema.trim();
        value = value.replaceAll("[^A-Za-z0-9_.$-]", "_");
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    @Transactional
    public int bulkUpdateClassifications(List<Long> ids, String status) {
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVED", "REJECTED", "SUGGESTED").contains(normalizedStatus)) {
            throw ApiException.bad("status must be APPROVED, REJECTED or SUGGESTED");
        }
        if (ids == null || ids.isEmpty()) return 0;
        List<Long> uniqueIds = ids.stream().filter(Objects::nonNull).distinct().limit(10_000).toList();
        List<ClassificationEntity> rows = upgradeStructuredRows(classifications.findAllById(uniqueIds));
        assertClassificationSources(rows);
        rows.forEach(row -> applyClassificationStatus(row, normalizedStatus, Set.of()));
        classifications.saveAll(rows);
        audit.log("system", "CLASSIFICATIONS_BULK_UPDATED",
                "status=" + normalizedStatus + " requested=" + uniqueIds.size() + " updated=" + rows.size());
        return rows.size();
    }

    private record Scored(String piiType, double confidence, String sample, String contentFormat,
                          String logicalPaths, boolean pathReviewRequired) {
        private Scored(String piiType, double confidence, String sample) {
            this(piiType, confidence, sample, null, null, false);
        }
    }

    private Scored classify(Connection c, String schema, String table, String column, String typeName,
                            Map<String, Pattern> nameHints, Map<String, Pattern> valueHints) {
        List<String> samples = sample(c, schema, table, column, props.getDiscovery().getSampleRows());
        List<StructuredValueInspector.Inspection> inspections = samples.stream()
                .map(StructuredValueInspector::inspect).toList();
        boolean structured = inspections.stream()
                .anyMatch(inspection -> inspection.format() != StructuredValueInspector.Format.SCALAR);
        if (!structured) return classifyLegacy(c, schema, table, column, typeName, nameHints, valueHints, samples);

        String category = typeCategory(typeName);
        boolean valueSignalAllowed = !"NUMERIC".equals(category)
                && !"BOOLEAN".equals(category) && !"BINARY".equals(category);
        String physicalSemanticType = structuredPhysicalType(column);
        if (physicalSemanticType == null) {
            for (Map.Entry<String, Pattern> hint : nameHints.entrySet()) {
                if (hint.getValue().matcher(column).find()) {
                    physicalSemanticType = hint.getKey();
                    break;
                }
            }
        }
        Set<String> physicalNameTypes = physicalSemanticType == null
                ? Set.of() : Set.of(physicalSemanticType);
        boolean metadataOnlyColumn = structuredMetadataColumn(column) && physicalSemanticType == null;

        Map<String, Set<Integer>> logicalNameRows = new LinkedHashMap<>();
        Map<String, Set<Integer>> valueRows = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> pathsByType = new LinkedHashMap<>();
        Map<String, String> sampleByType = new LinkedHashMap<>();
        StructuredValueInspector.Format format = StructuredValueInspector.Format.SCALAR;
        int populatedRows = 0;
        for (int rowIndex = 0; rowIndex < samples.size(); rowIndex++) {
            String raw = samples.get(rowIndex);
            StructuredValueInspector.Inspection inspection = inspections.get(rowIndex);
            if (inspection.format() != StructuredValueInspector.Format.SCALAR) format = inspection.format();
            if (raw == null || raw.isBlank()) continue;
            populatedRows++;
            for (StructuredValueInspector.Leaf leaf : inspection.leaves()) {
                if (leaf.value() == null || leaf.value().isBlank()) continue;
                String logicalName = leaf.semanticName() == null ? pathName(leaf.path()) : leaf.semanticName();
                Set<String> logicalSignals = new LinkedHashSet<>();
                if (physicalSemanticType != null) {
                    logicalSignals.add(physicalSemanticType);
                    logicalNameRows.computeIfAbsent(physicalSemanticType, ignored -> new LinkedHashSet<>()).add(rowIndex);
                    addPathEvidence(pathsByType, physicalSemanticType, leaf.path());
                    sampleByType.putIfAbsent(physicalSemanticType, leaf.value());
                }
                String logicalType = preciseStructuredType(logicalName, nameHints);
                if (logicalType != null) {
                    logicalSignals.add(logicalType);
                    logicalNameRows.computeIfAbsent(logicalType, ignored -> new LinkedHashSet<>()).add(rowIndex);
                    addPathEvidence(pathsByType, logicalType, leaf.path());
                    sampleByType.putIfAbsent(logicalType, leaf.value());
                }
                // A named Temenos key/XML element is stronger than a coincidental value shape.
                // Metadata collections such as ID.TYPE are not PII merely because a code resembles a BIC.
                if (!valueSignalAllowed || metadataOnlyColumn || !logicalSignals.isEmpty()
                        || leaf.semanticName() != null) continue;
                for (Map.Entry<String, Pattern> hint : valueHints.entrySet()) {
                    if (valueMatches(hint.getKey(), hint.getValue(), leaf.value().trim(), logicalName,
                            nameHints.get(hint.getKey()))) {
                        valueRows.computeIfAbsent(hint.getKey(), ignored -> new LinkedHashSet<>()).add(rowIndex);
                        addPathEvidence(pathsByType, hint.getKey(), leaf.path());
                        sampleByType.putIfAbsent(hint.getKey(), leaf.value());
                    }
                }
            }
        }

        int denominator = Math.max(1, populatedRows);
        Set<String> candidates = new LinkedHashSet<>();
        candidates.addAll(physicalNameTypes);
        candidates.addAll(logicalNameRows.keySet());
        candidates.addAll(valueRows.keySet());
        String piiType = null;
        double confidence = 0;
        for (String candidate : candidates) {
            double nameScore = physicalNameTypes.contains(candidate) ? 1.0
                    : logicalNameRows.getOrDefault(candidate, Set.of()).size() / (double) denominator;
            if (nameScore < 0.6) nameScore = 0;
            double valueScore = valueRows.getOrDefault(candidate, Set.of()).size() / (double) denominator;
            if (valueScore < 0.6) valueScore = 0;
            double score;
            if (nameScore > 0 && valueScore > 0) score = 0.6 * nameScore + 0.4 * valueScore;
            else if (nameScore > 0) score = 0.6 * nameScore;
            // In a structured payload, an incidental value must not outrank an explicit
            // element/key name. Value-only evidence still creates a reviewable finding.
            else if (valueScore > 0) score = 0.35 + 0.2 * valueScore;
            else continue;
            if (score > confidence) {
                piiType = candidate;
                confidence = score;
            }
        }
        if (piiType == null) return null;

        String sampleShown = sampleByType.get(piiType);
        if (sampleShown == null) {
            sampleShown = inspections.stream().flatMap(i -> i.leaves().stream())
                    .map(StructuredValueInspector.Leaf::value).filter(Objects::nonNull).findFirst().orElse(null);
        }
        Set<String> observedTypes = new LinkedHashSet<>(pathsByType.keySet());
        boolean pathReviewRequired = format == StructuredValueInspector.Format.XML
                || observedTypes.size() > 1 || !HOMOGENEOUS_LEAF_MASKS.contains(piiType);
        return new Scored(piiType, Math.min(confidence, 0.99), redactSample(truncate(sampleShown)),
                format.name(), encodePathEvidence(pathsByType, inspections, piiType), pathReviewRequired);
    }

    private static String pathName(String path) {
        if (path == null || path.equals("$")) return null;
        String tail = path.substring(path.lastIndexOf('/') + 1).replaceAll("\\[\\d+\\]", "");
        if (tail.startsWith("@")) tail = tail.substring(1);
        return StructuredValueInspector.semanticAlias(tail);
    }

    /** Resolve one precise semantic type per structured leaf before considering broad/custom patterns. */
    private static String preciseStructuredType(String logicalName, Map<String, Pattern> nameHints) {
        if (logicalName == null || logicalName.isBlank()) return null;
        String normalized = StructuredValueInspector.semanticAlias(logicalName);
        String exact = switch (normalized == null ? "" : normalized) {
            case "first_name", "fname", "given_name" -> "FIRST_NAME";
            case "last_name", "lname", "surname", "family_name" -> "LAST_NAME";
            case "name", "full_name", "customer_name", "party_name" -> "FULL_NAME";
            case "phone", "phone_number", "mobile", "mobile_number", "telephone" -> "PHONE";
            case "email", "email_address" -> "EMAIL";
            case "account_number", "account_no", "account_num", "acct_no", "acct_num", "bank_account" -> "BANK_ACCOUNT";
            case "ccn", "card_number", "card_no", "credit_card", "pan" -> "CREDIT_CARD";
            case "national_id", "national_identifier", "tax_id", "tin" -> "TAX_ID";
            case "address", "full_address", "mailing_address", "billing_address", "shipping_address" -> "FULL_ADDRESS";
            case "address_line", "street", "street_address" -> "ADDRESS";
            case "dob", "date_of_birth", "birth_date", "birthdate" -> "DOB";
            case "iban" -> "IBAN";
            case "swift", "bic", "swift_bic" -> "SWIFT_BIC";
            case "routing", "routing_number", "aba", "sort_code", "ifsc" -> "ROUTING";
            default -> null;
        };
        if (exact != null && nameHints.containsKey(exact)) return exact;

        // Newly added custom patterns are later in the effective map, so evaluate them first.
        List<Map.Entry<String, Pattern>> candidates = new ArrayList<>(nameHints.entrySet());
        for (int i = candidates.size() - 1; i >= 0; i--) {
            Map.Entry<String, Pattern> candidate = candidates.get(i);
            if (candidate.getValue().matcher(logicalName).find()) return candidate.getKey();
        }
        return null;
    }

    private static String structuredPhysicalType(String column) {
        String normalized = normalizeName(column).replace('.', '_');
        if (normalized.matches("(^|.*_)(mv_)?id_(no|number|numbers)$")) return "PERSON_ID";
        return null;
    }

    private static boolean structuredMetadataColumn(String column) {
        String normalized = normalizeName(column).replace('.', '_');
        return normalized.matches(".*_(type|types|class|classes|status|statuses|flag|flags)$");
    }

    private static void addPathEvidence(Map<String, LinkedHashSet<String>> evidence, String type, String path) {
        if (path != null && !path.equals("$"))
            evidence.computeIfAbsent(type, ignored -> new LinkedHashSet<>()).add(path);
    }

    private static String encodePathEvidence(Map<String, LinkedHashSet<String>> evidence,
                                             List<StructuredValueInspector.Inspection> inspections,
                                             String selectedType) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        evidence.forEach((type, paths) -> paths.forEach(path -> tokens.add(path + " [" + type + "]")));
        if (tokens.isEmpty()) {
            inspections.stream().flatMap(i -> i.leaves().stream()).map(StructuredValueInspector.Leaf::path)
                    .filter(path -> !"$".equals(path)).limit(12)
                    .forEach(path -> tokens.add(path + " [" + selectedType + "]"));
        }
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String token : tokens) {
            if (count++ >= 16 || out.length() + token.length() > 3_900) break;
            if (out.length() > 0) out.append("; ");
            out.append(token);
        }
        return out.isEmpty() ? null : out.toString();
    }

    private Scored classifyLegacy(Connection c, String schema, String table, String column, String typeName,
                                  Map<String, Pattern> nameHints, Map<String, Pattern> valueHints,
                                  List<String> sample) {
        String category = typeCategory(typeName);
        // 1) name signal
        String best = null; double nameScore = 0;
        for (Map.Entry<String, Pattern> e : nameHints.entrySet()) {
            if (e.getValue().matcher(column).find()) { best = e.getKey(); nameScore = 1.0; break; }
        }
        // 2) value signal from a bounded sample.
        //    Skip value matching for NUMERIC/BOOLEAN columns: numbers rendered as strings match too many
        //    patterns (a 9-digit BIGINT id is not an SSN). For these, PII must be name-indicated, which also
        //    prevents suggesting a name/format masker for a key/amount column.
        String bestValueType = null; double valueScore = 0;
        boolean valueSignalAllowed = !"NUMERIC".equals(category) && !"BOOLEAN".equals(category) && !"BINARY".equals(category);
        if (valueSignalAllowed && !sample.isEmpty()) {
            Map<String, Integer> hits = new LinkedHashMap<>();
            for (String v : sample) {
                if (v == null || v.isBlank()) continue;
                for (Map.Entry<String, Pattern> e : valueHints.entrySet()) {
                    if (valueMatches(e.getKey(), e.getValue(), v.trim(), column, nameHints.get(e.getKey())))
                        hits.merge(e.getKey(), 1, Integer::sum);
                }
            }
            int n = sample.size();
            for (Map.Entry<String, Integer> h : hits.entrySet()) {
                double ratio = h.getValue() / (double) n;
                if (ratio >= 0.6 && ratio > valueScore) { valueScore = ratio; bestValueType = h.getKey(); }
            }
        }
        // 3) combine — value evidence can confirm or override a name hint
        String piiType; double conf;
        if (best != null && best.equals(bestValueType)) { piiType = best; conf = 0.6 + 0.4 * valueScore; }
        else if (best != null && bestValueType == null)  { piiType = best; conf = 0.6; }
        else if (best == null && bestValueType != null)  { piiType = bestValueType; conf = 0.4 * valueScore + 0.35; }
        else if (best != null)                           { piiType = bestValueType; conf = 0.4 * valueScore + 0.3; } // disagree: trust data
        else return null;
        String sampleShown = sample.stream().filter(Objects::nonNull).findFirst().orElse(null);
        return new Scored(piiType, Math.min(conf, 0.99), redactSample(truncate(sampleShown)));
    }

    /** Strong validators replace permissive regexes for identifiers with real check-digit contracts. */
    static boolean valueMatches(String piiType, Pattern configuredPattern, String value) {
        return valueMatches(piiType, configuredPattern, value, null, null);
    }

    static boolean valueMatches(String piiType, Pattern configuredPattern, String value,
                                String columnName, Pattern configuredNamePattern) {
        if ("CREDIT_CARD".equals(normalizeType(piiType))) return PiiPatterns.looksLikeCard(value);
        if ("IBAN".equals(normalizeType(piiType))) return PiiPatterns.looksLikeIban(value);
        // A date-shaped value cannot distinguish a DOB from an effective, expiration, posting, or audit date.
        // Require the effective (built-in or custom) DOB name pattern as semantic evidence.
        if ("DOB".equals(normalizeType(piiType))) {
            Pattern namePattern = configuredNamePattern == null ? PiiPatterns.NAME_HINTS.get("DOB") : configuredNamePattern;
            if (columnName == null || namePattern == null || !namePattern.matcher(columnName).find()) return false;
        }
        return configuredPattern != null && configuredPattern.matcher(value).matches();
    }

    private List<String> sample(Connection c, String schema, String table, String column, int rows) {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement()) {
            st.setMaxRows(rows);
            try (ResultSet rs = st.executeQuery("SELECT " + quote(column) + " FROM " + q(schema, table))) {
                while (rs.next()) out.add(readCellBounded(rs));
            }
        } catch (Exception ignored) { /* non-text or unreadable columns are simply skipped */ }
        return out;
    }

    private static String readCellBounded(ResultSet rs) throws SQLException {
        Reader reader = null;
        try {
            reader = rs.getCharacterStream(1);
            if (reader == null) return null;
            StringBuilder out = new StringBuilder(Math.min(4_096, MAX_SAMPLE_CELL_CHARS));
            char[] buffer = new char[4_096];
            int read;
            while (out.length() < MAX_SAMPLE_CELL_CHARS
                    && (read = reader.read(buffer, 0,
                    Math.min(buffer.length, MAX_SAMPLE_CELL_CHARS - out.length()))) >= 0) {
                out.append(buffer, 0, read);
            }
            return out.toString();
        } catch (java.io.IOException | SQLException streamFailure) {
            String value = rs.getString(1);
            return value == null || value.length() <= MAX_SAMPLE_CELL_CHARS
                    ? value : value.substring(0, MAX_SAMPLE_CELL_CHARS);
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) { }
        }
    }

    @Transactional
    public ClassificationEntity setStatus(Long id, String status) {
        return updateClassification(id, status, null, null, null);
    }

    /**
     * Review-queue editing: before (or after) approving a finding the analyst can both
     * change its status AND override the suggested masking function — the override is
     * what generate-policy compiles into the rule.
     */
    @Transactional
    public ClassificationEntity updateClassification(Long id, String status, String suggestedFunction,
                                                     String suggestedParam1, String suggestedParam2) {
        ClassificationEntity e = classifications.findById(id)
                .orElseThrow(() -> ApiException.notFound("Classification " + id + " not found"));
        dataSources.get(e.getDataSourceId());
        upgradeStructuredRows(List.of(e));
        List<StructuredReviewCodec.Field> structuredFields = StructuredReviewCodec.decode(e.getStructuredReview());
        if (status != null) {
            validateClassificationStatus(status);
            if (structuredFields.isEmpty()) {
                e.setStatus(status);
            } else {
                structuredFields = structuredFields.stream()
                        .map(field -> field.withDecision(status, null,
                                field.suggestedParam1(), field.suggestedParam2()))
                        .toList();
                e.setStructuredReview(StructuredReviewCodec.encode(structuredFields));
                e.setStatus(aggregateStructuredStatus(structuredFields));
            }
        }
        if (suggestedFunction != null && !suggestedFunction.isBlank()) {
            if (!structuredFields.isEmpty()) {
                throw ApiException.bad("Configure masking on each logical field inside "
                        + e.getTableName() + "." + e.getColumnName() + "; the structured parent has no masking function.");
            }
            String fn = suggestedFunction.trim().toUpperCase();
            try { io.forgetdm.core.mask.MaskFunction.valueOf(fn); }
            catch (Exception ex) { throw ApiException.bad("Unknown masking function: " + suggestedFunction); }
            String cat = typeCategory(e.getDataType());
            if (!maskCompatible(fn, cat))
                throw ApiException.bad(fn + " can't be applied to " + e.getDataType() + " column "
                        + e.getTableName() + "." + e.getColumnName() + " (" + cat + "). Use a " + cat.toLowerCase(Locale.ROOT)
                        + "-safe function such as " + safeDefaultForCategory(cat) + ".");
            e.setSuggestedFunction(fn);
            e.setSuggestedParam1(defaultParam1(fn, e.getPiiType()));
            e.setSuggestedParam2(defaultParam2(fn, e.getPiiType()));
            audit.log("system", "CLASSIFICATION_RULE_CHANGED",
                    e.getTableName() + "." + e.getColumnName() + " -> " + fn);
        }
        if (suggestedParam1 != null) e.setSuggestedParam1(emptyToNull(suggestedParam1));
        if (suggestedParam2 != null) e.setSuggestedParam2(emptyToNull(suggestedParam2));
        return classifications.save(e);
    }

    @Transactional
    public ClassificationEntity updateStructuredField(Long id, String selector, String status,
                                                       String suggestedFunction, String suggestedParam1,
                                                       String suggestedParam2) {
        ClassificationEntity e = classifications.findById(id)
                .orElseThrow(() -> ApiException.notFound("Classification " + id + " not found"));
        dataSources.get(e.getDataSourceId());
        upgradeStructuredRows(List.of(e));
        List<StructuredReviewCodec.Field> fields = new ArrayList<>(StructuredReviewCodec.decode(e.getStructuredReview()));
        if (fields.isEmpty()) throw ApiException.bad(e.getTableName() + "." + e.getColumnName() + " is not a structured finding");
        String normalizedSelector = StructuredMaskingCodec.normalize(selector);
        int index = -1;
        for (int i = 0; i < fields.size(); i++) {
            if (StructuredMaskingCodec.normalize(fields.get(i).selector()).equals(normalizedSelector)) {
                index = i;
                break;
            }
        }
        if (index < 0) throw ApiException.notFound("Logical field " + selector + " not found in classification " + id);
        StructuredReviewCodec.Field current = fields.get(index);
        String nextStatus = status == null ? current.status() : status.trim().toUpperCase(Locale.ROOT);
        validateClassificationStatus(nextStatus);
        String nextFunction = suggestedFunction == null || suggestedFunction.isBlank()
                ? current.suggestedFunction() : suggestedFunction.trim().toUpperCase(Locale.ROOT);
        try { io.forgetdm.core.mask.MaskFunction.valueOf(nextFunction); }
        catch (Exception ex) { throw ApiException.bad("Unknown masking function: " + suggestedFunction); }
        String nextParam1 = suggestedFunction == null
                ? current.suggestedParam1() : emptyToNull(suggestedParam1);
        String nextParam2 = suggestedFunction == null
                ? current.suggestedParam2() : emptyToNull(suggestedParam2);
        fields.set(index, current.withDecision(nextStatus, nextFunction, nextParam1, nextParam2));
        e.setStructuredReview(StructuredReviewCodec.encode(fields));
        e.setStatus(aggregateStructuredStatus(fields));
        e.setPiiType("STRUCTURED_DATA");
        e.setSuggestedFunction("FORMAT_PRESERVE");
        e.setSuggestedParam1(null);
        e.setSuggestedParam2(null);
        ClassificationEntity saved = classifications.save(e);
        audit.log("system", "STRUCTURED_CLASSIFICATION_UPDATED",
                e.getTableName() + "." + e.getColumnName() + " " + normalizedSelector
                        + " -> " + nextStatus + "/" + nextFunction);
        return saved;
    }

    private static void validateClassificationStatus(String status) {
        if (!Set.of("APPROVED", "REJECTED", "SUGGESTED").contains(status))
            throw ApiException.bad("Status must be APPROVED, REJECTED or SUGGESTED");
    }

    /** Compile every APPROVED classification of a data source into a ready-to-run masking policy. */
    @Transactional
    public MaskingPolicyEntity generatePolicy(Long dataSourceId, String policyName) {
        return generatePolicy(dataSourceId, null, policyName);
    }

    @Transactional
    public MaskingPolicyEntity generatePolicy(Long dataSourceId, String schemaName, String policyName) {
        String cleanPolicyName = PolicyNameRules.normalize(policyName);
        DataSourceEntity ds = dataSources.get(dataSourceId);
        String schema = schemaName;
        if (schema == null || schema.isBlank()) {
            try (Connection c = connections.openPooled(ds)) { schema = DataSourceService.schemaOf(c); }
            catch (Exception e) { schema = null; }
        }
        List<ClassificationEntity> candidates = schema == null
                ? classifications.findByDataSourceId(dataSourceId)
                : classifications.findByDataSourceIdAndSchemaName(dataSourceId, schema);
        List<ClassificationEntity> approved = upgradeStructuredRows(candidates).stream()
                .filter(DiscoveryService::isPolicyApproved)
                .toList();
        if (approved.isEmpty()) throw ApiException.bad("No APPROVED classifications for data source " + dataSourceId);
        Set<String> unsafeRiKeys = incompatibleRiMaskKeys(ds, schema, approved);
        Map<String, String> effectiveSuggested = new LinkedHashMap<>(PiiPatterns.SUGGESTED);
        PiiPatternService.Effective custom = customPatterns.resolveEffective();
        if (custom != null && custom.suggested() != null) effectiveSuggested.putAll(custom.suggested());
        Map<ClassificationEntity, String> structuredPlans = new IdentityHashMap<>();
        List<ClassificationEntity> safeApproved = approved.stream()
                .filter(cl -> !unsafeRiKeys.contains(colKey(cl.getTableName(), cl.getColumnName())))
                .filter(cl -> {
                    if (cl.getContentFormat() == null || cl.getContentFormat().isBlank()) return true;
                    String plan = structuredMaskingPlan(cl, effectiveSuggested);
                    if (plan == null) return false;
                    structuredPlans.put(cl, plan);
                    return true;
                })
                .toList();
        if (!unsafeRiKeys.isEmpty()) {
            audit.log("system", "POLICY_RI_RULE_SKIPPED",
                    cleanPolicyName + " skipped " + unsafeRiKeys.size()
                            + " one-sided or incompatible PK/FK mask rule(s): "
                            + String.join(", ", unsafeRiKeys));
        }
        if (safeApproved.isEmpty()) {
            throw ApiException.bad("No policy-ready APPROVED classifications for data source " + dataSourceId
                    + ". Resolve referential-integrity findings and approve structured path evidence first.");
        }
        MaskingPolicyEntity p = new MaskingPolicyEntity();
        p.setName(cleanPolicyName);
        p.setDataSourceId(dataSourceId);
        p.setSchemaName(schema);
        p.setDescription("Auto-generated from discovery of data source " + dataSourceId + (schema == null ? "" : " schema " + schema));
        p = policies.save(p);
        for (ClassificationEntity cl : safeApproved) {
            MaskingRuleEntity r = new MaskingRuleEntity();
            r.setPolicyId(p.getId());
            r.setSchemaName(cl.getSchemaName());
            r.setTableName(cl.getTableName());
            r.setColumnName(cl.getColumnName());
            r.setFunction(cl.getSuggestedFunction());
            r.setParam1(cl.getSuggestedParam1() == null ? defaultParam1(cl.getSuggestedFunction(), cl.getPiiType()) : cl.getSuggestedParam1());
            r.setParam2(cl.getSuggestedParam2() == null ? defaultParam2(cl.getSuggestedFunction(), cl.getPiiType()) : cl.getSuggestedParam2());
            r.setSemanticSalt(semanticSalt(cl.getPiiType()));
            r.setStructuredConfig(structuredPlans.get(cl));
            rules.save(r);
        }
        audit.log("system", "POLICY_GENERATED", cleanPolicyName + " (" + safeApproved.size() + " rules, "
                + structuredPlans.size() + " structured)");
        return p;
    }

    static String structuredMaskingPlan(ClassificationEntity classification,
                                        Map<String, String> suggestedFunctions) {
        if (classification.getContentFormat() == null || classification.getLogicalPaths() == null) return null;
        List<StructuredReviewCodec.Field> reviewed = StructuredReviewCodec.decode(classification.getStructuredReview());
        if (!reviewed.isEmpty()) {
            List<StructuredMaskingCodec.RuleSpec> approved = reviewed.stream()
                    .filter(field -> "APPROVED".equals(field.status()))
                    .map(field -> new StructuredMaskingCodec.RuleSpec(
                            StructuredMaskingCodec.normalize(field.selector()),
                            field.suggestedFunction(), semanticSalt(field.piiType()),
                            field.suggestedParam1(), field.suggestedParam2()))
                    .toList();
            return approved.isEmpty() ? null
                    : StructuredMaskingCodec.encode(classification.getContentFormat(), approved);
        }
        Pattern token = Pattern.compile("(?:^|;\\s*)(.+?)\\s+\\[([A-Z0-9_]+)](?=;|$)");
        java.util.regex.Matcher matcher = token.matcher(classification.getLogicalPaths());
        Map<String, StructuredMaskingCodec.RuleSpec> bySelector = new LinkedHashMap<>();
        while (matcher.find()) {
            String selector = StructuredMaskingCodec.normalize(matcher.group(1));
            String piiType = normalizeType(matcher.group(2));
            String function = suggestedFunctions.getOrDefault(piiType, "FORMAT_PRESERVE");
            bySelector.putIfAbsent(selector, new StructuredMaskingCodec.RuleSpec(selector, function,
                    semanticSalt(piiType), defaultParam1(function, piiType), defaultParam2(function, piiType)));
        }
        if (bySelector.isEmpty()) return null;
        return StructuredMaskingCodec.encode(classification.getContentFormat(), new ArrayList<>(bySelector.values()));
    }

    private List<ClassificationEntity> upgradeStructuredRows(List<ClassificationEntity> rows) {
        Map<String, String> suggested = new LinkedHashMap<>(PiiPatterns.SUGGESTED);
        PiiPatternService.Effective custom = customPatterns.resolveEffective();
        if (custom != null && custom.suggested() != null) suggested.putAll(custom.suggested());
        List<ClassificationEntity> changed = new ArrayList<>();
        for (ClassificationEntity row : rows) {
            if (row.getContentFormat() == null || row.getContentFormat().isBlank()
                    || row.getLogicalPaths() == null || row.getLogicalPaths().isBlank()) continue;
            boolean needsReview = row.getStructuredReview() == null || row.getStructuredReview().isBlank();
            boolean needsNeutralParent = !"STRUCTURED_DATA".equals(row.getPiiType())
                    || !"FORMAT_PRESERVE".equals(row.getSuggestedFunction())
                    || row.getSuggestedParam1() != null || row.getSuggestedParam2() != null;
            if (!needsReview && !needsNeutralParent) continue;
            List<StructuredReviewCodec.Field> fields = needsReview
                    ? structuredReviewFromEvidence(row.getLogicalPaths(), row.getConfidence(), row.getSampleValue(),
                            row.getStatus(), null, suggested)
                    : StructuredReviewCodec.decode(row.getStructuredReview());
            row.setStructuredReview(StructuredReviewCodec.encode(fields));
            row.setPiiType("STRUCTURED_DATA");
            row.setSuggestedFunction("FORMAT_PRESERVE");
            row.setSuggestedParam1(null);
            row.setSuggestedParam2(null);
            row.setStatus(aggregateStructuredStatus(fields));
            row.setPathReviewRequired(true);
            changed.add(row);
        }
        if (!changed.isEmpty()) classifications.saveAll(changed);
        return rows;
    }

    private static List<StructuredReviewCodec.Field> structuredReviewFromEvidence(
            String logicalPaths, double confidence, String sampleValue, String inheritedStatus,
            String existingJson, Map<String, String> suggestedFunctions) {
        Map<String, StructuredReviewCodec.Field> existing = new LinkedHashMap<>();
        for (StructuredReviewCodec.Field field : StructuredReviewCodec.decode(existingJson)) {
            existing.put(StructuredMaskingCodec.normalize(field.selector()), field);
        }
        Pattern token = Pattern.compile("(?:^|;\\s*)(.+?)\\s+\\[([A-Z0-9_]+)](?=;|$)");
        java.util.regex.Matcher matcher = token.matcher(logicalPaths == null ? "" : logicalPaths);
        Map<String, StructuredReviewCodec.Field> fields = new LinkedHashMap<>();
        while (matcher.find()) {
            String selector = StructuredMaskingCodec.normalize(matcher.group(1));
            String piiType = normalizeType(matcher.group(2));
            StructuredReviewCodec.Field prior = existing.get(selector);
            if (prior != null) {
                fields.put(selector, prior);
                continue;
            }
            String function = suggestedFunctions.getOrDefault(piiType, "FORMAT_PRESERVE");
            String status = "APPROVED".equals(inheritedStatus) || "REJECTED".equals(inheritedStatus)
                    ? inheritedStatus : "SUGGESTED";
            fields.put(selector, new StructuredReviewCodec.Field(selector, piiType, confidence,
                    sampleValue, status, function, defaultParam1(function, piiType), defaultParam2(function, piiType)));
        }
        return new ArrayList<>(fields.values());
    }

    private static String aggregateStructuredStatus(List<StructuredReviewCodec.Field> fields) {
        if (fields.isEmpty()) return "SUGGESTED";
        if (fields.stream().allMatch(field -> "APPROVED".equals(field.status()))) return "APPROVED";
        if (fields.stream().allMatch(field -> "REJECTED".equals(field.status()))) return "REJECTED";
        return "SUGGESTED";
    }

    private static boolean classificationMatchesTypes(ClassificationEntity classification, Set<String> selected) {
        if (selected == null || selected.isEmpty()) return true;
        if (selected.contains(normalizeType(classification.getPiiType()))) return true;
        return StructuredReviewCodec.decode(classification.getStructuredReview()).stream()
                .anyMatch(field -> selected.contains(normalizeType(field.piiType())));
    }

    private static boolean isPolicyApproved(ClassificationEntity classification) {
        List<StructuredReviewCodec.Field> fields = StructuredReviewCodec.decode(classification.getStructuredReview());
        if (!fields.isEmpty()) return fields.stream().anyMatch(field -> "APPROVED".equals(field.status()));
        return "APPROVED".equals(classification.getStatus());
    }

    private static void applyClassificationStatus(ClassificationEntity classification, String status,
                                                  Set<String> piiTypes) {
        List<StructuredReviewCodec.Field> fields = StructuredReviewCodec.decode(classification.getStructuredReview());
        if (fields.isEmpty()) {
            classification.setStatus(status);
            return;
        }
        Set<String> selected = normalizeTypes(piiTypes);
        boolean allStructured = selected.contains("STRUCTURED_DATA");
        fields = fields.stream().map(field ->
                selected.isEmpty() || allStructured || selected.contains(normalizeType(field.piiType()))
                        ? field.withDecision(status, null, field.suggestedParam1(), field.suggestedParam2())
                        : field).toList();
        classification.setStructuredReview(StructuredReviewCodec.encode(fields));
        classification.setStatus(aggregateStructuredStatus(fields));
    }

    private static String semanticSalt(String piiType) {
        return "pii." + normalizeType(piiType).toLowerCase(Locale.ROOT);
    }

    /**
     * A deterministic mask preserves a relationship only when both columns use the
     * same function and parameters. Auto-generated policies therefore omit a
     * classified key when its linked key is absent or configured differently.
     */
    private Set<String> incompatibleRiMaskKeys(DataSourceEntity ds, String requestedSchema,
                                                List<ClassificationEntity> approved) {
        Map<String, ClassificationEntity> byColumn = new HashMap<>();
        approved.forEach(cl -> byColumn.put(colKey(cl.getTableName(), cl.getColumnName()), cl));
        Set<String> unsafe = new TreeSet<>();

        try (Connection c = connections.openPooled(ds)) {
            String schema = DataSourceService.normalizeSchema(c, requestedSchema);
            SqlDialect dialect = SqlDialect.of(ds);
            String catalog = dialect == SqlDialect.MYSQL
                    ? (schema == null || schema.isBlank() ? c.getCatalog() : schema)
                    : null;
            String schemaPattern = dialect == SqlDialect.MYSQL ? null : schema;
            DatabaseMetaData metadata = c.getMetaData();
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = metadata.getTables(catalog, schemaPattern, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    if (table != null && !SqlDialect.isSystemTable(table)) tables.add(table);
                }
            }

            for (String table : tables) {
                try (ResultSet rs = metadata.getImportedKeys(catalog, schemaPattern, table)) {
                    while (rs.next()) {
                        addUnsafeRiPair(byColumn, unsafe,
                                rs.getString("PKTABLE_NAME"), rs.getString("PKCOLUMN_NAME"),
                                rs.getString("FKTABLE_NAME"), rs.getString("FKCOLUMN_NAME"));
                    }
                }
            }
        } catch (SQLException e) {
            throw ApiException.bad("Cannot validate PK/FK masking compatibility for generated policy: "
                    + e.getMessage());
        }
        return unsafe;
    }

    static void addUnsafeRiPair(Map<String, ClassificationEntity> byColumn, Set<String> unsafe,
                                String parentTable, String parentColumn,
                                String childTable, String childColumn) {
        String parentKey = colKey(parentTable, parentColumn);
        String childKey = colKey(childTable, childColumn);
        ClassificationEntity parent = byColumn.get(parentKey);
        ClassificationEntity child = byColumn.get(childKey);
        if (parent == null && child == null) return;
        if (parent == null) {
            unsafe.add(childKey);
        } else if (child == null) {
            unsafe.add(parentKey);
        } else if (!sameEffectiveMask(parent, child)) {
            unsafe.add(parentKey);
            unsafe.add(childKey);
        }
    }

    static boolean sameEffectiveMask(ClassificationEntity left, ClassificationEntity right) {
        String leftFunction = normalizeType(left.getSuggestedFunction());
        String rightFunction = normalizeType(right.getSuggestedFunction());
        String leftParam1 = emptyToNull(left.getSuggestedParam1() == null
                ? defaultParam1(leftFunction, left.getPiiType()) : left.getSuggestedParam1());
        String rightParam1 = emptyToNull(right.getSuggestedParam1() == null
                ? defaultParam1(rightFunction, right.getPiiType()) : right.getSuggestedParam1());
        String leftParam2 = emptyToNull(left.getSuggestedParam2() == null
                ? defaultParam2(leftFunction, left.getPiiType()) : left.getSuggestedParam2());
        String rightParam2 = emptyToNull(right.getSuggestedParam2() == null
                ? defaultParam2(rightFunction, right.getPiiType()) : right.getSuggestedParam2());
        return leftFunction.equals(rightFunction)
                && Objects.equals(leftParam1, rightParam1)
                && Objects.equals(leftParam2, rightParam2);
    }

    public List<ClassificationEntity> results(Long dataSourceId) {
        dataSources.get(dataSourceId);
        return stableOrder(upgradeStructuredRows(classifications.findByDataSourceId(dataSourceId)));
    }

    public List<ClassificationEntity> results(Long dataSourceId, String schemaName) {
        dataSources.get(dataSourceId);
        List<ClassificationEntity> rows = (schemaName == null || schemaName.isBlank())
                ? classifications.findByDataSourceId(dataSourceId)
                : classifications.findByDataSourceIdAndSchemaName(dataSourceId, schemaName);
        return stableOrder(upgradeStructuredRows(rows));
    }

    /** Fixed (table, column) order so editing a finding's status/mask never reshuffles the review list.
     *  Without this, the DB returns rows in physical order, which changes when a row is updated. */
    private static List<ClassificationEntity> stableOrder(List<ClassificationEntity> rows) {
        return rows.stream()
                .sorted(Comparator.comparing((ClassificationEntity e) -> e.getTableName() == null ? "" : e.getTableName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(e -> e.getColumnName() == null ? "" : e.getColumnName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ClassificationEntity::getId))
                .toList();
    }

    private void assertClassificationSources(Collection<ClassificationEntity> rows) {
        rows.stream().map(ClassificationEntity::getDataSourceId).filter(Objects::nonNull).distinct()
                .forEach(dataSources::get);
    }

    public List<ClassificationEntity> results(Long dataSourceId, String schemaName, String tableFilter) {
        List<ClassificationEntity> rows = results(dataSourceId, schemaName);
        String filter = emptyToNull(tableFilter);
        if (filter == null) return rows;
        // Exact (case-insensitive) table match — the UI sends a specific table name, so "customer"
        // must NOT also match "customer_900".
        return rows.stream()
                .filter(r -> r.getTableName() != null && r.getTableName().equalsIgnoreCase(filter))
                .toList();
    }

    @Transactional
    public int approveAll(Long dataSourceId, String schemaName, String tableFilter) {
        List<ClassificationEntity> rows = results(dataSourceId, schemaName, tableFilter);
        rows.forEach(r -> applyClassificationStatus(r, "APPROVED", Set.of()));
        classifications.saveAll(rows);
        audit.log("system", "CLASSIFICATIONS_APPROVED",
                "datasource=" + dataSourceId + " schema=" + schemaName + " tableFilter=" + tableFilter + " count=" + rows.size());
        return rows.size();
    }

    public List<ClassificationEntity> results(Long dataSourceId, String schemaName, String tableFilter,
                                              Set<String> piiTypes) {
        List<ClassificationEntity> rows = results(dataSourceId, schemaName, tableFilter);
        Set<String> types = normalizeTypes(piiTypes);
        if (types.isEmpty()) return rows;
        return rows.stream()
                .filter(r -> classificationMatchesTypes(r, types))
                .toList();
    }

    @Transactional
    public int approveAll(Long dataSourceId, String schemaName, String tableFilter, Set<String> piiTypes) {
        List<ClassificationEntity> rows = results(dataSourceId, schemaName, tableFilter, piiTypes);
        rows.forEach(r -> applyClassificationStatus(r, "APPROVED", piiTypes));
        classifications.saveAll(rows);
        audit.log("system", "CLASSIFICATIONS_APPROVED",
                "datasource=" + dataSourceId + " schema=" + schemaName + " tableFilter=" + tableFilter +
                        " piiTypes=" + normalizeTypes(piiTypes) + " count=" + rows.size());
        return rows.size();
    }

    @Transactional
    public int rejectAll(Long dataSourceId, String schemaName, String tableFilter) {
        List<ClassificationEntity> rows = results(dataSourceId, schemaName, tableFilter);
        rows.forEach(r -> applyClassificationStatus(r, "REJECTED", Set.of()));
        classifications.saveAll(rows);
        audit.log("system", "CLASSIFICATIONS_REJECTED",
                "datasource=" + dataSourceId + " schema=" + schemaName + " tableFilter=" + tableFilter + " count=" + rows.size());
        return rows.size();
    }

    @Transactional
    public int rejectAll(Long dataSourceId, String schemaName, String tableFilter, Set<String> piiTypes) {
        List<ClassificationEntity> rows = results(dataSourceId, schemaName, tableFilter, piiTypes);
        rows.forEach(r -> applyClassificationStatus(r, "REJECTED", piiTypes));
        classifications.saveAll(rows);
        audit.log("system", "CLASSIFICATIONS_REJECTED",
                "datasource=" + dataSourceId + " schema=" + schemaName + " tableFilter=" + tableFilter +
                        " piiTypes=" + normalizeTypes(piiTypes) + " count=" + rows.size());
        return rows.size();
    }

    public List<Map<String, Object>> tableColumns(Long dataSourceId, String schemaName, String table) {
        return tableColumns(dataSourceId, schemaName, table, null);
    }

    public List<Map<String, Object>> tableColumns(Long dataSourceId, String schemaName, String table, Set<String> piiTypes) {
        DataSourceEntity ds = dataSources.get(dataSourceId);
        try (Connection c = connections.openPooled(ds)) {
            String schema = DataSourceService.normalizeSchema(c, schemaName);
            Set<String> types = normalizeTypes(piiTypes);
            Map<String, ClassificationEntity> existing = new LinkedHashMap<>();
            upgradeStructuredRows(classifications.findByDataSourceIdAndSchemaNameAndTableName(dataSourceId, schema, table))
                    .forEach(cl -> {
                        if (classificationMatchesTypes(cl, types)) {
                            existing.put(columnKey(cl.getColumnName()), cl);
                        }
                    });

            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = c.getMetaData().getColumns(null, schema, table, "%")) {
                while (rs.next()) {
                    String column = rs.getString("COLUMN_NAME");
                    ClassificationEntity cl = existing.get(columnKey(column));
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("schemaName", schema);
                    row.put("tableName", table);
                    row.put("columnName", column);
                    row.put("dataType", rs.getString("TYPE_NAME"));
                    row.put("nullable", rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                    row.put("sampleValue", redactSample(sample(c, schema, table, column, 1).stream()
                            .filter(Objects::nonNull).findFirst().orElse(null)));
                    row.put("typeCategory", typeCategory(rs.getString("TYPE_NAME")));
                    if (cl == null) {
                        row.put("status", "NOT_PII");
                        row.put("confidence", 0.0);
                    } else {
                        row.put("classificationId", cl.getId());
                        row.put("status", cl.getStatus());
                        row.put("piiType", cl.getPiiType());
                        row.put("confidence", cl.getConfidence());
                        row.put("suggestedFunction", cl.getSuggestedFunction());
                        row.put("suggestedParam1", cl.getSuggestedParam1());
                        row.put("suggestedParam2", cl.getSuggestedParam2());
                        row.put("contentFormat", cl.getContentFormat());
                        row.put("logicalPaths", cl.getLogicalPaths());
                        row.put("pathReviewRequired", cl.isPathReviewRequired());
                    }
                    out.add(row);
                }
            }
            if (out.isEmpty()) throw ApiException.notFound("Table " + table + " not found in schema " + schema);
            return out;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Column review failed: " + e.getMessage()); }
    }

    @Transactional
    public ClassificationEntity markManual(Long dataSourceId, Map<String, String> body) {
        DataSourceEntity ds = dataSources.get(dataSourceId);
        String table = requireText(body.get("tableName"), "tableName");
        String column = requireText(body.get("columnName"), "columnName");
        String piiType = Optional.ofNullable(emptyToNull(body.get("piiType")))
                .map(v -> v.trim().toUpperCase(Locale.ROOT))
                .orElse("MANUAL_PII");
        String fn = normalizeMaskFunction(Optional.ofNullable(emptyToNull(body.get("suggestedFunction")))
                .orElse(PiiPatterns.SUGGESTED.getOrDefault(piiType, "FORMAT_PRESERVE")));
        String status = normalizeStatus(body.get("status"), "APPROVED");
        String p1 = Optional.ofNullable(emptyToNull(body.get("suggestedParam1"))).orElse(defaultParam1(fn, piiType));
        String p2 = Optional.ofNullable(emptyToNull(body.get("suggestedParam2"))).orElse(defaultParam2(fn, piiType));

        try (Connection c = connections.openPooled(ds)) {
            String schema = DataSourceService.normalizeSchema(c, body.get("schemaName"));
            Map<String, String> colMeta = findColumn(c, schema, table, column);
            String actualColumn = colMeta.get("column");
            String cat = typeCategory(colMeta.get("type"));
            if (!maskCompatible(fn, cat))
                throw ApiException.bad(fn + " can't be applied to " + colMeta.get("type") + " column "
                        + table + "." + actualColumn + " (" + cat + "). Use a " + cat.toLowerCase(Locale.ROOT)
                        + "-safe function such as " + safeDefaultForCategory(cat) + ".");
            ClassificationEntity e = classifications
                    .findByDataSourceIdAndSchemaNameAndTableNameAndColumnName(dataSourceId, schema, table, actualColumn)
                    .orElseGet(ClassificationEntity::new);
            e.setDataSourceId(dataSourceId);
            e.setSchemaName(schema);
            e.setTableName(table);
            e.setColumnName(actualColumn);
            e.setDataType(colMeta.get("type"));
            e.setPiiType(piiType);
            e.setConfidence(1.0);
            e.setSuggestedFunction(fn);
            e.setSuggestedParam1(p1);
            e.setSuggestedParam2(p2);
            e.setStatus(status);
            List<String> rawSamples = sample(c, schema, table, actualColumn, 1);
            String rawSample = rawSamples.stream().filter(Objects::nonNull).findFirst().orElse(null);
            StructuredValueInspector.Inspection inspection = StructuredValueInspector.inspect(rawSample);
            String displaySample = inspection.leaves().stream().map(StructuredValueInspector.Leaf::value)
                    .filter(Objects::nonNull).findFirst().orElse(rawSample);
            e.setSampleValue(redactSample(displaySample));
            if (inspection.format() == StructuredValueInspector.Format.SCALAR) {
                e.setContentFormat(null);
                e.setLogicalPaths(null);
                e.setPathReviewRequired(false);
            } else {
                e.setContentFormat(inspection.format().name());
                e.setLogicalPaths(encodePathEvidence(Map.of(), List.of(inspection), piiType));
                e.setPathReviewRequired(inspection.format() == StructuredValueInspector.Format.XML
                        || !HOMOGENEOUS_LEAF_MASKS.contains(piiType));
            }
            ClassificationEntity saved = classifications.save(e);
            audit.log("system", "CLASSIFICATION_MANUAL",
                    ds.getName() + " " + schema + "." + table + "." + actualColumn + " -> " + piiType + "/" + fn);
            return saved;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Manual PII classification failed: " + e.getMessage()); }
    }

    /**
     * Entity-relationship traversal model for the UI:
     *  - nodes carry the PII columns discovered on each table (column, piiType, function, status)
     *  - edges are FK relationships, parent (PK side) -> child (FK side), with the join columns
     * This is what lets a user applying a rule on t1 see that child t2 also carries a name field.
     */
    public Map<String, Object> graph(Long dataSourceId, String schemaName) {
        return graph(dataSourceId, schemaName, null);
    }

    public Map<String, Object> graph(Long dataSourceId, String schemaName, Set<String> piiTypes) {
        DataSourceEntity ds = dataSources.get(dataSourceId);
        try (Connection c = connections.openPooled(ds)) {
            String schema = DataSourceService.normalizeSchema(c, schemaName);
            List<ClassificationEntity> findings = results(dataSourceId, schema, null, piiTypes);
            Map<String, List<Map<String, Object>>> piiByTable = new LinkedHashMap<>();
            for (ClassificationEntity f : findings) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("column", f.getColumnName());
                col.put("piiType", f.getPiiType());
                col.put("function", f.getSuggestedFunction());
                col.put("param1", f.getSuggestedParam1());
                col.put("param2", f.getSuggestedParam2());
                col.put("status", f.getStatus());
                col.put("confidence", f.getConfidence());
                piiByTable.computeIfAbsent(f.getTableName(), k -> new ArrayList<>()).add(col);
            }

            List<Map<String, Object>> nodes = new ArrayList<>();
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = c.getMetaData().getTables(null, schema, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    if (table.toLowerCase().startsWith("flyway_")) continue;
                    tables.add(table);
                    List<Map<String, Object>> piiCols = piiByTable.getOrDefault(table, List.of());
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("id", table);
                    node.put("label", table);
                    node.put("piiCount", (long) piiCols.size());
                    node.put("piiColumns", piiCols);
                    nodes.add(node);
                }
            }

            List<Map<String, Object>> edges = new ArrayList<>();
            for (String table : tables) {
                try (ResultSet rs = c.getMetaData().getImportedKeys(null, schema, table)) {
                    while (rs.next()) {
                        String parent = rs.getString("PKTABLE_NAME");
                        String child = rs.getString("FKTABLE_NAME");
                        String pk = rs.getString("PKCOLUMN_NAME");
                        String fk = rs.getString("FKCOLUMN_NAME");
                        Map<String, Object> edge = new LinkedHashMap<>();
                        edge.put("id", edgeId(parent, child, pk, fk));
                        edge.put("from", parent);   // parent
                        edge.put("to", child);      // child
                        edge.put("pkColumn", pk);
                        edge.put("fkColumn", fk);
                        edge.put("label", fk + " -> " + pk);
                        edges.add(edge);
                    }
                }
            }
            List<Map<String, Object>> cycles = traversalCycles(tables, edges);
            Set<String> cycleEdgeIds = new LinkedHashSet<>();
            cycles.forEach(cycle -> ((List<?>) cycle.getOrDefault("edgeIds", List.of()))
                    .forEach(id -> cycleEdgeIds.add(String.valueOf(id))));
            return Map.of("schema", schema == null ? "" : schema,
                    "nodes", nodes,
                    "edges", edges,
                    "cycles", cycles,
                    "cycleEdgeIds", cycleEdgeIds,
                    "traversalMode", cycles.isEmpty() ? "ACYCLIC" : "CYCLE_GUARDED");
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Discovery graph failed: " + e.getMessage()); }
    }

    private static String edgeId(String parent, String child, String pk, String fk) {
        return parent + "." + pk + "->" + child + "." + fk;
    }

    private static List<Map<String, Object>> traversalCycles(List<String> tables, List<Map<String, Object>> edges) {
        Map<String, List<Map<String, Object>>> outgoing = new LinkedHashMap<>();
        for (String table : tables) outgoing.put(table, new ArrayList<>());
        for (Map<String, Object> edge : edges) {
            String from = String.valueOf(edge.get("from"));
            if (outgoing.containsKey(from)) outgoing.get(from).add(edge);
        }
        List<Map<String, Object>> cycles = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String start : tables) {
            findCycles(start, start, outgoing, new ArrayList<>(List.of(start)), new ArrayList<>(), seen, cycles, tables.size());
            if (cycles.size() >= 25) break;
        }
        return cycles;
    }

    private static void findCycles(String start, String current, Map<String, List<Map<String, Object>>> outgoing,
                                   List<String> path, List<String> edgePath, Set<String> seen,
                                   List<Map<String, Object>> cycles, int maxDepth) {
        if (path.size() > maxDepth + 1 || cycles.size() >= 25) return;
        for (Map<String, Object> edge : outgoing.getOrDefault(current, List.of())) {
            String next = String.valueOf(edge.get("to"));
            String edgeId = String.valueOf(edge.get("id"));
            if (next.equals(start)) {
                List<String> nodes = new ArrayList<>(path);
                String key = canonicalCycleKey(nodes);
                if (seen.add(key)) {
                    List<String> closed = new ArrayList<>(nodes);
                    closed.add(start);
                    List<String> cycleEdges = new ArrayList<>(edgePath);
                    cycleEdges.add(edgeId);
                    Map<String, Object> cycle = new LinkedHashMap<>();
                    cycle.put("id", "cycle-" + (cycles.size() + 1));
                    cycle.put("tables", closed);
                    cycle.put("edgeIds", cycleEdges);
                    cycle.put("length", nodes.size());
                    cycles.add(cycle);
                }
            } else if (!path.contains(next)) {
                path.add(next);
                edgePath.add(edgeId);
                findCycles(start, next, outgoing, path, edgePath, seen, cycles, maxDepth);
                edgePath.remove(edgePath.size() - 1);
                path.remove(path.size() - 1);
            }
        }
    }

    private static String canonicalCycleKey(List<String> cycleNodes) {
        String best = null;
        for (int i = 0; i < cycleNodes.size(); i++) {
            List<String> rotated = new ArrayList<>();
            for (int j = 0; j < cycleNodes.size(); j++) rotated.add(cycleNodes.get((i + j) % cycleNodes.size()));
            String key = String.join("->", rotated);
            if (best == null || key.compareTo(best) < 0) best = key;
        }
        return best == null ? "" : best;
    }

    static String quote(String ident) {
        if (!ident.matches("[A-Za-z0-9_]+")) throw ApiException.bad("Illegal identifier: " + ident);
        return "\"" + ident + "\"";
    }

    static String q(String schema, String table) {
        return schema == null || schema.isBlank() ? quote(table) : quote(schema) + "." + quote(table);
    }

    private static String truncate(String s) { return s == null ? null : (s.length() > 290 ? s.substring(0, 290) : s); }
    private static String emptyToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static String columnKey(String column) {
        return column == null ? "" : column.toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        String v = emptyToNull(value);
        if (v == null) throw ApiException.bad(field + " is required");
        return v;
    }

    private static String normalizeStatus(String value, String fallback) {
        String status = Optional.ofNullable(emptyToNull(value)).orElse(fallback).toUpperCase(Locale.ROOT);
        if (!status.equals("APPROVED") && !status.equals("REJECTED") && !status.equals("SUGGESTED"))
            throw ApiException.bad("Status must be APPROVED, REJECTED or SUGGESTED");
        return status;
    }

    private static String normalizeMaskFunction(String value) {
        String fn = requireText(value, "suggestedFunction").toUpperCase(Locale.ROOT);
        try { io.forgetdm.core.mask.MaskFunction.valueOf(fn); }
        catch (Exception ex) { throw ApiException.bad("Unknown masking function: " + value); }
        return fn;
    }

    private static Map<String, String> findColumn(Connection c, String schema, String table, String column) throws SQLException {
        try (ResultSet rs = c.getMetaData().getColumns(null, schema, table, "%")) {
            while (rs.next()) {
                String candidate = rs.getString("COLUMN_NAME");
                if (candidate != null && candidate.equalsIgnoreCase(column)) {
                    Map<String, String> out = new LinkedHashMap<>();
                    out.put("column", candidate);
                    out.put("type", rs.getString("TYPE_NAME"));
                    return out;
                }
            }
        }
        throw ApiException.notFound("Column " + table + "." + column + " not found in schema " + schema);
    }

    private static Set<String> normalizeTypes(Set<String> types) {
        if (types == null) return Set.of();
        Set<String> out = new HashSet<>();
        for (String t : types) if (t != null && !t.isBlank()) out.add(normalizeType(t));
        return out;
    }

    static boolean shouldLockExistingClassification(boolean typeInScope, String status) {
        return !typeInScope || !"SUGGESTED".equals(status);
    }

    private static Set<String> normalizeNames(Set<String> names) {
        if (names == null) return Set.of();
        Set<String> out = new HashSet<>();
        for (String name : names) if (name != null && !name.isBlank()) out.add(normalizeName(name));
        return out;
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeType(String type) {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }

    /** All scannable PII types (built-in + the current user's custom types) for the Scan Source selector. */
    public List<String> piiTypeCatalog() {
        Set<String> all = new TreeSet<>();
        all.addAll(PiiPatterns.NAME_HINTS.keySet());
        all.addAll(PiiPatterns.VALUE_HINTS.keySet());
        all.addAll(PiiPatterns.SUGGESTED.keySet());
        all.addAll(customPatterns.customTypes());
        return new ArrayList<>(all);
    }

    private static String colKey(String table, String column) {
        return (table == null ? "" : table.toLowerCase(Locale.ROOT)) + "." + (column == null ? "" : column.toLowerCase(Locale.ROOT));
    }

    /** Broad data-type category from a JDBC TYPE_NAME, used to keep maskers type-compatible. */
    static String typeCategory(String typeName) {
        String t = typeName == null ? "" : typeName.toLowerCase(Locale.ROOT);
        if (t.contains("bool") || t.equals("bit")) return "BOOLEAN";
        if (t.contains("date") || t.contains("time")) return "DATE";              // date / timestamp / time / timestamptz
        if (t.contains("char") || t.contains("text") || t.contains("clob")
                || t.contains("json") || t.contains("uuid") || t.contains("enum")) return "TEXT";
        if (t.contains("int") || t.contains("serial") || t.contains("numeric") || t.contains("decimal")
                || t.contains("real") || t.contains("double") || t.contains("float")
                || t.contains("money") || t.contains("number")) return "NUMERIC";
        if (t.contains("binary") || t.contains("blob") || t.contains("bytea") || t.contains("raw")) return "BINARY";
        return "TEXT";   // default permissive
    }

    /** True if a masking function can safely produce a value writable to a column of this type category. */
    static boolean maskCompatible(String fn, String category) {
        if (fn == null) return false;
        switch (fn) {                                   // type-agnostic — write any column
            case "NULLIFY": case "FIXED": case "PASSTHROUGH": return true;
            default: break;
        }
        return switch (category == null ? "TEXT" : category) {
            case "TEXT" -> true;                        // any masker renders to text
            case "NUMERIC" -> Set.of("FORMAT_PRESERVE", "CHARACTER_MAP", "SEQUENCE", "NUMERIC_NOISE",
                    "MIN_MAX", "SSN", "CREDIT_CARD", "PHONE", "BANK_ACCOUNT", "ABA_ROUTING").contains(fn);
            case "DATE" -> Set.of("DATE_SHIFT", "DOB_AGE_BAND", "AGE").contains(fn);
            default -> false;                           // BOOLEAN / BINARY → only the type-agnostic ones above
        };
    }

    static String safeDefaultForCategory(String category) {
        return switch (category == null ? "TEXT" : category) {
            case "NUMERIC" -> "FORMAT_PRESERVE";
            case "DATE" -> "DATE_SHIFT";
            case "BOOLEAN", "BINARY" -> "NULLIFY";
            default -> "FORMAT_PRESERVE";
        };
    }

    /** Pick the function, but never one incompatible with the column's data type. */
    static String typeSafeFunction(String fn, String typeName) {
        String cat = typeCategory(typeName);
        return maskCompatible(fn, cat) ? fn : safeDefaultForCategory(cat);
    }

    /** Never persist/show raw PII in the review sample — keep just enough to recognize the format. */
    static String redactSample(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        int at = v.indexOf('@');
        if (at > 0) return v.charAt(0) + "***" + v.substring(at);     // email: keep first char + domain
        if (v.length() <= 2) return "**";
        if (v.length() <= 4) return v.charAt(0) + "***";
        return v.charAt(0) + "***" + v.substring(v.length() - 2);     // first char + last two
    }

    static String defaultParam1(String fn, String pii) {
        if ("FULL_NAME".equals(fn)) return "FIRST LAST";
        if ("EMAIL".equals(fn)) return "NAME_SAFE";
        if ("PHONE".equals(fn)) return "FORMAT_PRESERVE";
        if ("SSN".equals(fn)) return "VALID_PRESERVE_AREA";
        if ("CREDIT_CARD".equals(fn)) return "VALID_PRESERVE_BIN";
        if ("CITY_STATE_ZIP".equals(fn))
            return "CITY".equals(pii) ? "CITY" : "STATE".equals(pii) ? "STATE" : "ZIP".equals(pii) ? "ZIP" : "FULL";
        if ("ADDRESS_US".equals(fn)) return "FULL";
        if ("IBAN".equals(fn)) return "PRESERVE_COUNTRY";
        if ("SWIFT_BIC".equals(fn)) return "PRESERVE_COUNTRY";
        if ("BANK_ACCOUNT".equals(fn)) return "KEEP_LAST4";
        if ("ABA_ROUTING".equals(fn)) return "PRESERVE_FED_DISTRICT";
        if ("NATIONAL_ID".equals(fn)) return "GENERIC";
        if ("DATE_SHIFT".equals(fn) && "CARD_EXPIRY".equals(pii)) return "0:365";
        if ("IP_ADDRESS".equals(fn)) return "SAFE_TEST_RANGE";
        if ("MAC_ADDRESS".equals(fn)) return "LOCAL_ADMIN";
        if ("TOKENIZE".equals(fn)) return switch (String.valueOf(pii)) {
            case "MEDICAL_RECORD_NUMBER" -> "MRN_";
            case "HEALTH_PLAN_ID" -> "HPL_";
            case "PRESCRIPTION_ID" -> "RX_";
            case "BIOMETRIC_ID" -> "BIO_";
            case "GENETIC_DATA" -> "GEN_";
            case "DEVICE_ID" -> "DEV_";
            case "COOKIE_ID" -> "CK_";
            case "PERSON_ID" -> "PID_";
            case "VEHICLE_ID" -> "VEH_";
            case "URL" -> "URL_";
            default -> "USR_";
        };
        if ("NUMERIC_NOISE".equals(fn) && "AGE".equals(pii)) return "ABS:2";
        if ("SECURE_LOOKUP".equals(fn) && "GENDER".equals(pii)) return "F|M|X";
        return null;
    }

    static String defaultParam2(String fn, String pii) {
        if (Set.of("FIRST_NAME", "LAST_NAME", "FULL_NAME", "COMPANY", "ADDRESS_STREET").contains(fn)) return "PROPER";
        if ("EMAIL".equals(fn)) return "SAFE_DOMAIN";
        if ("PHONE".equals(fn)) return "PRESERVE_COUNTRY";
        if ("SSN".equals(fn) || "CREDIT_CARD".equals(fn)) return "PRESERVE_FORMAT";
        if ("CITY_STATE_ZIP".equals(fn) || "ADDRESS_US".equals(fn)) return "PRESERVE_STATE";
        if ("IBAN".equals(fn)) return "PRESERVE_FORMAT";
        if ("NATIONAL_ID".equals(fn)) return "PRESERVE_FORMAT";
        if ("TOKENIZE".equals(fn)) return "24";
        if ("NUMERIC_NOISE".equals(fn) && "AGE".equals(pii)) return "0:120";
        if ("SECURE_LOOKUP".equals(fn)) return "UPPER";
        return null;
    }
}
