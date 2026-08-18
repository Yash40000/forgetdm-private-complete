package io.forgetdm.mainframe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.copybook.Copybook;
import io.forgetdm.core.copybook.Field;
import io.forgetdm.core.copybook.RecordCodec;
import io.forgetdm.core.copybook.RecordValue;
import io.forgetdm.core.copybook.codec.Ebcdic;
import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.core.mask.MaskingSemantics;
import io.forgetdm.mainframe.transport.MainframeTransport;
import io.forgetdm.mainframe.transport.TransportFactory;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.OwnershipGuard;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Runs a mainframe file-masking job end to end, asynchronously:
 *   fetch each file from the source LPAR → split into records (FB/VB) → decode with the file's
 *   copybook → apply the copybook's field masks (deterministic engine) → re-encode in place →
 *   rejoin → write to the target LPAR (same or different) under the target name.
 */
@Service
public class MainframeMaskingService {

    private final MainframeJobRepository jobs;
    private final MainframeJobFileRepository jobFiles;
    private final MainframeConnectionRepository connections;
    private final CopybookDefRepository copybooks;
    private final CopybookMaskRepository masks;
    private final TransportFactory transports;
    private final MaskingEngine engine;
    private final ExecutorService executor;
    private final AuditService audit;
    private final OwnershipGuard ownership;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public MainframeMaskingService(MainframeJobRepository jobs, MainframeJobFileRepository jobFiles,
                                   MainframeConnectionRepository connections, CopybookDefRepository copybooks,
                                   CopybookMaskRepository masks, TransportFactory transports,
                                   MaskingEngine engine, ExecutorService provisioningExecutor, AuditService audit,
                                   OwnershipGuard ownership) {
        this.jobs = jobs; this.jobFiles = jobFiles; this.connections = connections;
        this.copybooks = copybooks; this.masks = masks; this.transports = transports;
        this.engine = engine; this.executor = provisioningExecutor; this.audit = audit; this.ownership = ownership;
    }

    public void submitAsync(Long jobId) {
        MainframeJobEntity job = visibleJob(jobId);
        audit.record(job.getCreatedBy(), "MAINFRAME_JOB_QUEUED", "MASKING",
                "MAINFRAME_JOB", String.valueOf(jobId), job.getName(), "SUCCESS",
                "Mainframe masking job queued", "{\"files\":" + job.getFilesTotal() + "}");
        executor.submit(() -> run(jobId));
    }

    public MainframeJobEntity cancel(Long jobId) {
        MainframeJobEntity job = visibleJob(jobId);
        if (terminal(job.getStatus())) return job;
        job.setCancelRequested(true);
        job.setMessage("Cancellation requested; the active record batch will stop at a safe boundary");
        if ("PENDING".equals(job.getStatus())) {
            job.setStatus("CANCELED");
            job.setFinishedAt(Instant.now());
        }
        MainframeJobEntity saved = jobs.save(job);
        audit.record(actor(), "MAINFRAME_JOB_CANCEL_REQUESTED", "CANCEL", "MAINFRAME_JOB", String.valueOf(jobId),
                job.getName(), "SUCCESS", "Mainframe masking cancellation requested", null);
        if ("CANCELED".equals(saved.getStatus())) auditTerminal(saved);
        return saved;
    }

