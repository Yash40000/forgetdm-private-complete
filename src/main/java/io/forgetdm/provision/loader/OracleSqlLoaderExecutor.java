package io.forgetdm.provision.loader;

import io.forgetdm.datasource.DataSourceEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Governed Oracle SQL*Loader execution with secure authentication, load profiles, and retained evidence. */
public class OracleSqlLoaderExecutor implements NativeLoadExecutor {
    static final String STRATEGY = "ORACLE_SQLLOADER_DIRECT_PATH";
    static final String ENABLED_ENV = "FORGETDM_ORACLE_SQLLOADER_ENABLED";
    static final String BINARY_ENV = "FORGETDM_ORACLE_SQLLOADER_BIN";
    static final String CONNECT_ENV = "FORGETDM_ORACLE_SQLLOADER_CONNECT";
    static final String AUTH_ENV = "FORGETDM_ORACLE_SQLLOADER_AUTH";
    static final String MINIMAL_REDO_ENV = "FORGETDM_ORACLE_MINIMAL_REDO_ALLOWED";
    static final String EVIDENCE_DIR_ENV = "FORGETDM_NATIVE_LOAD_EVIDENCE_DIR";

    private static final Pattern LOADED = Pattern.compile("(?im)^\\s*(\\d+)\\s+Rows? successfully loaded\\.");
    private static final Pattern REJECTED = Pattern.compile("(?im)^\\s*(\\d+)\\s+Rows? not loaded due to data errors\\.");

    enum Profile {
        AUTO,
        DIRECT_RECOVERABLE,
        DIRECT_MINIMAL_REDO,
        CONVENTIONAL_SAFE;

        static Profile from(String value) {
            if (value == null || value.isBlank()) return AUTO;
            try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
            catch (Exception ignored) { return AUTO; }
        }
    }

    @Override public String strategy() { return STRATEGY; }

    @Override
    public boolean supports(DataSourceEntity target) {
        return "ORACLE".equals(NativeLoadRegistry.engineOf(target));
    }

    @Override
    public NativeLoadStrategy describe(DataSourceEntity target) {
        String binary = System.getenv(BINARY_ENV);
        boolean enabled = NativeLoadSupport.truthy(System.getenv(ENABLED_ENV));
        boolean binaryReady = binary != null && !binary.isBlank() && Files.exists(Path.of(binary));
        boolean ready = enabled && binaryReady;
        return new NativeLoadStrategy(STRATEGY, "ORACLE", ready, getClass().getSimpleName(),
                "JDBC_BATCH", "EXTERNAL_NATIVE_CLIENT",
                ENABLED_ENV + "=true, " + BINARY_ENV + "=<sqlldr path>, and secure Oracle authentication",
                "Governed SQL*Loader profiles: direct recoverable, approved minimal-redo, and conventional safe."
                        + (ready ? "" : " Native client is not ready; AUTO uses JDBC_BATCH."));
    }

