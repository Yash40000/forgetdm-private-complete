package io.forgetdm.provision.loader;

import io.forgetdm.datasource.DataSourceEntity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OracleSqlLoaderExecutorTest {
    @Test
    void recoverableControlIsTypedAndDoesNotEnableUnrecoverableLoading() {
        NativeLoadRequest request = request(Map.of("jdbcTypes",
                Types.NUMERIC + "," + Types.DATE + "," + Types.TIMESTAMP));

        String control = OracleSqlLoaderExecutor.controlText(
                request, OracleSqlLoaderExecutor.Profile.DIRECT_RECOVERABLE, 0);

        assertTrue(control.contains("OPTIONS (ERRORS=0)"));
        assertFalse(control.contains("UNRECOVERABLE"));
        assertTrue(control.contains("\"CUSTOMER_ID\" DECIMAL EXTERNAL"));
        assertTrue(control.contains("\"BUSINESS_DATE\" DATE \"YYYY-MM-DD\""));
        assertTrue(control.contains("\"UPDATED_AT\" TIMESTAMP \"YYYY-MM-DD HH24:MI:SS.FF9\""));
        assertTrue(control.contains("APPEND INTO TABLE \"BANK\".\"CUSTOMERS\""));
    }

    @Test
    void minimalRedoControlIsExplicitAndDirectOnly() {
        String control = OracleSqlLoaderExecutor.controlText(
                request(Map.of()), OracleSqlLoaderExecutor.Profile.DIRECT_MINIMAL_REDO, 0);

        assertTrue(control.startsWith("OPTIONS (ERRORS=0)\nUNRECOVERABLE\nLOAD DATA"));
    }

    @Test
    void parameterFileNeverContainsCredentials() {
        String parameters = OracleSqlLoaderExecutor.parameterText(
                Path.of("load.ctl"), Path.of("load.log"), Path.of("load.bad"), true, 50_000, 0);

        assertFalse(parameters.toLowerCase().contains("userid"));
        assertFalse(parameters.contains("secret-value"));
        assertTrue(parameters.contains("direct=true"));
        assertFalse(parameters.contains("direct_path_lock_wait"));
        assertTrue(parameters.contains("errors=0"));

        String conventional = OracleSqlLoaderExecutor.parameterText(
                Path.of("load.ctl"), Path.of("load.log"), Path.of("load.bad"), false, 10_000, 0);
        assertFalse(conventional.contains("direct_path_lock_wait"));
    }

    @Test
    void registryPublishesTheGovernedOracleExecutor() {
        NativeLoadStrategy strategy = new NativeLoadRegistry().strategyFor(source());

        assertEquals(OracleSqlLoaderExecutor.STRATEGY, strategy.strategy());
        assertEquals("OracleSqlLoaderExecutor", strategy.executor());
        assertEquals("JDBC_BATCH", strategy.fallback());
    }

    @Test
    void unknownProfileFailsBackToGovernedAutomatic() {
        assertEquals(OracleSqlLoaderExecutor.Profile.AUTO, OracleSqlLoaderExecutor.Profile.from("unknown"));
        assertEquals(OracleSqlLoaderExecutor.Profile.DIRECT_RECOVERABLE,
                OracleSqlLoaderExecutor.Profile.from("direct_recoverable"));
    }

    private NativeLoadRequest request(Map<String, String> options) {
        return new NativeLoadRequest(source(), "BANK", "CUSTOMERS",
                List.of("CUSTOMER_ID", "BUSINESS_DATE", "UPDATED_AT"),
                Path.of("target", "oracle-loader-test.tsv"), "\t", false, "INSERT", options);
    }

    private DataSourceEntity source() {
        DataSourceEntity ds = new DataSourceEntity();
        ds.setKind("ORACLE");
        ds.setJdbcUrl("jdbc:oracle:thin:@//localhost:1521/FREEPDB1");
        ds.setUsername("forgetdm");
        ds.setPassword("secret-value");
        ds.setRole("TARGET");
        return ds;
    }
}