    /** A retry is a new immutable attempt; files already delivered successfully are retained as completed evidence. */
    public MainframeJobEntity retry(Long jobId) {
        MainframeJobEntity previous = visibleJob(jobId);
        if (!java.util.Set.of("FAILED", "COMPLETED_WITH_ERRORS", "CANCELED").contains(previous.getStatus())) {
            throw ApiException.bad("Only failed, partially completed, or canceled mainframe jobs can be retried");
        }
        List<MainframeJobFileEntity> previousFiles = jobFiles.findByJobIdOrderByOrdinalAsc(previous.getId());
        validateReferences(previous, previousFiles);
        MainframeJobEntity next = new MainframeJobEntity();
        next.setName(previous.getName());
        next.setSourceConnectionId(previous.getSourceConnectionId());
        next.setTargetConnectionId(previous.getTargetConnectionId());
        next.setMaskingSeed(previous.getMaskingSeed());
        next.setPolicyId(previous.getPolicyId());
        next.setDatasetId(previous.getDatasetId());
        next.setBusinessEntityId(previous.getBusinessEntityId());
        next.setExecutionPlanId(previous.getExecutionPlanId());
        next.setRunGroupId(previous.getRunGroupId());
        next.setManifestJson(previous.getManifestJson());
        next.setCreatedBy(actor());
        stamp(next);
        next.setFilesTotal(previous.getFilesTotal());
        next.setStatus("PENDING");
        next.setMessage("Retry queued from job " + previous.getId());
        next = jobs.save(next);

        int completed = 0;
        long completedRecords = 0;
        for (MainframeJobFileEntity priorFile : previousFiles) {
            MainframeJobFileEntity file = copyFile(priorFile, next.getId());
            if ("COMPLETED".equals(priorFile.getStatus())) {
                file.setStatus("COMPLETED");
                file.setRecordCount(priorFile.getRecordCount());
                file.setMessage("Retained from successful attempt " + previous.getId());
                completed++;
                completedRecords += priorFile.getRecordCount();
            } else {
                file.setStatus("PENDING");
                file.setRecordCount(0);
                clearExecutionEvidence(file);
                file.setMessage(null);
            }
            jobFiles.save(file);
        }
        next.setFilesDone(completed);
        next.setRecordsProcessed(completedRecords);
        next = jobs.save(next);
        audit.record(actor(), "MAINFRAME_JOB_RETRIED", "MASKING", "MAINFRAME_JOB", String.valueOf(next.getId()),
                next.getName(), "SUCCESS", "Created retry attempt",
                "{\"previousJobId\":" + previous.getId() + ",\"retainedFiles\":" + completed + "}");
        submitAsync(next.getId());
        return next;
    }

    void run(Long jobId) {
        MainframeJobEntity job = jobs.findById(jobId).orElse(null);
        if (job == null) return;
        if (job.isCancelRequested() || "CANCELED".equals(job.getStatus())) {
            if (!"CANCELED".equals(job.getStatus())) {
                job.setStatus("CANCELED");
                job.setFinishedAt(Instant.now());
                jobs.save(job);
                auditTerminal(job);
            }
            return;
        }
        if (MainframeOwnership.isOrphanedNonShared(
                job.getOwnerUserId(), job.getOwnerGroupId(), job.getVisibility())) {
            job.setStatus("FAILED");
            job.setMessage("Mainframe job ownership is no longer valid");
            job.setFinishedAt(Instant.now());
            jobs.save(job);
            auditTerminal(job);
            return;
        }
        job.setStatus("RUNNING");
        job.setStartedAt(Instant.now());
        jobs.save(job);
        audit.record(job.getCreatedBy(), "MAINFRAME_JOB_STARTED", "MASKING", "MAINFRAME_JOB", String.valueOf(jobId),
                job.getName(), "SUCCESS", "Mainframe masking worker started", null);

        List<MainframeJobFileEntity> files = jobFiles.findByJobIdOrderByOrdinalAsc(jobId);
        long totalRecords = files.stream().filter(f -> "COMPLETED".equals(f.getStatus()))
                .mapToLong(MainframeJobFileEntity::getRecordCount).sum();
        int ok = (int) files.stream().filter(f -> "COMPLETED".equals(f.getStatus())).count();
        int failed = 0;
        for (MainframeJobFileEntity file : files) {
            if ("COMPLETED".equals(file.getStatus())) continue;
            if (cancelRequested(jobId)) { cancelRunningJob(jobId, totalRecords, ok + failed); return; }
            try {
                file.setStatus("RUNNING");
                file.setStartedAt(Instant.now());
                file.setFinishedAt(null);
                file.setMessage("Masking in progress");
                jobFiles.save(file);
                long n = processFile(job, file);
                file.setStatus("COMPLETED");
                file.setRecordCount(n);
                file.setFinishedAt(Instant.now());
                file.setMessage("Masked " + n + " records");
                totalRecords += n; ok++;
            } catch (JobCanceled e) {
                file.setStatus("CANCELED");
                file.setFinishedAt(Instant.now());
                file.setMessage("Canceled before target delivery");
                jobFiles.save(file);
                cancelRunningJob(jobId, totalRecords, ok + failed);
                return;
            } catch (Exception e) {
                file.setStatus("FAILED");
                file.setFinishedAt(Instant.now());
                file.setMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                failed++;
            }
            jobFiles.save(file);
            job.setFilesDone(ok + failed);
            job.setRecordsProcessed(totalRecords);
            jobs.save(job);
        }

        job.setStatus(failed == 0 ? "COMPLETED" : (ok == 0 ? "FAILED" : "COMPLETED_WITH_ERRORS"));
        job.setMessage(ok + " file(s) masked, " + failed + " failed, " + totalRecords + " records total");
        job.setFinishedAt(Instant.now());
        jobs.save(job);
        auditTerminal(job);
    }