    @Override
    public NativeLoadResult execute(NativeLoadRequest request) {
        DataSourceEntity target = request == null ? null : request.target();
        NativeLoadStrategy description = describe(target);
        if (!description.nativeAvailable()) {
            return NativeLoadSupport.skipped(STRATEGY, target, "Oracle SQL*Loader is not configured", description);
        }
        validate(request);

        Path workDir = sqlLoaderWorkDir();
        Path controlFile = workDir.resolve("sqlldr.ctl");
        Path parameterFile = workDir.resolve("sqlldr.par");
        Path logFile = workDir.resolve("sqlldr.log");
        Path badFile = workDir.resolve("sqlldr.bad");
        Profile requested = Profile.from(option(request, "oracleLoadProfile"));
        Profile profile = requested == Profile.AUTO ? Profile.DIRECT_RECOVERABLE : requested;
        if (profile == Profile.DIRECT_MINIMAL_REDO && !NativeLoadSupport.truthy(System.getenv(MINIMAL_REDO_ENV))) {
            throw new NativeLoadException("Oracle minimal-redo loading requires " + MINIMAL_REDO_ENV
                    + "=true and an approved recovery plan.");
        }

        boolean direct = profile != Profile.CONVENTIONAL_SAFE;
        boolean recoverable = profile != Profile.DIRECT_MINIMAL_REDO;
        int errors = integerOption(request, "maxRejects", 0, 0, 100_000);
        int rows = integerOption(request, "batchSize", 10_000, 1, 1_000_000);
        String authMode = authMode();
        String connect = connection(target, authMode);
        String userId = userId(target, connect, authMode);

        Path ctl = NativeLoadSupport.write(workDir, controlFile.getFileName().toString(),
                controlText(request, profile, errors));
        Path par = NativeLoadSupport.write(workDir, parameterFile.getFileName().toString(),
                parameterText(ctl, logFile, badFile, direct, rows, errors));
        List<String> command = List.of(System.getenv(BINARY_ENV), "userid=" + userId,
                "parfile=\"" + par.toAbsolutePath() + "\"");
        List<String> redacted = NativeLoadSupport.redact(command, target);
        char[] secret = "PASSWORD_STDIN".equals(authMode) && target.getPassword() != null
                ? target.getPassword().toCharArray() : null;

        LinkedHashMap<String, Object> initial = new LinkedHashMap<>();
        initial.put("profile", profile.name());
        initial.put("requestedProfile", requested.name());
        initial.put("authMode", authMode);
        initial.put("directPath", direct);
        initial.put("recoverable", recoverable);
        initial.put("minimalRedo", !recoverable);
        initial.put("connect", maskedConnect(connect));
        initial.put("loaderRouteReason", "Oracle SQL*Loader profile " + profile.name());
        initial.put("rowsExpected", integerOption(request, "rowsExpected", rows, 0, Integer.MAX_VALUE));

        NativeLoadResult raw = NativeLoadSupport.run(STRATEGY, target, command, redacted, Map.of(),
                List.of(ctl, par), initial, secret);
        return withEvidence(request, raw, ctl, logFile, badFile);
    }

    static String controlText(NativeLoadRequest request, Profile profile, int errors) {
        String unrecoverable = profile == Profile.DIRECT_MINIMAL_REDO ? "UNRECOVERABLE\n" : "";
        String table = NativeLoadSupport.qualified(request.schema(), request.table());
        List<Integer> jdbcTypes = jdbcTypes(request);
        List<String> fields = new ArrayList<>();
        for (int i = 0; i < request.columns().size(); i++) {
            int type = i < jdbcTypes.size() ? jdbcTypes.get(i) : Types.VARCHAR;
            fields.add(fieldDefinition(request.columns().get(i), type));
        }
        return ("OPTIONS (ERRORS=" + errors + ")\n" + unrecoverable + "LOAD DATA\n"
                + "CHARACTERSET AL32UTF8\n"
                + "INFILE '" + NativeLoadSupport.csvPath(request.dataFile()) + "'\n"
                + "APPEND INTO TABLE " + table + "\n"
                + "FIELDS TERMINATED BY X'09' OPTIONALLY ENCLOSED BY '\"'\n"
                + "TRAILING NULLCOLS\n(" + String.join(",\n ", fields) + ")\n");
    }

    static String parameterText(Path control, Path log, Path bad, boolean direct, int rows, int errors) {
        return "control=\"" + control.toAbsolutePath() + "\"\n"
                + "log=\"" + log.toAbsolutePath() + "\"\n"
                + "bad=\"" + bad.toAbsolutePath() + "\"\n"
                + "direct=" + direct + "\n"
                + "rows=" + rows + "\n"
                + "errors=" + errors + "\n";
    }

    static String fieldDefinition(String column, int jdbcType) {
        String name = NativeLoadSupport.qIdent(column);
        return switch (jdbcType) {
            case Types.DATE -> name + " DATE \"YYYY-MM-DD\"";
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE ->
                    name + " TIMESTAMP \"YYYY-MM-DD HH24:MI:SS.FF9\"";
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> name + " INTEGER EXTERNAL";
            case Types.NUMERIC, Types.DECIMAL, Types.FLOAT, Types.REAL, Types.DOUBLE -> name + " DECIMAL EXTERNAL";
            case Types.BOOLEAN, Types.BIT -> name + " CHAR(8)";
            default -> name + " CHAR(32767)";
        };
    }

    private static Path sqlLoaderWorkDir() {
        try {
            // Oracle 11g SQL*Loader's Windows command-line parser splits parfile paths containing spaces
            // even when ProcessBuilder supplies one argument. Keep support files in a private temp path.
            Path dir = Files.createTempDirectory("forgetdm-sqlldr-");
            NativeLoadSupport.hardenPermissions(dir);
            return dir;
        } catch (Exception e) {
            throw new NativeLoadException("Could not create SQL*Loader support directory: " + e.getMessage(), e);
        }
    }

