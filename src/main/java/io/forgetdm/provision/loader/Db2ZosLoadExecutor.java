package io.forgetdm.provision.loader;

import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.mainframe.MainframeConnectionEntity;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class Db2ZosLoadExecutor implements NativeLoadExecutor {
    public static final String STRATEGY = "DB2_ZOS_LOAD";
    private static final Pattern RETURN_CODE = Pattern.compile("(?:CC|RC)\\s*0*([0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOADED = Pattern.compile("(?:NUMBER OF (?:INPUT )?RECORDS (?:LOADED|PROCESSED)|ROWS? LOADED)\\s*[=:]\\s*([0-9,]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REJECTED = Pattern.compile("(?:NUMBER OF (?:INPUT )?RECORDS (?:DISCARDED|REJECTED)|ROWS? (?:DISCARDED|REJECTED))\\s*[=:]\\s*([0-9,]+)", Pattern.CASE_INSENSITIVE);

    private final Db2ZosLoadProfileService profiles;
    private final ZosmfJobClient zosmf;
    private final Db2ZosLoadJclBuilder jcl = new Db2ZosLoadJclBuilder();

    public Db2ZosLoadExecutor(Db2ZosLoadProfileService profiles, ZosmfJobClient zosmf) {
        this.profiles = profiles;
        this.zosmf = zosmf;
    }

    @Override public String strategy() { return STRATEGY; }

    @Override public boolean supports(DataSourceEntity target) {
        return "DB2ZOS".equals(NativeLoadRegistry.engineOf(target));
    }

    @Override public NativeLoadStrategy describe(DataSourceEntity target) {
        boolean configured = target != null && target.getId() != null && profiles.find(target.getId()).isPresent();
        return new NativeLoadStrategy(STRATEGY, "DB2ZOS", configured,
                configured ? "z/OSMF JES + DSNUTILB" : "JDBC_BATCH",
                "JDBC_BATCH", configured ? "ZOSMF_JES" : "JDBC",
                configured ? "Profile configured. Test z/OSMF readiness before production use."
                        : "Configure a Db2 z/OS loader profile and a ZOWE/z/OSMF connection.",
                "Streams UTF-8 delimited records to a VB data set, submits DSNUTILB LOAD, polls JES, and retains spool evidence.");
    }

    public Map<String, Object> status() {
        long count = profiles.configuredCount();
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("engine", "DB2ZOS");
        row.put("strategy", STRATEGY);
        row.put("label", "Db2 for z/OS LOAD through z/OSMF/JES");
        row.put("builtIn", true);
        row.put("nativeAvailable", count > 0);
        row.put("configuredProfiles", count);
        row.put("launchMode", "ZOSMF_JES");
        row.put("fallback", "JDBC_BATCH");
        row.put("status", count > 0 ? "CONFIGURED" : "SETUP_NEEDED");
        row.put("hint", count > 0 ? "Test each target profile against z/OSMF before loading."
                : "Assign a z/OSMF connection, Db2 subsystem, work HLQ, and DSNUPROC settings to a DB2ZOS data source.");
        row.put("security", "Vault-backed z/OSMF credential; no password in JCL or evidence");
        row.put("evidence", "Generated control statement, JCL, JES spool, return code, row metrics, and remote data-set names");
        return row;
    }

    @Override public NativeLoadResult execute(NativeLoadRequest request) {
        Instant started = Instant.now();
        List<Path> supportFiles = new ArrayList<>();
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        ZosmfJobClient.Job job = null;
        Db2ZosLoadJclBuilder.PreparedLoad prepared = null;
        MainframeConnectionEntity connection = null;
        String spoolText = "";
        try {
            Db2ZosLoadProfileService.ResolvedProfile resolved = profiles.resolve(request.target().getId());
            Db2ZosLoadProfileEntity profile = resolved.profile();
            connection = resolved.connection();
            Path workDir = NativeLoadSupport.supportDir(request);
            prepared = jcl.prepare(request, profile, workDir);
            supportFiles.addAll(prepared.supportFiles());

            details.put("profileId", profile.getId());
            details.put("connection", connection.getName());
            details.put("subsystem", profile.getSubsystem());
            details.put("loggingMode", profile.getLoggingMode());
            details.put("rowsExpected", prepared.rows());
            details.put("maxRecordBytes", prepared.maxRecordBytes());
            details.put("inputSha256", NativeLoadSupport.sha256(request.dataFile()));
            details.put("remoteDatasets", datasets(prepared.datasets()));

            long framedBytes = Files.size(request.dataFile()) + prepared.rows() * 4L;
            zosmf.allocateRecordDataset(connection, prepared.datasets().sysrec(), framedBytes, Db2ZosLoadJclBuilder.LRECL);
            zosmf.uploadUtf8Records(connection, prepared.datasets().sysrec(), request.dataFile(), Db2ZosLoadJclBuilder.MAX_RECORD_BYTES);
            job = zosmf.submit(connection, prepared.jcl());
            details.put("jobName", job.jobName());
            details.put("jobId", job.jobId());

            job = await(connection, job, profile);
            List<ZosmfJobClient.SpoolFile> spool = zosmf.spool(connection, job);
            StringBuilder combined = new StringBuilder();
            for (ZosmfJobClient.SpoolFile file : spool) {
                String safeName = file.ddName().replaceAll("[^A-Za-z0-9._-]", "_");
                Path evidence = NativeLoadSupport.write(workDir, "db2-zos-spool-" + file.id() + "-" + safeName + ".txt", file.content());
                supportFiles.add(evidence);
                combined.append("===== ").append(file.ddName()).append(" #").append(file.id()).append(" =====\n")
                        .append(file.content()).append('\n');
            }
            spoolText = combined.toString();
            int rc = parseReturnCode(job.returnCode(), spoolText);
            boolean accepted = rc >= 0 && rc <= profile.getMaxReturnCode() && !abnormal(job.returnCode(), spoolText);
            details.put("jobStatus", job.status());
            details.put("returnCode", job.returnCode());
            details.put("acceptedReturnCode", profile.getMaxReturnCode());
            details.put("rowsLoaded", metric(LOADED, spoolText));
            details.put("rowsRejected", metric(REJECTED, spoolText));
            details.put("remoteCleanup", accepted && profile.isCleanupRemote() ? "COMPLETED" : "RETAINED");
            if (accepted && profile.isCleanupRemote()) cleanup(connection, prepared.datasets());

            return result(request.target(), accepted, rc, accepted ? "COMPLETED" : "FAILED",
                    accepted ? "Db2 z/OS LOAD completed through JES" : "Db2 z/OS LOAD ended with " + safe(job.returnCode()),
                    connection, job, supportFiles, spoolText, accepted ? "" : firstError(spoolText), started, details);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted() && connection != null && job != null) {
                try { zosmf.cancel(connection, job); } catch (RuntimeException ignored) { }
                details.put("cancelRequested", true);
            }
            if (prepared != null) details.putIfAbsent("remoteDatasets", datasets(prepared.datasets()));
            details.put("remoteCleanup", "RETAINED_FOR_DIAGNOSIS");
            return result(request == null ? null : request.target(), false, -1, "FAILED",
                    NativeLoadSupport.sanitizeOutput(e.getMessage(), request == null ? null : request.target()),
                    connection, job, supportFiles, spoolText, e.toString(), started, details);
        }
    }

    private ZosmfJobClient.Job await(MainframeConnectionEntity connection, ZosmfJobClient.Job job,
                                     Db2ZosLoadProfileEntity profile) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(profile.getTimeoutSeconds());
        ZosmfJobClient.Job current = job;
        while (Instant.now().isBefore(deadline)) {
            current = zosmf.status(connection, current);
            if (current.returnCode() != null && !current.returnCode().isBlank()) return current;
            if ("OUTPUT".equalsIgnoreCase(current.status()) || "NOT_FOUND".equalsIgnoreCase(current.status())) return current;
            Thread.sleep(Duration.ofSeconds(profile.getPollSeconds()).toMillis());
        }
        try { zosmf.cancel(connection, current); } catch (RuntimeException ignored) { }
        throw new NativeLoadException("Db2 z/OS LOAD timed out after " + profile.getTimeoutSeconds() + " seconds");
    }

    private NativeLoadResult result(DataSourceEntity target, boolean success, int rc, String status, String message,
                                    MainframeConnectionEntity connection, ZosmfJobClient.Job job, List<Path> files,
                                    String stdout, String stderr, Instant started, Map<String, Object> details) {
        List<String> command = connection == null ? List.of("z/OSMF", "DSNUTILB")
                : List.of("z/OSMF", connection.getName(), job == null ? "pending" : safe(job.jobName()));
        return new NativeLoadResult(STRATEGY, "DB2ZOS", true, success, rc, status, message,
                command, command, List.copyOf(files), stdout, stderr, started, Instant.now(), Map.copyOf(details));
    }

    private void cleanup(MainframeConnectionEntity connection, Db2ZosLoadJclBuilder.Datasets ds) {
        zosmf.deleteDatasetQuietly(connection, ds.sysrec());
        zosmf.deleteDatasetQuietly(connection, ds.sysdisc());
        zosmf.deleteDatasetQuietly(connection, ds.syserr());
        zosmf.deleteDatasetQuietly(connection, ds.sysmap());
    }

    private Map<String, String> datasets(Db2ZosLoadJclBuilder.Datasets ds) {
        return Map.of("sysrec", ds.sysrec(), "sysdisc", ds.sysdisc(), "syserr", ds.syserr(), "sysmap", ds.sysmap());
    }

    private int parseReturnCode(String retcode, String spool) {
        Matcher direct = RETURN_CODE.matcher(safe(retcode));
        if (direct.find()) return Integer.parseInt(direct.group(1));
        Matcher evidence = RETURN_CODE.matcher(safe(spool));
        int last = -1;
        while (evidence.find()) last = Math.max(last, Integer.parseInt(evidence.group(1)));
        return last;
    }

    private long metric(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(safe(text));
        long value = -1;
        while (matcher.find()) value = Math.max(value, Long.parseLong(matcher.group(1).replace(",", "")));
        return value;
    }

    private boolean abnormal(String retcode, String spool) {
        String value = (safe(retcode) + " " + safe(spool)).toUpperCase(Locale.ROOT);
        return value.contains("ABEND") || value.contains("JCL ERROR") || value.contains("SEC ERROR");
    }

    private String firstError(String spool) {
        for (String line : safe(spool).split("\\R")) {
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.contains("DSNU") && (upper.contains("ERROR") || upper.matches(".*DSNU[0-9]+[AE].*"))) return line.trim();
        }
        return "Review JES spool evidence for the failing utility message";
    }

    private String safe(String value) { return value == null ? "" : value; }
}