    private long processFile(MainframeJobEntity job, MainframeJobFileEntity file) throws IOException {
        MainframeConnectionEntity src = conn(job.getSourceConnectionId(), "source");
        CopybookDefEntity def = workerCopybook(file.getCopybookId(), file.getSourceName());
        MainframeConnectionEntity tgt = conn(
                file.getTargetConnectionId() != null ? file.getTargetConnectionId() : job.getTargetConnectionId(),
                "target");
        MainframeTransport sourceTransport = transports.forConnection(src);
        MainframeTransport targetTransport = transports.forConnection(tgt);
        Copybook cb = CopybookSupport.parse(def.getSource());
        Field record = cb.primaryRecord();

        String codePage = firstNonBlank(file.getCodePage(), def.getCodePage(), src.getCodePage(), "Cp037");
        String recfm = file.getRecfm() == null ? "FB" : file.getRecfm();
        int lrecl = file.getLrecl() != null ? file.getLrecl()
                : RecordSplitter.isVariable(recfm) ? record.length() + 4 : record.length();

        Map<String, CopybookMaskEntity> maskMap = new HashMap<>();
        for (CopybookMaskEntity m : masks.findByCopybookId(def.getId()))
            maskMap.put(runtimePath(m.getFieldPath(), record.name()), m);
        MainframeMaskPlan governedPlan = readMaskPlan(file);
        if (job.getDatasetId() != null && (governedPlan == null || governedPlan.rules().isEmpty())) {
            throw ApiException.bad("DataScope file '" + file.getSourceName()
                    + "' has no immutable governed masking plan");
        }

        RecordCodec codec = new RecordCodec(record, new Ebcdic(codePage));
        MaskingEngine eng = (job.getMaskingSeed() == null || job.getMaskingSeed().isBlank())
                ? engine : engine.withSeed(job.getMaskingSeed());
        String targetName = (file.getTargetName() != null && !file.getTargetName().isBlank())
                ? file.getTargetName() : file.getSourceName();
        MainframeTransport.ResourceVersion targetVersion = targetTransport.version(tgt, targetName);
        Path staged = Files.createTempFile("forgetdm-mainframe-job-" + job.getId() + "-", ".stage");
        long rowIndex = 0;
        try {
            MessageDigest inputDigest = sha256();
            MessageDigest outputDigest = sha256();
            MainframeTransport.ResourceVersion sourceVersion;
            try (MainframeTransport.ReadHandle source = sourceTransport.openRead(src, file.getSourceName());
                 CountingInputStream countedInput = new CountingInputStream(source.stream());
                 DigestInputStream input = new DigestInputStream(countedInput, inputDigest);
                 DigestOutputStream output = new DigestOutputStream(
                         new BufferedOutputStream(Files.newOutputStream(staged)), outputDigest)) {
                sourceVersion = source.version();
                file.setSourceVersion(source.version().value());
                jobFiles.save(file);
                RecordStreamCodec.Reader reader = RecordStreamCodec.reader(input, recfm, lrecl);
                RecordStreamCodec.Writer writer = RecordStreamCodec.writer(output, recfm);
                byte[] sourceRecord;
                while ((sourceRecord = reader.next()) != null) {
                    rowIndex++;
                    if (rowIndex == 1 || rowIndex % 250 == 0) checkCanceled(job.getId());
                    writer.write(maskRecord(codec, eng, sourceRecord, rowIndex, governedPlan, maskMap,
                            file.getSourceName(), record.name()));
                    if (rowIndex % 1_000 == 0) {
                        file.setRecordsProcessed(rowIndex);
                        file.setCheckpointRecord(rowIndex);
                        file.setMessage("Masked " + rowIndex + " records into staged output");
                        jobFiles.save(file);
                    }
                }
                file.setInputBytes(countedInput.count());
            }
            sourceTransport.assertVersion(src, file.getSourceName(), sourceVersion);
            file.setOutputBytes(Files.size(staged));
            file.setInputSha256(HexFormat.of().formatHex(inputDigest.digest()));
            file.setOutputSha256(HexFormat.of().formatHex(outputDigest.digest()));
            file.setRecordsProcessed(rowIndex);
            file.setCheckpointRecord(rowIndex);
            checkCanceled(job.getId());

            // Recheck the target immediately before adapter-specific atomic publication.
            MainframeTransport.PublishReceipt receipt = targetTransport.publish(
                    tgt, targetName, staged, recfm, lrecl, targetVersion);
            file.setTargetVersion(receipt.version() == null ? null : receipt.version().value());
            file.setStagingName(receipt.stagingName());
        } finally {
            Files.deleteIfExists(staged);
        }

        audit.record(job.getCreatedBy(), "MAINFRAME_FILE_MASKED", "MASKING", "MAINFRAME_JOB_FILE",
                String.valueOf(file.getId()), file.getSourceName(), "SUCCESS",
                "records=" + rowIndex + " target=" + tgt.getName() + ":" + targetName,
                "{\"inputSha256\":\"" + safeJson(file.getInputSha256()) + "\",\"outputSha256\":\""
                        + safeJson(file.getOutputSha256()) + "\"}");
        return rowIndex;
    }