    private NativeLoadResult withEvidence(NativeLoadRequest request, NativeLoadResult raw, Path control,
                                          Path logFile, Path badFile) {
        String logText = read(logFile);
        long loaded = matchLong(LOADED, logText);
        long rejected = matchLong(REJECTED, logText);
        long badBytes = size(badFile);
        String badHash = NativeLoadSupport.sha256(badFile);
        String logHash = NativeLoadSupport.sha256(logFile);
        String controlHash = NativeLoadSupport.sha256(control);
        String firstError = firstError(logText, raw.stderr());

        LinkedHashMap<String, Object> details = new LinkedHashMap<>(raw.details());
        details.put("status", raw.status());
        details.put("exitCode", raw.exitCode());
        details.put("rowsLoaded", loaded);
        details.put("rowsRejected", rejected);
        details.put("badFileBytes", badBytes);
        details.put("badFileSha256", badHash);
        details.put("logSha256", logHash);
        details.put("controlSha256", controlHash);
        details.put("firstError", firstError);
        details.put("evidenceRetained", true);

        Path evidenceDir = evidenceDirectory(request, raw.startedAt());
        Path retainedLog = NativeLoadSupport.write(evidenceDir, "sqlldr.log",
                NativeLoadSupport.sanitizeOutput(logText, request.target()));
        String manifest = evidenceManifest(request, raw, details, retainedLog);
        Path manifestFile = NativeLoadSupport.write(evidenceDir, "manifest.properties", manifest);
        details.put("evidencePath", manifestFile.toAbsolutePath().toString());
        details.put("evidenceSha256", NativeLoadSupport.sha256(manifestFile));

        String message = raw.success() ? "Oracle SQL*Loader completed with retained evidence"
                : "Oracle SQL*Loader failed" + (firstError.isBlank() ? "" : ": " + firstError);
        return new NativeLoadResult(raw.strategy(), raw.engine(), raw.nativeUsed(), raw.success(), raw.exitCode(),
                raw.status(), message, raw.command(), raw.redactedCommand(), List.of(retainedLog, manifestFile),
                raw.stdout(), raw.stderr(), raw.startedAt(), raw.finishedAt(), Map.copyOf(details));
    }

    private static String evidenceManifest(NativeLoadRequest request, NativeLoadResult result,
                                           Map<String, Object> details, Path retainedLog) {
        return "strategy=" + STRATEGY + "\n"
                + "engine=ORACLE\n"
                + "jobId=" + safe(option(request, "jobId")) + "\n"
                + "chunkNo=" + safe(option(request, "chunkNo")) + "\n"
                + "table=" + safe(option(request, "logicalTable") == null ? request.table() : option(request, "logicalTable")) + "\n"
                + "loaderTable=" + safe(request.schema()) + "." + safe(request.table()) + "\n"
                + "stagedPublish=" + safe(option(request, "stagedPublish")) + "\n"
                + "status=" + result.status() + "\n"
                + "exitCode=" + result.exitCode() + "\n"
                + "startedAt=" + result.startedAt() + "\n"
                + "finishedAt=" + result.finishedAt() + "\n"
                + "profile=" + details.getOrDefault("profile", "") + "\n"
                + "authMode=" + details.getOrDefault("authMode", "") + "\n"
                + "recoverable=" + details.getOrDefault("recoverable", true) + "\n"
                + "rowsExpected=" + details.getOrDefault("rowsExpected", 0) + "\n"
                + "rowsLoaded=" + details.getOrDefault("rowsLoaded", 0) + "\n"
                + "rowsRejected=" + details.getOrDefault("rowsRejected", 0) + "\n"
                + "controlSha256=" + details.getOrDefault("controlSha256", "") + "\n"
                + "logSha256=" + details.getOrDefault("logSha256", "") + "\n"
                + "badFileSha256=" + details.getOrDefault("badFileSha256", "") + "\n"
                + "retainedLog=" + retainedLog.toAbsolutePath() + "\n";
    }

