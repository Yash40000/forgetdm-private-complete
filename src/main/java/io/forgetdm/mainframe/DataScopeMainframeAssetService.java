package io.forgetdm.mainframe;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.dataset.DataSetDefinitionEntity;
import io.forgetdm.dataset.DataSetService;
import io.forgetdm.mainframe.transport.MainframeTransport;
import io.forgetdm.mainframe.transport.TransportFactory;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.OwnershipGuard;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** CRUD, authorization, and deterministic runtime resolution for DataScope mainframe file assets. */
@Service
public class DataScopeMainframeAssetService {
    private static final Set<String> RECFM = Set.of("F", "FB", "V", "VB");
    private static final Set<String> DSORG = Set.of("PS", "PDS_MEMBER", "GDG_GENERATION", "VSAM_EXPORT");
    private static final Set<String> SELECTION = Set.of("ALL", "ENTITY_KEYS", "FILTER");

    private final DataScopeMainframeAssetRepository assets;
    private final DataSetService datasets;
    private final MainframeConnectionRepository connections;
    private final CopybookDefRepository copybooks;
    private final TransportFactory transports;
    private final OwnershipGuard ownership;
    private final AuditService audit;

    public DataScopeMainframeAssetService(DataScopeMainframeAssetRepository assets,
                                          DataSetService datasets,
                                          MainframeConnectionRepository connections,
                                          CopybookDefRepository copybooks,
                                          TransportFactory transports,
                                          OwnershipGuard ownership,
                                          AuditService audit) {
        this.assets = assets;
        this.datasets = datasets;
        this.connections = connections;
        this.copybooks = copybooks;
        this.transports = transports;
        this.ownership = ownership;
        this.audit = audit;
    }

    public List<DataScopeMainframeAssetEntity> list(Long datasetId) {
        datasets.get(datasetId);
        List<DataScopeMainframeAssetEntity> rows = assets.findByDatasetIdOrderByOrdinalNoAscIdAsc(datasetId);
        rows.forEach(this::assertReferencesVisible);
        return rows;
    }

    public List<DataScopeMainframeAssetEntity> enabled(Long datasetId) {
        datasets.get(datasetId);
        List<DataScopeMainframeAssetEntity> rows = assets.findByDatasetIdAndEnabledTrueOrderByOrdinalNoAscIdAsc(datasetId);
        rows.forEach(this::assertReferencesVisible);
        return rows;
    }

    public DataScopeMainframeAssetEntity get(Long datasetId, Long assetId) {
        datasets.get(datasetId);
        DataScopeMainframeAssetEntity asset = assets.findById(assetId)
                .orElseThrow(() -> ApiException.notFound("Mainframe asset " + assetId + " not found"));
        if (!datasetId.equals(asset.getDatasetId())) {
            throw ApiException.notFound("Mainframe asset " + assetId + " not found in DataScope " + datasetId);
        }
        assertReferencesVisible(asset);
        return asset;
    }

    @Transactional
    public DataScopeMainframeAssetEntity create(Long datasetId, DataScopeMainframeAssetEntity request) {
        DataSetDefinitionEntity definition = datasets.get(datasetId);
        DataScopeMainframeAssetEntity clean = clean(request, new DataScopeMainframeAssetEntity(), datasetId, null);
        clean.setCreatedAt(Instant.now());
        DataScopeMainframeAssetEntity saved = assets.save(clean);
        promoteDefinitionKind(definition);
        audit("DATASCOPE_MAINFRAME_ASSET_CREATED", definition, saved);
        return saved;
    }

    @Transactional
    public DataScopeMainframeAssetEntity update(Long datasetId, Long assetId, DataScopeMainframeAssetEntity request) {
        DataSetDefinitionEntity definition = datasets.get(datasetId);
        DataScopeMainframeAssetEntity existing = get(datasetId, assetId);
        DataScopeMainframeAssetEntity saved = assets.save(clean(request, existing, datasetId, assetId));
        audit("DATASCOPE_MAINFRAME_ASSET_UPDATED", definition, saved);
        return saved;
    }

    @Transactional
    public void delete(Long datasetId, Long assetId) {
        DataSetDefinitionEntity definition = datasets.get(datasetId);
        DataScopeMainframeAssetEntity existing = get(datasetId, assetId);
        assets.delete(existing);
        audit("DATASCOPE_MAINFRAME_ASSET_DELETED", definition, existing);
    }

