package io.forgetdm.provision.loader;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class Db2ZosLoadJclBuilder {
    static final int LRECL = 32756;
    static final int MAX_RECORD_BYTES = LRECL - 4;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#@]{0,127}");

    PreparedLoad prepare(NativeLoadRequest request, Db2ZosLoadProfileEntity profile, Path workDir) {
        validate(request);
        Scan scan = scan(request.dataFile());
        String token = Long.toString(Instant.now().toEpochMilli(), 36).toUpperCase(Locale.ROOT);
        token = token.substring(Math.max(0, token.length() - 7));
        String base = profile.getWorkHlq() + ".FDM" + token;
        if (base.length() > 35) throw new NativeLoadException("Work HLQ leaves insufficient room for generated LOAD data sets");
        Datasets datasets = new Datasets(base + ".SYSREC", base + ".SYSDISC", base + ".SYSERR", base + ".SYSMAP");
        String jobName = ("FDM" + token).substring(0, Math.min(8, 3 + token.length()));
        String loadMode = loadMode(request.loadAction());
        String logging = "MINIMAL_LOGGING".equalsIgnoreCase(profile.getLoggingMode())
                ? "LOG NO NOCOPYPEND"
                : "LOG YES";
        String control = control(request, loadMode, logging);
        String jcl = jcl(profile, datasets, jobName, control);
        Path controlFile = NativeLoadSupport.write(workDir, "db2-zos-load-control.txt", control);
        Path jclFile = NativeLoadSupport.write(workDir, "db2-zos-load.jcl", jcl);
        return new PreparedLoad(jobName, datasets, control, jcl, scan.rows(), scan.maxRecordBytes(),
                List.of(controlFile, jclFile));
    }

    private void validate(NativeLoadRequest request) {
        if (request == null || request.target() == null || request.target().getId() == null) {
            throw new NativeLoadException("Db2 z/OS LOAD requires a saved target data source");
        }
        if (request.dataFile() == null || !Files.isRegularFile(request.dataFile())) {
            throw new NativeLoadException("Db2 z/OS LOAD input file is missing");
        }
        identifier(request.schema(), "schema");
        identifier(request.table(), "table");
        if (request.columns() == null || request.columns().isEmpty()) throw new NativeLoadException("At least one target column is required");
        request.columns().forEach(column -> identifier(column, "column"));
        if (request.header()) throw new NativeLoadException("Db2 z/OS native LOAD input must not contain a header row");
        String delimiter = request.delimiter();
        if (delimiter != null && !delimiter.isEmpty() && !"\t".equals(delimiter)) {
            throw new NativeLoadException("Db2 z/OS native LOAD currently accepts the platform's tab-delimited staging format");
        }
    }

    private String control(NativeLoadRequest request, String loadMode, String logging) {
        List<String> columns = new ArrayList<>();
        for (String column : request.columns()) columns.add("    " + q(column));
        return "LOAD DATA INDDN SYSREC\n"
                + "  " + logging + "\n"
                + "  " + loadMode + "\n"
                + "  FORMAT DELIMITED COLDEL X'09' CHARDEL X'22' DECPT X'2E'\n"
                + "  UNICODE CCSID(00367,01208,01200)\n"
                + "  ENFORCE CONSTRAINTS\n"
                + "  INTO TABLE " + q(request.schema()) + "." + q(request.table()) + "\n"
                + "  (\n" + String.join(",\n", columns) + "\n  )\n";
    }

    private String jcl(Db2ZosLoadProfileEntity profile, Datasets ds, String jobName, String control) {
        String accounting = profile.getJobAccounting() == null || profile.getJobAccounting().isBlank()
                ? ""
                : profile.getJobAccounting();
        return "//" + jobName + " JOB " + accounting + ",'DB2 ZOS LOAD',CLASS=" + profile.getJobClass()
                + ",MSGCLASS=" + profile.getMessageClass() + ",NOTIFY=&SYSUID\n"
                + "//LOAD EXEC " + profile.getProcedureName() + ",SYSTEM=" + profile.getSubsystem()
                + ",UID='" + jobName + "',UTPROC=''\n"
                + "//SYSREC DD DSN=" + ds.sysrec() + ",DISP=SHR\n"
                + workDd("SYSDISC", ds.sysdisc(), profile.getWorkUnit())
                + workDd("SYSERR", ds.syserr(), profile.getWorkUnit())
                + workDd("SYSMAP", ds.sysmap(), profile.getWorkUnit())
                + "//UTPRINT DD SYSOUT=*\n"
                + "//SYSIN DD *\n" + control + "/*\n";
    }

    private String workDd(String dd, String dataset, String unit) {
        return "//" + dd + " DD DSN=" + dataset + ",DISP=(NEW,CATLG,DELETE),\n"
                + "//         UNIT=" + unit + ",SPACE=(TRK,(10,10),RLSE),\n"
                + "//         DCB=(RECFM=VB,LRECL=" + LRECL + ",BLKSIZE=0)\n";
    }

    private Scan scan(Path path) {
        long rows = 0;
        int current = 0;
        int max = 0;
        int previous = -1;
        boolean any = false;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path), 256 * 1024)) {
            int value;
            while ((value = in.read()) >= 0) {
                any = true;
                if (value == '\n') {
                    int record = current > 0 && previous == '\r' ? current - 1 : current;
                    max = Math.max(max, record);
                    if (record > MAX_RECORD_BYTES) throw new NativeLoadException("A staged row exceeds the Db2 z/OS " + MAX_RECORD_BYTES + " byte limit");
                    rows++;
                    current = 0;
                    previous = -1;
                } else {
                    current++;
                    if (current > MAX_RECORD_BYTES + 1) throw new NativeLoadException("A staged row exceeds the Db2 z/OS " + MAX_RECORD_BYTES + " byte limit");
                    previous = value;
                }
            }
            if (current > 0) { rows++; max = Math.max(max, current); }
        } catch (IOException e) {
            throw new NativeLoadException("Could not validate Db2 z/OS LOAD input: " + e.getMessage(), e);
        }
        if (!any || rows == 0) throw new NativeLoadException("Db2 z/OS LOAD input is empty");
        return new Scan(rows, max);
    }

    private String loadMode(String value) {
        String action = value == null ? "INSERT" : value.trim().toUpperCase(Locale.ROOT);
        return switch (action) {
            case "INSERT", "APPEND" -> "RESUME YES";
            case "REPLACE", "TRUNCATE", "TRUNCATE_INSERT", "TRUNCATE_BEFORE_LOAD" -> "REPLACE REUSE";
            default -> throw new NativeLoadException("Db2 z/OS native LOAD supports append or replace actions; " + action + " requires JDBC/SQL processing");
        };
    }

    private String identifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) throw new NativeLoadException("Invalid Db2 z/OS " + label + " identifier: " + value);
        return value;
    }

    private String q(String value) { return "\"" + identifier(value, "identifier").replace("\"", "\"\"") + "\""; }

    record Datasets(String sysrec, String sysdisc, String syserr, String sysmap) { }
    record Scan(long rows, int maxRecordBytes) { }
    record PreparedLoad(String jobName, Datasets datasets, String control, String jcl, long rows,
                        int maxRecordBytes, List<Path> supportFiles) { }
}