    private static Path evidenceDirectory(NativeLoadRequest request, Instant started) {
        String configured = System.getenv(EVIDENCE_DIR_ENV);
        Path root = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".forgetdm", "evidence", "native-loader")
                : Path.of(configured);
        String job = safe(option(request, "jobId"));
        String chunk = safe(option(request, "chunkNo"));
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(java.time.ZoneOffset.UTC).format(started);
        String name = (job.isBlank() ? "adhoc" : "job-" + job) + "-" + safe(request.table())
                + (chunk.isBlank() ? "" : "-chunk-" + chunk) + "-" + stamp;
        try {
            Path dir = root.resolve(name.replaceAll("[^A-Za-z0-9._-]", "_"));
            Files.createDirectories(dir);
            NativeLoadSupport.hardenPermissions(dir);
            return dir;
        } catch (Exception e) {
            throw new NativeLoadException("Could not create Oracle load evidence directory: " + e.getMessage(), e);
        }
    }

    private static void validate(NativeLoadRequest request) {
        if (request == null || request.target() == null || request.dataFile() == null || request.table() == null
                || request.table().isBlank() || request.columns() == null || request.columns().isEmpty()) {
            throw new NativeLoadException("Oracle SQL*Loader requires a target, table, data file, and columns.");
        }
        if (!Files.isRegularFile(request.dataFile())) {
            throw new NativeLoadException("Oracle SQL*Loader data file does not exist: " + request.dataFile());
        }
    }

    private static String connection(DataSourceEntity target, String authMode) {
        if ("OS_AUTH".equals(authMode)) return "";
        String configured = System.getenv(CONNECT_ENV);
        if ("WALLET".equals(authMode)) {
            if (configured == null || configured.isBlank()) {
                throw new NativeLoadException("Oracle wallet authentication requires " + CONNECT_ENV
                        + " to name a wallet-backed TNS alias.");
            }
            return configured.trim();
        }
        NativeLoadSupport.JdbcParts parts = NativeLoadSupport.parse(target);
        String parsed = parts.host().isBlank() ? "" : "//" + parts.host() + ":" + parts.port() + "/" + parts.database();
        String connection = NativeLoadSupport.firstNonBlank(configured, parsed);
        if (connection.isBlank()) throw new NativeLoadException("Oracle SQL*Loader connect alias is not configured.");
        return connection;
    }

    private static String authMode() {
        String value = NativeLoadSupport.firstNonBlank(System.getenv(AUTH_ENV), "PASSWORD_STDIN").toUpperCase(Locale.ROOT);
        if (!List.of("PASSWORD_STDIN", "WALLET", "OS_AUTH").contains(value)) {
            throw new NativeLoadException(AUTH_ENV + " must be PASSWORD_STDIN, WALLET, or OS_AUTH.");
        }
        return value;
    }

    private static String userId(DataSourceEntity target, String connect, String authMode) {
        return switch (authMode) {
            case "WALLET" -> "/@" + connect;
            case "OS_AUTH" -> "/";
            default -> {
                String username = target.getUsername();
                if (username == null || username.isBlank()) throw new NativeLoadException("Oracle username is required.");
                if (target.getPassword() == null || target.getPassword().isBlank()) {
                    throw new NativeLoadException("Oracle password is required for PASSWORD_STDIN authentication.");
                }
                yield username + "@" + connect;
            }
        };
    }

    private static List<Integer> jdbcTypes(NativeLoadRequest request) {
        String value = option(request, "jdbcTypes");
        if (value == null || value.isBlank()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (String part : value.split(",")) {
            try { out.add(Integer.parseInt(part.trim())); }
            catch (Exception ignored) { out.add(Types.VARCHAR); }
        }
        return out;
    }

    private static int integerOption(NativeLoadRequest request, String name, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(option(request, name));
            return Math.max(min, Math.min(max, value));
        } catch (Exception ignored) { return fallback; }
    }

    private static String option(NativeLoadRequest request, String name) {
        return request == null || request.options() == null ? null : request.options().get(name);
    }

    private static String read(Path path) {
        try { return path != null && Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : ""; }
        catch (Exception ignored) { return ""; }
    }

    private static long size(Path path) {
        try { return path != null && Files.exists(path) ? Files.size(path) : 0; }
        catch (Exception ignored) { return 0; }
    }

    private static long matchLong(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }

    private static String firstError(String log, String stderr) {
        String combined = (log == null ? "" : log) + "\n" + (stderr == null ? "" : stderr);
        return combined.lines().map(String::trim)
                .filter(line -> line.contains("ORA-") || line.contains("SQL*Loader-") || line.contains("SQL*Loader:"))
                .findFirst().map(line -> line.length() > 500 ? line.substring(0, 500) : line).orElse("");
    }

    private static String maskedConnect(String connect) {
        return connect == null ? "" : connect.replaceAll("(?i)(password|pwd)=([^;]+)", "$1=****");
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace("\r", " ").replace("\n", " ").replace("=", "_").trim();
    }
}
