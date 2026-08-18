package io.forgetdm.provision.loader;

import io.forgetdm.datasource.DataSourceEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Db2ZosLoadJclBuilderTest {
    @TempDir Path temp;

    @Test
    void buildsRecoverableAppendJclAndMeasuresCrLfRecords() throws Exception {
        Path input = temp.resolve("rows.tsv");
        Files.writeString(input, "1\tAlice\r\n2\tBjörk\r\n");

        Db2ZosLoadJclBuilder.PreparedLoad load = new Db2ZosLoadJclBuilder().prepare(
                request(input, "INSERT"), profile("RECOVERABLE"), temp);

        assertEquals(2, load.rows());
        assertTrue(load.control().contains("LOG YES\n  RESUME YES"));
        assertTrue(load.control().contains("FORMAT DELIMITED COLDEL X'09'"));
        assertTrue(load.control().contains("UNICODE CCSID(00367,01208,01200)"));
        assertTrue(load.control().contains("INTO TABLE \"BANK\".\"CUSTOMER\""));
        assertFalse(load.control().contains("POSITION"));
        assertTrue(load.jcl().contains("EXEC DSNUPROC,SYSTEM=DB2P"));
        assertTrue(load.jcl().contains("//SYSREC DD DSN=" + load.datasets().sysrec()));
        assertTrue(load.jcl().contains("//         UNIT=SYSDA"));
        assertTrue(load.supportFiles().stream().allMatch(Files::exists));
    }

    @Test
    void minimalLoggingReplaceIsExplicitAndNeverSilent() throws Exception {
        Path input = temp.resolve("rows.tsv");
        Files.writeString(input, "1\tAlice\n");

        Db2ZosLoadJclBuilder.PreparedLoad load = new Db2ZosLoadJclBuilder().prepare(
                request(input, "REPLACE"), profile("MINIMAL_LOGGING"), temp);

        assertTrue(load.control().contains("LOG NO NOCOPYPEND\n  REPLACE REUSE"));
    }

    @Test
    void rejectsSqlOnlyLoadActionsBeforeAnyHostCall() throws Exception {
        Path input = temp.resolve("rows.tsv");
        Files.writeString(input, "1\tAlice\n");

        NativeLoadException error = assertThrows(NativeLoadException.class, () ->
                new Db2ZosLoadJclBuilder().prepare(request(input, "UPSERT"), profile("RECOVERABLE"), temp));

        assertTrue(error.getMessage().contains("requires JDBC/SQL"));
    }

    @Test
    void rejectsRowsThatCannotFitARecordDataSet() throws Exception {
        Path input = temp.resolve("oversize.tsv");
        Files.writeString(input, "X".repeat(Db2ZosLoadJclBuilder.MAX_RECORD_BYTES + 1) + "\n");

        assertThrows(NativeLoadException.class, () ->
                new Db2ZosLoadJclBuilder().prepare(request(input, "INSERT"), profile("RECOVERABLE"), temp));
    }

    private NativeLoadRequest request(Path input, String action) {
        DataSourceEntity target = new DataSourceEntity();
        target.setId(91L);
        target.setKind("DB2ZOS");
        return new NativeLoadRequest(target, "BANK", "CUSTOMER", List.of("ID", "NAME"), input,
                "\t", false, action, Map.of());
    }

    private Db2ZosLoadProfileEntity profile(String loggingMode) {
        Db2ZosLoadProfileEntity profile = new Db2ZosLoadProfileEntity();
        profile.setSubsystem("DB2P");
        profile.setWorkHlq("FTDM.LOAD");
        profile.setProcedureName("DSNUPROC");
        profile.setJobClass("A");
        profile.setMessageClass("X");
        profile.setJobAccounting("(BANK01)");
        profile.setWorkUnit("SYSDA");
        profile.setLoggingMode(loggingMode);
        return profile;
    }
}