    /** Version-restore path: replace the complete file-asset set under one authorized DataScope. */
    @Transactional
    public List<DataScopeMainframeAssetEntity> replace(Long datasetId,
                                                        List<DataScopeMainframeAssetEntity> incoming) {
        DataSetDefinitionEntity definition = datasets.get(datasetId);
        assets.deleteByDatasetId(datasetId);
        assets.flush();
        List<DataScopeMainframeAssetEntity> saved = new ArrayList<>();
        int ordinal = 0;
        for (DataScopeMainframeAssetEntity source : incoming == null
                ? List.<DataScopeMainframeAssetEntity>of() : incoming) {
            source.setOrdinalNo(ordinal++);
            DataScopeMainframeAssetEntity clean = clean(source, new DataScopeMainframeAssetEntity(), datasetId, null);
            clean.setCreatedAt(Instant.now());
            saved.add(assets.save(clean));
        }
        if (!saved.isEmpty()) promoteDefinitionKind(definition);
        audit.record(AccessContext.current().map(p -> p.username()).orElse("system"),
                "DATASCOPE_MAINFRAME_ASSETS_REPLACED", "DATASCOPE", "dataset",
                String.valueOf(datasetId), definition.getName(), "SUCCESS",
                "Replaced DataScope mainframe file assets", "{\"assetCount\":" + saved.size() + "}");
        return saved;
    }

    /** Resolve a pattern to an immutable file manifest. No data bytes are fetched. */
    public List<ResolvedFile> resolve(Long datasetId, Long assetId) {
        DataScopeMainframeAssetEntity asset = get(datasetId, assetId);
        if ("VSAM_EXPORT".equals(asset.getDsorg())) {
            throw ApiException.bad("VSAM_EXPORT requires the IDCAMS unload/reload adapter and cannot run through the sequential-file transport");
        }
        if (!"ALL".equals(asset.getSelectionMode())) {
            throw ApiException.bad(asset.getSelectionMode() + " selection is modeled but not executable until the entity-key record selector is configured");
        }
        MainframeConnectionEntity source = connection(asset.getSourceConnectionId(), "source");
        List<MainframeTransport.RemoteFile> matches = transports.forConnection(source)
                .list(source, asset.getSourceNamePattern());
        if (matches.isEmpty()) {
            throw ApiException.bad("No source files matched '" + asset.getSourceNamePattern() + "'");
        }
        if (matches.size() > 1 && notBlank(asset.getTargetNameTemplate())
                && !asset.getTargetNameTemplate().contains("${source}")) {
            throw ApiException.bad("A multi-file pattern needs ${source} in its target name template to prevent collisions");
        }
        List<ResolvedFile> out = new ArrayList<>();
        for (MainframeTransport.RemoteFile remote : matches) {
            String remoteRecfm = upper(remote.recfm(), asset.getRecfm());
            Integer remoteLrecl = remote.lrecl() == null ? asset.getLrecl() : remote.lrecl();
            if (!RECFM.contains(remoteRecfm)) {
                throw ApiException.bad("Unsupported RECFM " + remoteRecfm + " for " + remote.name());
            }
            String target = expandTarget(asset.getTargetNameTemplate(), remote.name());
            out.add(new ResolvedFile(asset.getId(), asset.getLogicalRole(), remote.name(), target,
                    remoteRecfm, remoteLrecl, firstNonBlank(asset.getCodePage(), source.getCodePage(), "Cp037"),
                    remote.sizeBytes(), remote.dsorg(), asset.getCopybookId(), asset.getTargetConnectionId()));
        }
        return out;
    }

    private DataScopeMainframeAssetEntity clean(DataScopeMainframeAssetEntity source,
                                                 DataScopeMainframeAssetEntity target,
                                                 Long datasetId,
                                                 Long currentId) {
        if (source == null) throw ApiException.bad("Mainframe asset body is required");
        String role = required(source.getLogicalRole(), "logicalRole");
        assets.findByDatasetIdAndLogicalRoleIgnoreCase(datasetId, role).ifPresent(other -> {
            if (currentId == null || !other.getId().equals(currentId)) {
                throw ApiException.bad("A mainframe asset with logical role '" + role + "' already exists in this DataScope");
            }
        });
        target.setDatasetId(datasetId);
        target.setLogicalRole(role);
        target.setSourceConnectionId(requiredId(source.getSourceConnectionId(), "sourceConnectionId"));
        target.setTargetConnectionId(source.getTargetConnectionId());
        target.setSourceNamePattern(required(source.getSourceNamePattern(), "sourceNamePattern"));
        target.setTargetNameTemplate(blank(source.getTargetNameTemplate()));
        target.setCopybookId(requiredId(source.getCopybookId(), "copybookId"));
        target.setDsorg(allowed(source.getDsorg(), "PS", DSORG, "dsorg"));
        target.setRecfm(allowed(source.getRecfm(), "FB", RECFM, "recfm"));
        if (source.getLrecl() != null && source.getLrecl() <= 0) throw ApiException.bad("lrecl must be positive");
        target.setLrecl(source.getLrecl());
        target.setCodePage(blank(source.getCodePage()));
        target.setSelectionMode(allowed(source.getSelectionMode(), "ALL", SELECTION, "selectionMode"));
        target.setKeyFieldPaths(blank(source.getKeyFieldPaths()));
        target.setEntityKeyFieldPath(blank(source.getEntityKeyFieldPath()));
        target.setFilterExpression(blank(source.getFilterExpression()));
        target.setEnabled(source.isEnabled());
        target.setOrdinalNo(Math.max(0, source.getOrdinalNo()));
        target.setUpdatedAt(Instant.now());

        MainframeConnectionEntity sourceConnection = connection(target.getSourceConnectionId(), "source");
        if (target.getTargetConnectionId() != null) connection(target.getTargetConnectionId(), "target");
        CopybookDefEntity copybook = copybook(target.getCopybookId());
        validateCopybookPaths(copybook, target.getKeyFieldPaths(), "keyFieldPaths");
        validateCopybookPaths(copybook, target.getEntityKeyFieldPath(), "entityKeyFieldPath");
        if (target.getLrecl() == null) target.setLrecl(copybook.getRecordLength());
        if (target.getCodePage() == null) {
            target.setCodePage(firstNonBlank(copybook.getCodePage(), sourceConnection.getCodePage(), "Cp037"));
        }
        if (!"ALL".equals(target.getSelectionMode()) && target.getEntityKeyFieldPath() == null) {
            throw ApiException.bad("entityKeyFieldPath is required for " + target.getSelectionMode() + " selection");
        }
        if ("FILTER".equals(target.getSelectionMode()) && target.getFilterExpression() == null) {
            throw ApiException.bad("filterExpression is required for FILTER selection");
        }
        return target;
    }