    private byte[] maskRecord(RecordCodec codec, MaskingEngine eng, byte[] record, long rowIndex,
                              MainframeMaskPlan governedPlan, Map<String, CopybookMaskEntity> maskMap,
                              String sourceName, String recordName) {
        RecordValue decoded = codec.decode(record);
        MaskContext ctx = new MaskContext(rowIndex);
        Map<String, RecordValue.DecodedField> decodedByPath = new HashMap<>();
        for (RecordValue.DecodedField field : decoded.fields()) {
            ctx.row.put(field.field().name().toLowerCase(Locale.ROOT), logicalValue(field));
            decodedByPath.put(normalizePath(field.path()), field);
        }
        Map<String, String> changes = new LinkedHashMap<>();
        if (governedPlan != null && !governedPlan.rules().isEmpty()) {
            configureDateShiftContext(ctx, governedPlan.rules());
            for (MainframeMaskPlan.Rule rule : governedPlan.rules()) {
                RecordValue.DecodedField field = decodedByPath.get(runtimePath(rule.fieldPath(), recordName));
                if (field == null) throw ApiException.bad("Copybook drift: mapped field '" + rule.fieldPath()
                        + "' is missing from " + sourceName);
                if (rule.sourceColumn() != null) {
                    ctx.row.put(rule.sourceColumn().toLowerCase(Locale.ROOT), logicalValue(field));
                }
            }
            for (MainframeMaskPlan.Rule rule : governedPlan.rules()) {
                RecordValue.DecodedField field = decodedByPath.get(runtimePath(rule.fieldPath(), recordName));
                String masked = eng.mask(MaskFunction.valueOf(rule.function().trim().toUpperCase(Locale.ROOT)),
                        rule.semanticSalt(), logicalValue(field), blankToNull(rule.param1()),
                        blankToNull(rule.param2()), ctx);
                if (masked == null) masked = field.numeric() ? "0" : "";
                rememberMasked(ctx, rule, field, masked);
                changes.put(field.path(), masked);
            }
        } else {
            for (RecordValue.DecodedField field : decoded.fields()) {
                CopybookMaskEntity mask = maskMap.get(normalizePath(field.path()));
                if (mask == null) continue;
                String masked = eng.mask(MaskFunction.valueOf(mask.getFunction().trim().toUpperCase(Locale.ROOT)),
                        field.field().name().toLowerCase(Locale.ROOT), logicalValue(field),
                        blankToNull(mask.getParam1()), blankToNull(mask.getParam2()), ctx);
                if (masked == null) masked = field.numeric() ? "0" : "";
                ctx.masked.put(field.field().name().toLowerCase(Locale.ROOT), masked);
                changes.put(field.path(), masked);
            }
        }
        return codec.encodeOverlay(decoded, record, changes);
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    private static String safeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long count;
        private CountingInputStream(InputStream input) { super(input); }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) count++;
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) count += read;
            return read;
        }
        long count() { return count; }
    }

    private MainframeMaskPlan readMaskPlan(MainframeJobFileEntity file) {
        if (file.getMaskPlanJson() == null || file.getMaskPlanJson().isBlank()) return null;
        try {
            MainframeMaskPlan plan = json.readValue(file.getMaskPlanJson(), MainframeMaskPlan.class);
            if (plan.assetId() != null && file.getAssetId() != null && !plan.assetId().equals(file.getAssetId())) {
                throw ApiException.bad("Mainframe mask-plan asset identity does not match the job manifest");
            }
            return plan;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Invalid immutable mask plan for file '" + file.getSourceName() + "': " + e.getMessage());
        }
    }

    private static void configureDateShiftContext(MaskContext ctx, List<MainframeMaskPlan.Rule> rules) {
        List<String> ranges = rules.stream()
                .filter(rule -> "DATE_SHIFT".equalsIgnoreCase(rule.function()))
                .map(MainframeMaskPlan.Rule::param1)
                .toList();
        int[] shared = MaskingEngine.intersectDateShiftRanges(ranges);
        if (shared != null) ctx.useSharedDateShiftRange(shared[0], shared[1]);
    }

    private static void rememberMasked(MaskContext ctx, MainframeMaskPlan.Rule rule,
                                       RecordValue.DecodedField field, String masked) {
        ctx.masked.put(field.field().name().toLowerCase(Locale.ROOT), masked);
        if (rule.sourceColumn() != null) ctx.masked.put(rule.sourceColumn().toLowerCase(Locale.ROOT), masked);
        String alias = MaskingSemantics.contextAlias(rule.function());
        if (alias != null) ctx.masked.put(alias, masked);
    }

    private static String normalizePath(String path) {
        return CopybookSupport.stripSubscripts(path).toUpperCase(Locale.ROOT);
    }

    private static String runtimePath(String path, String recordName) {
        String normalized = normalizePath(path);
        String prefix = recordName == null ? "" : recordName.trim().toUpperCase(Locale.ROOT) + ".";
        return !prefix.isEmpty() && normalized.startsWith(prefix)
                ? normalized.substring(prefix.length()) : normalized;
    }

    private static String logicalValue(RecordValue.DecodedField field) {
        return field.numeric() || field.value() == null ? field.value() : field.value().stripTrailing();
    }

    private void cancelRunningJob(Long jobId, long records, int filesDone) {
        MainframeJobEntity job = directJob(jobId);
        job.setCancelRequested(true);
        job.setStatus("CANCELED");
        job.setRecordsProcessed(records);
        job.setFilesDone(filesDone);
        job.setMessage("Canceled safely before the next target delivery");
        job.setFinishedAt(Instant.now());
        jobs.save(job);
        auditTerminal(job);
    }

    private boolean cancelRequested(Long jobId) {
        return jobs.findById(jobId).map(MainframeJobEntity::isCancelRequested).orElse(true)
                || Thread.currentThread().isInterrupted();
    }

    private void checkCanceled(Long jobId) {
        if (cancelRequested(jobId)) throw new JobCanceled();
    }

    private void auditTerminal(MainframeJobEntity job) {
        String status = job.getStatus() == null ? "FAILED" : job.getStatus().toUpperCase(Locale.ROOT);
        audit.record(job.getCreatedBy(), "MAINFRAME_JOB_" + status, "MASKING", "MAINFRAME_JOB",
                String.valueOf(job.getId()), job.getName(), "FAILED".equals(status) ? "FAILURE" : "SUCCESS",
                "files=" + job.getFilesDone() + "/" + job.getFilesTotal() + " records=" + job.getRecordsProcessed(),
                "{\"status\":\"" + status + "\"}");
    }

    private MainframeJobEntity visibleJob(Long id) {
        MainframeJobEntity job = directJob(id);
        MainframeOwnership.assertCanSee(ownership, "mainframe job", id, job.getOwnerUserId(),
                job.getOwnerGroupId(), job.getVisibility());
        return job;
    }

    private MainframeJobEntity directJob(Long id) {
        return jobs.findById(id).orElseThrow(() -> ApiException.notFound("Mainframe job " + id + " not found"));
    }

    private void validateReferences(MainframeJobEntity job, List<MainframeJobFileEntity> files) {
        visibleConnection(job.getSourceConnectionId(), "source");
        visibleConnection(job.getTargetConnectionId(), "target");
        for (MainframeJobFileEntity file : files) {
            visibleCopybook(file.getCopybookId(), file.getSourceName());
            if (file.getTargetConnectionId() != null) visibleConnection(file.getTargetConnectionId(), "target");
        }
    }

    private MainframeConnectionEntity visibleConnection(Long id, String role) {
        if (id == null) throw ApiException.bad("No " + role + " connection set for the job");
        MainframeConnectionEntity connection = connections.findById(id)
                .orElseThrow(() -> ApiException.bad(role + " connection " + id + " not found"));
        MainframeOwnership.assertCanSee(ownership, "mainframe connection", id, connection.getOwnerUserId(),
                connection.getOwnerGroupId(), connection.getVisibility());
        return connection;
    }

    private CopybookDefEntity visibleCopybook(Long id, String sourceName) {
        CopybookDefEntity copybook = copybooks.findById(id == null ? -1L : id)
                .orElseThrow(() -> ApiException.bad("File '" + sourceName + "' has no copybook assigned"));
        MainframeOwnership.assertCanSee(ownership, "mainframe copybook", copybook.getId(),
                copybook.getOwnerUserId(), copybook.getOwnerGroupId(), copybook.getVisibility());
        return copybook;
    }

    private void stamp(MainframeJobEntity job) {
        job.setOwnerUserId(ownership.defaultOwnerUserId());
        job.setOwnerUsername(ownership.defaultOwnerUsername());
        job.setOwnerGroupId(ownership.defaultOwnerGroupId());
        job.setVisibility(ownership.defaultVisibility());
    }

    private static MainframeJobFileEntity copyFile(MainframeJobFileEntity source, Long jobId) {
        MainframeJobFileEntity copy = new MainframeJobFileEntity();
        copy.setJobId(jobId);
        copy.setSourceName(source.getSourceName());
        copy.setCopybookId(source.getCopybookId());
        copy.setAssetId(source.getAssetId());
        copy.setRecfm(source.getRecfm());
        copy.setLrecl(source.getLrecl());
        copy.setCodePage(source.getCodePage());
        copy.setTargetConnectionId(source.getTargetConnectionId());
        copy.setTargetName(source.getTargetName());
        copy.setMaskPlanJson(source.getMaskPlanJson());
        copy.setMappingCount(source.getMappingCount());
        copy.setRecordsProcessed(source.getRecordsProcessed());
        copy.setCheckpointRecord(source.getCheckpointRecord());
        copy.setInputBytes(source.getInputBytes());
        copy.setOutputBytes(source.getOutputBytes());
        copy.setInputSha256(source.getInputSha256());
        copy.setOutputSha256(source.getOutputSha256());
        copy.setSourceVersion(source.getSourceVersion());
        copy.setTargetVersion(source.getTargetVersion());
        copy.setStagingName(source.getStagingName());
        copy.setStartedAt(source.getStartedAt());
        copy.setFinishedAt(source.getFinishedAt());
        copy.setOrdinal(source.getOrdinal());
        return copy;
    }

    private static void clearExecutionEvidence(MainframeJobFileEntity file) {
        file.setRecordsProcessed(0);
        file.setCheckpointRecord(0);
        file.setInputBytes(0);
        file.setOutputBytes(0);
        file.setInputSha256(null);
        file.setOutputSha256(null);
        file.setSourceVersion(null);
        file.setTargetVersion(null);
        file.setStagingName(null);
        file.setStartedAt(null);
        file.setFinishedAt(null);
    }

    private static boolean terminal(String status) {
        return java.util.Set.of("COMPLETED", "FAILED", "COMPLETED_WITH_ERRORS", "CANCELED").contains(status);
    }

    private static String actor() {
        return AccessContext.current().map(p -> p.username()).orElse("system");
    }

    private static final class JobCanceled extends RuntimeException { }

    private MainframeConnectionEntity conn(Long id, String role) {
        if (id == null) throw ApiException.bad("No " + role + " connection set for the job");
        MainframeConnectionEntity connection = connections.findById(id)
                .orElseThrow(() -> ApiException.bad(role + " connection " + id + " not found"));
        MainframeOwnership.assertOwnedOrShared("mainframe connection", id, connection.getOwnerUserId(),
                connection.getOwnerGroupId(), connection.getVisibility());
        return connection;
    }

    private CopybookDefEntity workerCopybook(Long id, String sourceName) {
        CopybookDefEntity copybook = copybooks.findById(id == null ? -1L : id)
                .orElseThrow(() -> ApiException.bad("File '" + sourceName + "' has no copybook assigned"));
        MainframeOwnership.assertOwnedOrShared("mainframe copybook", copybook.getId(),
                copybook.getOwnerUserId(), copybook.getOwnerGroupId(), copybook.getVisibility());
        return copybook;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "Cp037";
    }
}
