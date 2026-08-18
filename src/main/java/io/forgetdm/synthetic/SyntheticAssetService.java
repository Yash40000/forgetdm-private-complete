package io.forgetdm.synthetic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.synth.Generators;
import io.forgetdm.provision.SyntheticGenService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.OwnershipGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Governed registry for reusable synthetic design assets.
 *
 * <p>Drafts are mutable, published versions are immutable, and scenarios compile into the
 * existing {@link SyntheticGenService.GenPlan}. This keeps one execution engine while adding
 * reusable models, contracts, rules, delivery profiles, dependency impact, and exact lineage.</p>
 */
@Service
public class SyntheticAssetService {
    public static final String DATA_MODEL = "DATA_MODEL";
    public static final String FIELD_CONTRACT = "FIELD_CONTRACT";
    public static final String GENERATION_RULE = "GENERATION_RULE";
    public static final String DELIVERY_PROFILE = "DELIVERY_PROFILE";
    public static final String GENERATION_SCENARIO = "GENERATION_SCENARIO";

    private static final Set<String> TYPES = Set.of(
            DATA_MODEL, FIELD_CONTRACT, GENERATION_RULE, DELIVERY_PROFILE, GENERATION_SCENARIO);
    private static final Set<String> RECEIVERS = Set.of("DB", "CSV", "JSON", "SQL");
    private static final Set<String> VISIBILITIES = Set.of("PRIVATE", "GROUP", "SHARED");
    private static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "DEPRECATED", "ARCHIVED");
    private static final Set<String> GENERATORS = new LinkedHashSet<>(Generators.catalog());

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OwnershipGuard ownership;
    private final AuditService audit;
    private final SyntheticGenService generator;

    public SyntheticAssetService(JdbcTemplate jdbc, ObjectMapper json, OwnershipGuard ownership,
                                 AuditService audit, SyntheticGenService generator) {
        this.jdbc = jdbc;
        this.json = json;
        this.ownership = ownership;
        this.audit = audit;
        this.generator = generator;
    }

    public List<AssetType> types() {
        return List.of(
                new AssetType(DATA_MODEL, "Data models", "Reusable tables, fields, keys, relationships, and row volumes.", starterModel()),
                new AssetType(FIELD_CONTRACT, "Field contracts", "Reusable type, nullability, uniqueness, and semantic field requirements.", starterContract()),
                new AssetType(GENERATION_RULE, "Generation rules", "Reusable generator configuration with deterministic parameters.", starterRule()),
                new AssetType(DELIVERY_PROFILE, "Delivery profiles", "Reusable receiver, target, load, safeguard, and partition settings.", starterDelivery()),
                new AssetType(GENERATION_SCENARIO, "Generation scenarios", "Pinned model, rule, and delivery versions compiled into one runnable plan.", starterScenario())
        );
    }

    public List<Map<String, Object>> plugins() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Generators.GeneratorSpec spec : Generators.catalogDetails()) {
            rows.add(Map.of(
                    "pluginType", "GENERATOR",
                    "key", spec.name(),
                    "version", "built-in",
                    "category", spec.category(),
                    "description", spec.description(),
                    "enabled", true,
                    "certificationStatus", "BUILT_IN"));
        }
        for (String receiver : RECEIVERS.stream().sorted().toList()) {
            rows.add(Map.of(
                    "pluginType", "RECEIVER",
                    "key", receiver,
                    "version", "built-in",
                    "category", "Delivery",
                    "description", receiverDescription(receiver),
                    "enabled", true,
                    "certificationStatus", "BUILT_IN"));
        }
        rows.addAll(jdbc.query("""
                SELECT plugin_type,plugin_key,plugin_version,descriptor_json,enabled,certification_status
                  FROM synthetic_plugin_registry
                 WHERE enabled=TRUE
                 ORDER BY plugin_type,plugin_key,plugin_version
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pluginType", rs.getString("plugin_type"));
            item.put("key", rs.getString("plugin_key"));
            item.put("version", rs.getString("plugin_version"));
            item.put("descriptor", readTree(rs.getString("descriptor_json")));
            item.put("enabled", rs.getBoolean("enabled"));
            item.put("certificationStatus", rs.getString("certification_status"));
            return item;
        }));
        return rows;
    }

    public List<AssetSummary> list(String assetType, String status, String query) {
        String type = optionalType(assetType);
        String cleanStatus = optionalStatus(status);
        String needle = text(query).toLowerCase(Locale.ROOT);
        return jdbc.query("SELECT * FROM synthetic_assets ORDER BY updated_at DESC,LOWER(name)",
                        (ResultSet rs, int index) -> summary(rs, index))
                .stream()
                .filter(row -> ownership.canSee(row.ownerUserId(), row.ownerGroupId(), row.visibility()))
                .filter(row -> type == null || type.equals(row.assetType()))
                .filter(row -> cleanStatus == null || cleanStatus.equals(row.status()))
                .filter(row -> needle.isBlank()
                        || row.name().toLowerCase(Locale.ROOT).contains(needle)
                        || text(row.description()).toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    public AssetDetail get(String id) {
        AssetRow row = require(id);
        return detail(row);
    }

    @Transactional
    public AssetDetail create(AssetRequest request) {
        if (request == null) throw ApiException.bad("Asset request is required");
        String type = requiredType(request.assetType());
        String name = validName(request.name());
        ensureNameAvailable(type, name, null);
        String id = UUID.randomUUID().toString();
        JsonNode draft = normalizedDraft(request.content(), starter(type));
        validateDefinition(type, draft, false);
        String visibility = visibility(request.visibility());
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO synthetic_assets
                  (id,asset_type,name,description,status,draft_json,current_version,
                   owner_user_id,owner_username,owner_group_id,visibility,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, type, name, limited(request.description(), 2000), "DRAFT", write(draft), 0,
                ownership.defaultOwnerUserId(), actor(), ownership.defaultOwnerGroupId(), visibility,
                Timestamp.from(now), Timestamp.from(now));
        audit(id, name, "SYNTHETIC_ASSET_CREATED", type + " draft created");
        return get(id);
    }

    @Transactional
    public AssetDetail update(String id, AssetRequest request) {
        AssetRow row = require(id);
        assertManage(row);
        if ("ARCHIVED".equals(row.status())) throw ApiException.bad("Archived assets cannot be edited");
        String name = validName(request == null ? row.name() : first(request.name(), row.name()));
        ensureNameAvailable(row.assetType(), name, id);
        JsonNode draft = normalizedDraft(request == null ? null : request.content(), row.draft());
        validateDefinition(row.assetType(), draft, false);
        String visibility = request == null || text(request.visibility()).isBlank()
                ? row.visibility() : visibility(request.visibility());
        jdbc.update("""
                UPDATE synthetic_assets
                   SET name=?,description=?,draft_json=?,visibility=?,status='DRAFT',updated_at=?
                 WHERE id=?
                """, name, limited(request == null ? row.description() : request.description(), 2000),
                write(draft), visibility, Timestamp.from(Instant.now()), id);
        audit(id, name, "SYNTHETIC_ASSET_DRAFT_UPDATED", row.assetType() + " draft updated");
        return get(id);
    }

    @Transactional
    public AssetDetail publish(String id) {
        AssetRow row = require(id);
        assertManage(row);
        if ("ARCHIVED".equals(row.status())) throw ApiException.bad("Archived assets cannot be published");

        ObjectNode pinned = asObject(row.draft()).deepCopy();
        List<DependencyRef> dependencies = pinReferences(pinned, id);
        validateDefinition(row.assetType(), pinned, true);
        int version = row.currentVersion() + 1;
        String versionId = UUID.randomUUID().toString();
        String content = write(pinned);
        String hash = sha256(content);
        jdbc.update("""
                INSERT INTO synthetic_asset_versions
                  (id,asset_id,version_no,schema_version,content_json,content_hash,
                   compatibility_level,published_by,published_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, versionId, id, version, 1, content, hash, "COMPATIBLE", actor(),
                Timestamp.from(Instant.now()));
        for (DependencyRef dependency : dependencies) {
            jdbc.update("""
                    INSERT INTO synthetic_asset_dependencies
                      (owner_version_id,dependency_asset_id,dependency_version,dependency_kind)
                    VALUES (?,?,?,?)
                    """, versionId, dependency.assetId(), dependency.version(), dependency.kind());
        }
        jdbc.update("""
                UPDATE synthetic_assets
                   SET draft_json=?,current_version=?,status='PUBLISHED',updated_at=?
                 WHERE id=?
                """, content, version, Timestamp.from(Instant.now()), id);
        if (GENERATION_SCENARIO.equals(row.assetType())) {
            compileAndPersist(row.id(), version, versionId, pinned);
        }
        audit(id, row.name(), "SYNTHETIC_ASSET_PUBLISHED",
                row.assetType() + " version " + version + " published hash=" + hash);
        return get(id);
    }

    @Transactional
    public AssetDetail cloneAsset(String id, CloneRequest request) {
        AssetRow source = require(id);
        VersionRow version = version(source, request == null ? null : request.version());
        String name = validName(request == null ? source.name() + " Copy" : request.name());
        return create(new AssetRequest(source.assetType(), name,
                first(request == null ? null : request.description(), source.description()),
                source.visibility(), version.content()));
    }

    @Transactional
    public AssetDetail changeStatus(String id, StatusRequest request) {
        AssetRow row = require(id);
        assertManage(row);
        String status = requiredStatus(request == null ? null : request.status());
        if ("PUBLISHED".equals(status)) return publish(id);
        jdbc.update("UPDATE synthetic_assets SET status=?,updated_at=? WHERE id=?",
                status, Timestamp.from(Instant.now()), id);
        audit(id, row.name(), "SYNTHETIC_ASSET_" + status, row.assetType() + " status changed to " + status);
        return get(id);
    }

    public List<VersionSummary> versions(String id) {
        AssetRow row = require(id);
        return jdbc.query("""
                SELECT v.*,
                       (SELECT COUNT(*) FROM synthetic_asset_dependencies d WHERE d.owner_version_id=v.id) dependency_count
                  FROM synthetic_asset_versions v
                 WHERE v.asset_id=?
                 ORDER BY v.version_no DESC
                """, (rs, index) -> new VersionSummary(
                rs.getString("id"), rs.getInt("version_no"), rs.getInt("schema_version"),
                rs.getString("content_hash"), rs.getString("compatibility_level"),
                rs.getString("published_by"), instant(rs.getTimestamp("published_at")),
                rs.getInt("dependency_count")), row.id());
    }

    public Map<String, Object> compare(String id, int from, int to) {
        AssetRow row = require(id);
        VersionRow left = version(row, from);
        VersionRow right = version(row, to);
        Set<String> before = flatten(left.content());
        Set<String> after = flatten(right.content());
        Set<String> added = new LinkedHashSet<>(after);
        added.removeAll(before);
        Set<String> removed = new LinkedHashSet<>(before);
        removed.removeAll(after);
        return Map.of(
                "assetId", id,
                "fromVersion", from,
                "toVersion", to,
                "fromHash", left.hash(),
                "toHash", right.hash(),
                "added", added.stream().sorted().toList(),
                "removed", removed.stream().sorted().toList(),
                "compatible", removed.isEmpty());
    }

    public List<ImpactItem> impact(String id) {
        AssetRow row = require(id);
        return jdbc.query("""
                SELECT a.id,a.asset_type,a.name,v.version_no,d.dependency_version,d.dependency_kind
                  FROM synthetic_asset_dependencies d
                  JOIN synthetic_asset_versions v ON v.id=d.owner_version_id
                  JOIN synthetic_assets a ON a.id=v.asset_id
                 WHERE d.dependency_asset_id=?
                 ORDER BY a.asset_type,LOWER(a.name),v.version_no DESC
                """, (rs, index) -> new ImpactItem(
                rs.getString("id"), rs.getString("asset_type"), rs.getString("name"),
                rs.getInt("version_no"), rs.getInt("dependency_version"),
                rs.getString("dependency_kind")), row.id());
    }

    @Transactional
    public CompiledScenario compile(String scenarioId, Integer version) {
        AssetRow scenario = require(scenarioId);
        if (!GENERATION_SCENARIO.equals(scenario.assetType())) {
            throw ApiException.bad("Only Generation Scenarios can be compiled");
        }
        VersionRow published = version(scenario, version);
        return compileAndPersist(scenario.id(), published.version(), published.id(), published.content());
    }

    @Transactional
    public Map<String, Object> launch(String scenarioId, LaunchRequest request) {
        CompiledScenario compiled = compile(scenarioId, request == null ? null : request.version());
        SyntheticGenService.GenPlan plan = compiled.plan();
        if (request != null && request.seed() != null) {
            plan = withSeed(plan, request.seed());
        }
        Map<String, Object> job = generator.startGenerate(plan);
        AssetRow scenario = require(scenarioId);
        audit(scenarioId, scenario.name(), "SYNTHETIC_SCENARIO_LAUNCHED",
                "version=" + compiled.scenarioVersion() + " job=" + job.get("id")
                        + " planHash=" + compiled.planHash());
        Map<String, Object> result = new LinkedHashMap<>(job);
        result.put("scenarioAssetId", scenarioId);
        result.put("scenarioVersion", compiled.scenarioVersion());
        result.put("manifestId", compiled.manifestId());
        result.put("planHash", compiled.planHash());
        return result;
    }

    private CompiledScenario compileAndPersist(String scenarioId, int scenarioVersion,
                                               String scenarioVersionId, JsonNode scenarioContent) {
        SyntheticGenService.GenPlan plan = compilePlan(scenarioContent);
        JsonNode planNode = json.valueToTree(plan);
        List<Map<String, Object>> components = componentManifest(scenarioContent);
        String componentsJson = write(components);
        String planJson = write(planNode);
        String componentHash = sha256(componentsJson);
        String planHash = sha256(planJson);
        String manifestId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO synthetic_scenario_manifests
                  (id,scenario_asset_id,scenario_version,scenario_version_id,component_manifest_json,
                   compiled_plan_json,component_hash,plan_hash,compiled_by,compiled_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, manifestId, scenarioId, scenarioVersion, scenarioVersionId, componentsJson,
                planJson, componentHash, planHash, actor(), Timestamp.from(Instant.now()));
        return new CompiledScenario(manifestId, scenarioId, scenarioVersion, componentHash, planHash,
                components, plan, Instant.now());
    }

    private SyntheticGenService.GenPlan compilePlan(JsonNode scenario) {
        Reference modelRef = reference(scenario.path("modelRef"), "modelRef");
        VersionRow modelVersion = referencedVersion(modelRef, DATA_MODEL);
        JsonNode model = modelVersion.content();

        Map<String, Reference> bindings = new HashMap<>();
        for (JsonNode binding : array(scenario.path("bindings"))) {
            String table = requiredText(binding, "table", "Scenario binding table");
            String field = requiredText(binding, "field", "Scenario binding field");
            JsonNode ref = binding.has("ruleRef") ? binding.path("ruleRef") : binding.path("generationRuleRef");
            bindings.put(key(table, field), reference(ref, "binding ruleRef"));
        }

        List<SyntheticGenService.GenTable> tables = new ArrayList<>();
        for (JsonNode table : modelTables(model)) {
            String tableName = requiredText(table, "name", "Data Model table name");
            long rows = Math.max(0L, longValue(table.path("rowCount"), 100L));
            List<SyntheticGenService.GenColumn> columns = new ArrayList<>();
            for (JsonNode field : modelFields(table)) {
                String fieldName = requiredText(field, "name", "Data Model field name");
                ObjectNode effective = asObject(field).deepCopy();
                if (field.has("fieldContractRef")) {
                    VersionRow contract = referencedVersion(reference(field.path("fieldContractRef"),
                            "fieldContractRef"), FIELD_CONTRACT);
                    mergeMissing(effective, contract.content());
                }
                Reference ruleRef = bindings.get(key(tableName, fieldName));
                if (ruleRef == null && field.has("ruleRef")) {
                    ruleRef = reference(field.path("ruleRef"), "field ruleRef");
                }
                if (ruleRef != null) {
                    VersionRow rule = referencedVersion(ruleRef, GENERATION_RULE);
                    mergeOverride(effective, rule.content());
                }
                String generatorName = upper(firstText(effective.path("generator").asText(null), "ALPHANUMERIC"));
                if (!GENERATORS.contains(generatorName)) {
                    throw ApiException.bad("Unknown generator " + generatorName + " on " + tableName + "." + fieldName);
                }
                JsonNode reference = effective.path("references");
                String fkTable = firstText(effective.path("fkTable").asText(null), reference.path("table").asText(null));
                String fkColumn = firstText(effective.path("fkColumn").asText(null), reference.path("column").asText(null));
                columns.add(new SyntheticGenService.GenColumn(
                        fieldName, generatorName,
                        nullableText(effective.path("param1")), nullableText(effective.path("param2")),
                        boolValue(effective.path("primaryKey"), false),
                        nullable(fkTable), nullable(fkColumn),
                        firstText(effective.path("sqlType").asText(null), "VARCHAR"),
                        integerValue(effective.path("fkMin")), integerValue(effective.path("fkMax"))));
            }
            tables.add(new SyntheticGenService.GenTable(tableName, rows, columns));
        }

        Reference deliveryRef = reference(scenario.path("deliveryRef"), "deliveryRef");
        VersionRow deliveryVersion = referencedVersion(deliveryRef, DELIVERY_PROFILE);
        JsonNode delivery = deliveryVersion.content();
        JsonNode execution = scenario.path("execution");
        String receiver = upper(firstText(delivery.path("receiver").asText(null), "CSV"));
        List<SyntheticGenService.TargetSystem> targets = delivery.has("targetSystems")
                ? json.convertValue(delivery.path("targetSystems"),
                new TypeReference<List<SyntheticGenService.TargetSystem>>() {}) : null;
        return new SyntheticGenService.GenPlan(
                firstText(scenario.path("dataset").asText(null), "Reusable synthetic scenario"),
                tables,
                longValue(scenario.path("seed"), 42L),
                receiver,
                longObject(delivery.path("targetDataSourceId")),
                nullableText(delivery.path("targetSchema")),
                boolValue(delivery.path("createTable"), false),
                boolValue(delivery.path("dropTable"), false),
                nullableText(delivery.path("prepMode")),
                firstText(delivery.path("loadAction").asText(null), "INSERT"),
                firstText(delivery.path("targetPrep").asText(null), "NONE"),
                stringList(delivery.path("keyColumns")),
                intObject(delivery.path("batchSize")),
                intObject(delivery.path("commitEveryRows")),
                boolValue(delivery.path("continueOnError"), false),
                intObject(delivery.path("maxRejects")),
                boolValue(delivery.path("fastLoad"), false),
                firstText(execution.path("mode").asText(null), "SINGLE"),
                intObject(execution.path("partitionCount")),
                longObject(execution.path("partitionSize")),
                targets);
    }

    private void validateDefinition(String type, JsonNode content, boolean publishing) {
        if (!content.isObject()) throw ApiException.bad("Asset definition must be a JSON object");
        switch (type) {
            case DATA_MODEL -> validateModel(content);
            case FIELD_CONTRACT -> {
                if (text(content.path("sqlType").asText()).isBlank()) {
                    throw ApiException.bad("Field Contract requires sqlType");
                }
            }
            case GENERATION_RULE -> {
                String name = upper(content.path("generator").asText());
                if (!GENERATORS.contains(name)) throw ApiException.bad("Generation Rule requires a supported generator");
            }
            case DELIVERY_PROFILE -> {
                String receiver = upper(content.path("receiver").asText());
                if (!RECEIVERS.contains(receiver)) throw ApiException.bad("Delivery Profile receiver must be DB, CSV, JSON, or SQL");
                if ("DB".equals(receiver) && publishing && !content.hasNonNull("targetDataSourceId")
                        && !content.has("targetSystems")) {
                    throw ApiException.bad("Database Delivery Profiles require a target data source or target systems");
                }
            }
            case GENERATION_SCENARIO -> {
                if (!content.path("modelRef").isObject()) throw ApiException.bad("Generation Scenario requires modelRef");
                if (!content.path("deliveryRef").isObject()) throw ApiException.bad("Generation Scenario requires deliveryRef");
                if (publishing) compilePlan(content);
            }
            default -> throw ApiException.bad("Unsupported synthetic asset type");
        }
    }

    private void validateModel(JsonNode content) {
        List<JsonNode> tables = modelTables(content);
        if (tables.isEmpty()) throw ApiException.bad("Data Model requires at least one table");
        Set<String> tableNames = new LinkedHashSet<>();
        Map<String, Set<String>> fields = new LinkedHashMap<>();
        for (JsonNode table : tables) {
            String name = requiredText(table, "name", "Data Model table name");
            if (!tableNames.add(name.toLowerCase(Locale.ROOT))) {
                throw ApiException.bad("Duplicate Data Model table " + name);
            }
            List<JsonNode> columns = modelFields(table);
            if (columns.isEmpty()) throw ApiException.bad("Data Model table " + name + " requires fields");
            Set<String> names = new LinkedHashSet<>();
            for (JsonNode field : columns) {
                String fieldName = requiredText(field, "name", "Field name");
                if (!names.add(fieldName.toLowerCase(Locale.ROOT))) {
                    throw ApiException.bad("Duplicate field " + name + "." + fieldName);
                }
            }
            fields.put(name.toLowerCase(Locale.ROOT), names);
        }
        for (JsonNode table : tables) {
            String child = table.path("name").asText();
            for (JsonNode field : modelFields(table)) {
                JsonNode ref = field.path("references");
                String parent = firstText(field.path("fkTable").asText(null), ref.path("table").asText(null));
                String parentColumn = firstText(field.path("fkColumn").asText(null), ref.path("column").asText(null));
                if (parent != null && (!fields.containsKey(parent.toLowerCase(Locale.ROOT))
                        || parentColumn == null
                        || !fields.get(parent.toLowerCase(Locale.ROOT)).contains(parentColumn.toLowerCase(Locale.ROOT)))) {
                    throw ApiException.bad("Invalid relationship " + child + "." + field.path("name").asText()
                            + " -> " + parent + "." + parentColumn);
                }
            }
        }
    }

    private List<DependencyRef> pinReferences(JsonNode root, String ownerId) {
        List<DependencyRef> result = new ArrayList<>();
        pinReferences(root, "REFERENCE", ownerId, result);
        return result.stream().distinct().sorted(Comparator.comparing(DependencyRef::assetId)
                .thenComparing(DependencyRef::version).thenComparing(DependencyRef::kind)).toList();
    }

    private void pinReferences(JsonNode node, String kind, String ownerId, List<DependencyRef> result) {
        if (node == null) return;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            if (object.hasNonNull("assetId")) {
                String assetId = object.path("assetId").asText();
                if (ownerId.equals(assetId)) throw ApiException.bad("An asset cannot depend on itself");
                AssetRow target = require(assetId);
                int version = object.path("version").asInt(target.currentVersion());
                if (version < 1) throw ApiException.bad("Dependency " + target.name() + " has no published version");
                version(target, version);
                object.put("version", version);
                result.add(new DependencyRef(assetId, version, kind));
                return;
            }
            object.fields().forEachRemaining(entry ->
                    pinReferences(entry.getValue(), dependencyKind(entry.getKey()), ownerId, result));
        } else if (node.isArray()) {
            node.forEach(child -> pinReferences(child, kind, ownerId, result));
        }
    }

    private List<Map<String, Object>> componentManifest(JsonNode scenario) {
        List<Map<String, Object>> components = new ArrayList<>();
        collectManifest(scenario, "REFERENCE", components);
        return components.stream().distinct().toList();
    }

    private void collectManifest(JsonNode node, String kind, List<Map<String, Object>> result) {
        if (node == null) return;
        if (node.isObject()) {
            if (node.hasNonNull("assetId")) {
                AssetRow asset = require(node.path("assetId").asText());
                VersionRow version = version(asset, node.path("version").asInt());
                result.add(Map.of(
                        "assetId", asset.id(),
                        "assetType", asset.assetType(),
                        "name", asset.name(),
                        "version", version.version(),
                        "contentHash", version.hash(),
                        "dependencyKind", kind));
                return;
            }
            node.fields().forEachRemaining(entry ->
                    collectManifest(entry.getValue(), dependencyKind(entry.getKey()), result));
        } else if (node.isArray()) {
            node.forEach(child -> collectManifest(child, kind, result));
        }
    }

    private AssetDetail detail(AssetRow row) {
        List<DependencyView> dependencies = row.currentVersion() < 1 ? List.of() : jdbc.query("""
                SELECT a.id,a.asset_type,a.name,d.dependency_version,d.dependency_kind
                  FROM synthetic_asset_versions v
                  JOIN synthetic_asset_dependencies d ON d.owner_version_id=v.id
                  JOIN synthetic_assets a ON a.id=d.dependency_asset_id
                 WHERE v.asset_id=? AND v.version_no=?
                 ORDER BY d.dependency_kind,a.asset_type,LOWER(a.name)
                """, (rs, index) -> new DependencyView(
                rs.getString("id"), rs.getString("asset_type"), rs.getString("name"),
                rs.getInt("dependency_version"), rs.getString("dependency_kind")),
                row.id(), row.currentVersion());
        return new AssetDetail(summary(row), row.draft(), versions(row.id()), dependencies, impact(row.id()));
    }

    private AssetSummary summary(ResultSet rs, int index) throws SQLException {
        return new AssetSummary(
                rs.getString("id"), rs.getString("asset_type"), rs.getString("name"),
                rs.getString("description"), rs.getString("status"), rs.getInt("current_version"),
                rs.getLong("owner_user_id") == 0 && rs.wasNull() ? null : rs.getLong("owner_user_id"),
                rs.getString("owner_username"),
                rs.getLong("owner_group_id") == 0 && rs.wasNull() ? null : rs.getLong("owner_group_id"),
                rs.getString("visibility"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private AssetSummary summary(AssetRow row) {
        return new AssetSummary(row.id(), row.assetType(), row.name(), row.description(), row.status(),
                row.currentVersion(), row.ownerUserId(), row.ownerUsername(), row.ownerGroupId(),
                row.visibility(), row.createdAt(), row.updatedAt());
    }

    private AssetRow require(String id) {
        if (text(id).isBlank()) throw ApiException.bad("Asset id is required");
        List<AssetRow> rows = jdbc.query("SELECT * FROM synthetic_assets WHERE id=?", this::row, id);
        if (rows.isEmpty()) throw ApiException.notFound("Synthetic asset " + id + " not found");
        AssetRow row = rows.get(0);
        ownership.assertCanSee("synthetic asset", id, row.ownerUserId(), row.ownerGroupId(), row.visibility());
        return row;
    }

    private AssetRow row(ResultSet rs, int index) throws SQLException {
        Long ownerUser = nullableLong(rs, "owner_user_id");
        Long ownerGroup = nullableLong(rs, "owner_group_id");
        return new AssetRow(
                rs.getString("id"), rs.getString("asset_type"), rs.getString("name"),
                rs.getString("description"), rs.getString("status"), readTree(rs.getString("draft_json")),
                rs.getInt("current_version"), ownerUser, rs.getString("owner_username"), ownerGroup,
                rs.getString("visibility"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private VersionRow version(AssetRow asset, Integer requested) {
        int version = requested == null ? asset.currentVersion() : requested;
        if (version < 1) throw ApiException.bad(asset.name() + " has no published version");
        List<VersionRow> rows = jdbc.query("""
                SELECT * FROM synthetic_asset_versions WHERE asset_id=? AND version_no=?
                """, (rs, index) -> new VersionRow(
                rs.getString("id"), rs.getInt("version_no"), readTree(rs.getString("content_json")),
                rs.getString("content_hash"), rs.getString("published_by"),
                instant(rs.getTimestamp("published_at"))), asset.id(), version);
        if (rows.isEmpty()) throw ApiException.notFound(asset.name() + " version " + version + " not found");
        return rows.get(0);
    }

    private VersionRow referencedVersion(Reference ref, String expectedType) {
        AssetRow asset = require(ref.assetId());
        if (!expectedType.equals(asset.assetType())) {
            throw ApiException.bad(asset.name() + " is " + asset.assetType() + ", expected " + expectedType);
        }
        return version(asset, ref.version());
    }

    private void ensureNameAvailable(String type, String name, String excluding) {
        Integer count = excluding == null
                ? jdbc.queryForObject("""
                        SELECT COUNT(*) FROM synthetic_assets
                         WHERE asset_type=? AND LOWER(name)=LOWER(?)
                        """, Integer.class, type, name)
                : jdbc.queryForObject("""
                        SELECT COUNT(*) FROM synthetic_assets
                         WHERE asset_type=? AND LOWER(name)=LOWER(?) AND id<>?
                        """, Integer.class, type, name, excluding);
        if (count != null && count > 0) throw ApiException.conflict(typeLabel(type) + " name already exists");
    }

    private void assertManage(AssetRow row) {
        AccessContext.current().ifPresent(principal -> {
            if (principal.isAdmin() || Objects.equals(row.ownerUserId(), principal.userId())) return;
            if (row.ownerGroupId() != null && principal.groupIds().contains(row.ownerGroupId())) return;
            throw ApiException.forbidden("Only the owning user or group can change this synthetic asset");
        });
    }

    private void audit(String id, String name, String action, String detail) {
        audit.record(actor(), action, "SYNTHETIC", "synthetic-asset", id, name,
                "SUCCESS", detail, null);
    }

    private String actor() {
        return AccessContext.current().map(AccessPrincipal::username).orElse("system");
    }

    private Reference reference(JsonNode node, String label) {
        if (node == null || !node.isObject() || text(node.path("assetId").asText()).isBlank()) {
            throw ApiException.bad(label + " requires assetId");
        }
        Integer version = node.hasNonNull("version") ? node.path("version").asInt() : null;
        return new Reference(node.path("assetId").asText(), version);
    }

    private List<JsonNode> modelTables(JsonNode model) {
        JsonNode node = model.has("tables") ? model.path("tables") : model.path("records");
        return array(node);
    }

    private List<JsonNode> modelFields(JsonNode table) {
        JsonNode node = table.has("columns") ? table.path("columns") : table.path("fields");
        return array(node);
    }

    private static List<JsonNode> array(JsonNode node) {
        List<JsonNode> result = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(result::add);
        return result;
    }

    private static void mergeMissing(ObjectNode target, JsonNode source) {
        if (source == null || !source.isObject()) return;
        source.fields().forEachRemaining(entry -> {
            if (!target.has(entry.getKey()) || target.path(entry.getKey()).isNull()) {
                target.set(entry.getKey(), entry.getValue());
            }
        });
    }

    private static void mergeOverride(ObjectNode target, JsonNode source) {
        if (source == null || !source.isObject()) return;
        source.fields().forEachRemaining(entry -> target.set(entry.getKey(), entry.getValue()));
    }

    private static String dependencyKind(String key) {
        String clean = text(key).replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        return clean.endsWith("_REF") ? clean.substring(0, clean.length() - 4) : clean;
    }

    private static String key(String table, String field) {
        return table.toLowerCase(Locale.ROOT) + "." + field.toLowerCase(Locale.ROOT);
    }

    private static String requiredText(JsonNode node, String field, String label) {
        String value = text(node.path(field).asText());
        if (value.isBlank()) throw ApiException.bad(label + " is required");
        return value;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw ApiException.bad("Asset definition is not valid JSON");
        }
    }

    private JsonNode readTree(String value) {
        try {
            return json.readTree(first(value, "{}"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored synthetic asset JSON is invalid", e);
        }
    }

    private JsonNode normalizedDraft(JsonNode requested, JsonNode fallback) {
        JsonNode node = requested == null || requested.isNull() ? fallback : requested;
        if (!node.isObject()) throw ApiException.bad("Asset definition must be a JSON object");
        return node.deepCopy();
    }

    private static ObjectNode asObject(JsonNode node) {
        if (node == null || !node.isObject()) throw ApiException.bad("Asset definition must be a JSON object");
        return (ObjectNode) node;
    }

    private JsonNode starter(String type) {
        return types().stream().filter(item -> item.type().equals(type)).findFirst()
                .map(AssetType::starter).orElseGet(json::createObjectNode).deepCopy();
    }

    private JsonNode starterModel() {
        return readTree("""
                {"tables":[{"name":"customers","rowCount":1000,"columns":[
                  {"name":"customer_id","sqlType":"BIGINT","primaryKey":true,"generator":"SEQUENCE"},
                  {"name":"first_name","sqlType":"VARCHAR(80)","generator":"FIRST_NAME","param1":"US"},
                  {"name":"last_name","sqlType":"VARCHAR(80)","generator":"LAST_NAME","param1":"US"}
                ]}]}
                """);
    }

    private JsonNode starterContract() {
        return readTree("""
                {"semanticType":"CUSTOMER_IDENTIFIER","sqlType":"VARCHAR(40)",
                 "required":true,"unique":false,"description":"Reusable field requirement"}
                """);
    }

    private JsonNode starterRule() {
        return readTree("""
                {"generator":"FIRST_NAME","param1":"US","param2":"ANY",
                 "outputType":"VARCHAR","deterministic":true}
                """);
    }

    private JsonNode starterDelivery() {
        return readTree("""
                {"receiver":"CSV","createTable":false,"dropTable":false,
                 "loadAction":"INSERT","targetPrep":"NONE","continueOnError":false,
                 "fastLoad":false}
                """);
    }

    private JsonNode starterScenario() {
        return readTree("""
                {"dataset":"Reusable customer scenario","seed":42,
                 "modelRef":{"assetId":"","version":1},
                 "bindings":[],
                 "deliveryRef":{"assetId":"","version":1},
                 "execution":{"mode":"SINGLE"}}
                """);
    }

    private static SyntheticGenService.GenPlan withSeed(SyntheticGenService.GenPlan plan, long seed) {
        return new SyntheticGenService.GenPlan(
                plan.dataset(), plan.tables(), seed, plan.receiver(), plan.targetDataSourceId(),
                plan.targetSchema(), plan.createTable(), plan.dropTable(), plan.prepMode(),
                plan.loadAction(), plan.targetPrep(), plan.keyColumns(), plan.batchSize(),
                plan.commitEveryRows(), plan.continueOnError(), plan.maxRejects(), plan.fastLoad(),
                plan.executionMode(), plan.partitionCount(), plan.partitionSize(), plan.targetSystems());
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (!text(value.asText()).isBlank()) values.add(value.asText());
        });
        return values;
    }

    private static Set<String> flatten(JsonNode node) {
        Set<String> values = new LinkedHashSet<>();
        flatten(node, "$", values);
        return values;
    }

    private static void flatten(JsonNode node, String path, Set<String> values) {
        if (node == null) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> flatten(entry.getValue(), path + "." + entry.getKey(), values));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) flatten(node.get(i), path + "[" + i + "]", values);
        } else {
            values.add(path + "=" + node);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String requiredType(String value) {
        String type = upper(value);
        if (!TYPES.contains(type)) throw ApiException.bad("Unsupported synthetic asset type");
        return type;
    }

    private static String optionalType(String value) {
        return text(value).isBlank() ? null : requiredType(value);
    }

    private static String requiredStatus(String value) {
        String status = upper(value);
        if (!STATUSES.contains(status)) throw ApiException.bad("Unsupported synthetic asset status");
        return status;
    }

    private static String optionalStatus(String value) {
        return text(value).isBlank() ? null : requiredStatus(value);
    }

    private static String visibility(String value) {
        String visibility = upper(first(value, "GROUP"));
        if (!VISIBILITIES.contains(visibility)) throw ApiException.bad("Visibility must be PRIVATE, GROUP, or SHARED");
        return visibility;
    }

    private static String validName(String value) {
        String name = text(value);
        if (name.length() < 8 || name.length() > 120) {
            throw ApiException.bad("Asset name must be between 8 and 120 characters");
        }
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9 _.-]*")) {
            throw ApiException.bad("Asset name may contain letters, numbers, spaces, dots, dashes, and underscores");
        }
        return name;
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case DATA_MODEL -> "Data Model";
            case FIELD_CONTRACT -> "Field Contract";
            case GENERATION_RULE -> "Generation Rule";
            case DELIVERY_PROFILE -> "Delivery Profile";
            case GENERATION_SCENARIO -> "Generation Scenario";
            default -> "Synthetic asset";
        };
    }

    private static String receiverDescription(String receiver) {
        return switch (receiver) {
            case "DB" -> "Load through the governed database delivery engine.";
            case "CSV" -> "Create portable comma-separated output.";
            case "JSON" -> "Create structured JSON output.";
            case "SQL" -> "Create replayable SQL statements.";
            default -> "Synthetic delivery receiver.";
        };
    }

    private static String upper(String value) {
        return text(value).toUpperCase(Locale.ROOT);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String first(String value, String fallback) {
        return text(value).isBlank() ? fallback : value.trim();
    }

    private static String firstText(String value, String fallback) {
        return text(value).isBlank() ? fallback : value.trim();
    }

    private static String limited(String value, int max) {
        String clean = text(value);
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static String nullable(String value) {
        return text(value).isBlank() ? null : value.trim();
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() || text(node.asText()).isBlank() ? null : node.asText().trim();
    }

    private static long longValue(JsonNode node, long fallback) {
        return node == null || node.isNull() || !node.canConvertToLong() ? fallback : node.asLong();
    }

    private static Integer integerValue(JsonNode node) {
        return node == null || node.isNull() || !node.canConvertToInt() ? null : node.asInt();
    }

    private static Integer intObject(JsonNode node) {
        return integerValue(node);
    }

    private static Long longObject(JsonNode node) {
        return node == null || node.isNull() || !node.canConvertToLong() ? null : node.asLong();
    }

    private static boolean boolValue(JsonNode node, boolean fallback) {
        return node == null || node.isNull() ? fallback : node.asBoolean(fallback);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record AssetType(String type, String label, String description, JsonNode starter) {}
    public record AssetRequest(String assetType, String name, String description,
                               String visibility, JsonNode content) {}
    public record CloneRequest(String name, String description, Integer version) {}
    public record StatusRequest(String status) {}
    public record LaunchRequest(Integer version, Long seed) {}
    public record AssetSummary(String id, String assetType, String name, String description,
                               String status, int currentVersion, Long ownerUserId,
                               String ownerUsername, Long ownerGroupId, String visibility,
                               Instant createdAt, Instant updatedAt) {}
    public record AssetDetail(AssetSummary asset, JsonNode draft, List<VersionSummary> versions,
                              List<DependencyView> dependencies, List<ImpactItem> impact) {}
    public record VersionSummary(String id, int version, int schemaVersion, String contentHash,
                                 String compatibilityLevel, String publishedBy, Instant publishedAt,
                                 int dependencyCount) {}
    public record DependencyView(String assetId, String assetType, String name, int version, String kind) {}
    public record ImpactItem(String assetId, String assetType, String name, int ownerVersion,
                             int dependencyVersion, String kind) {}
    public record CompiledScenario(String manifestId, String scenarioAssetId, int scenarioVersion,
                                   String componentHash, String planHash,
                                   List<Map<String, Object>> components,
                                   SyntheticGenService.GenPlan plan, Instant compiledAt) {}

    private record AssetRow(String id, String assetType, String name, String description,
                            String status, JsonNode draft, int currentVersion, Long ownerUserId,
                            String ownerUsername, Long ownerGroupId, String visibility,
                            Instant createdAt, Instant updatedAt) {}
    private record VersionRow(String id, int version, JsonNode content, String hash,
                              String publishedBy, Instant publishedAt) {}
    private record Reference(String assetId, Integer version) {}
    private record DependencyRef(String assetId, int version, String kind) {}
}