    private void validateCopybookPaths(CopybookDefEntity copybook, String paths, String field) {
        if (paths == null) return;
        var parsed = CopybookSupport.parse(copybook.getSource());
        Set<String> known = CopybookSupport.structuralFields(
                        parsed, parsed.primaryRecord()).stream()
                .map(info -> CopybookSupport.stripSubscripts(info.path()).toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        for (String raw : paths.split(",")) {
            String path = blank(raw);
            if (path == null) continue;
            if (!known.contains(CopybookSupport.stripSubscripts(path).toUpperCase(Locale.ROOT))) {
                throw ApiException.bad(field + " contains unknown copybook field path '" + path + "'");
            }
        }
    }

    private void assertReferencesVisible(DataScopeMainframeAssetEntity asset) {
        connection(asset.getSourceConnectionId(), "source");
        if (asset.getTargetConnectionId() != null) connection(asset.getTargetConnectionId(), "target");
        copybook(asset.getCopybookId());
    }

    private MainframeConnectionEntity connection(Long id, String role) {
        if (id == null) throw ApiException.bad(role + " connection is required");
        MainframeConnectionEntity connection = connections.findById(id)
                .orElseThrow(() -> ApiException.notFound("Mainframe connection " + id + " not found"));
        MainframeOwnership.assertCanSee(ownership, "mainframe connection", id,
                connection.getOwnerUserId(), connection.getOwnerGroupId(), connection.getVisibility());
        return connection;
    }

    private CopybookDefEntity copybook(Long id) {
        CopybookDefEntity copybook = copybooks.findById(id)
                .orElseThrow(() -> ApiException.notFound("Copybook " + id + " not found"));
        MainframeOwnership.assertCanSee(ownership, "mainframe copybook", id,
                copybook.getOwnerUserId(), copybook.getOwnerGroupId(), copybook.getVisibility());
        return copybook;
    }

    private void promoteDefinitionKind(DataSetDefinitionEntity definition) {
        String next = definition.getDataSourceId() == null ? "MAINFRAME" : "HYBRID";
        if (!next.equals(definition.getScopeKind())) {
            datasets.updateScopeKind(definition.getId(), next);
        }
    }

    private void audit(String action, DataSetDefinitionEntity definition, DataScopeMainframeAssetEntity asset) {
        audit.record(AccessContext.current().map(p -> p.username()).orElse("system"), action, "DATASCOPE",
                "datascope-mainframe-asset", String.valueOf(asset.getId()), asset.getLogicalRole(), "SUCCESS",
                "DataScope mainframe file asset changed",
                "{\"datasetId\":" + definition.getId() + ",\"sourceConnectionId\":"
                        + asset.getSourceConnectionId() + ",\"copybookId\":" + asset.getCopybookId() + "}");
    }

    private static String expandTarget(String template, String source) {
        if (!notBlank(template)) return source;
        return template.replace("${source}", source);
    }

    private static String required(String value, String field) {
        String clean = blank(value);
        if (clean == null) throw ApiException.bad(field + " is required");
        return clean;
    }

    private static Long requiredId(Long value, String field) {
        if (value == null || value <= 0) throw ApiException.bad(field + " is required");
        return value;
    }

    private static String allowed(String value, String fallback, Set<String> allowed, String field) {
        String clean = upper(value, fallback);
        if (!allowed.contains(clean)) throw ApiException.bad(field + " must be one of " + allowed);
        return clean;
    }

    private static String upper(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (notBlank(value)) return value.trim();
        return null;
    }

    public record ResolvedFile(Long assetId, String logicalRole, String sourceName, String targetName,
                               String recfm, Integer lrecl, String codePage, Long sizeBytes, String dsorg,
                               Long copybookId, Long targetConnectionId) {}
}
