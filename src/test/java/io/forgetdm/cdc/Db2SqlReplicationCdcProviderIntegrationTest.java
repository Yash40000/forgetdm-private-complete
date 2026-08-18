package io.forgetdm.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.datasource.DataSourceEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "FORGETDM_DB2_CDC_URL", matches = ".+")
class Db2SqlReplicationCdcProviderIntegrationTest {

    private static final String SCHEMA = "FTDM_VIRT_DEMO";
    private static final String TABLE = "CUSTOMER";

    @Test
    void capturesCommittedInsertUpdateAndDeleteFromNativeDb2Replication() throws Exception {
        DataSourceEntity source = source();
        Db2SqlReplicationCdcProvider provider =
                new Db2SqlReplicationCdcProvider("ASN", new ObjectMapper());

        assertThat(provider.preflight(source).ok()).isTrue();
        provider.validateScope(source, SCHEMA, List.of(TABLE));

        String start = provider.currentLogPosition(source);
        assertThat(Db2SqlReplicationCdcProvider.isCommitSequence(start)).isTrue();

        CdcCaptureEntity capture = new CdcCaptureEntity();
        capture.setDataSourceId(48L);
        capture.setStatus("ACTIVE");
        capture.setSlotName("asn:integration-proof");
        capture.setSchemaName(SCHEMA);
        capture.setTablesJson("[\"CUSTOMER\"]");
        capture.setConfirmedLsn(start);

        long id = 9_100_000L + Math.floorMod(System.nanoTime(), 700_000L);
        try {
            insertCustomer(source, id);
            updateCustomer(source, id);
            deleteCustomer(source, id);

            List<CdcProvider.DecodedChange> captured = drain(provider, source, capture, id);
            assertThat(captured).extracting(change -> change.op)
                    .contains("I", "U", "D");
            assertThat(captured).allSatisfy(change -> {
                assertThat(change.schema).isEqualTo(SCHEMA);
                assertThat(change.table).isEqualTo(TABLE);
                assertThat(change.pk).containsEntry("CUSTOMER_ID", String.valueOf(id));
                assertThat(Db2SqlReplicationCdcProvider.isCommitSequence(change.lsn)).isTrue();
            });
        } finally {
            deleteCustomer(source, id);
        }
    }

    private static List<CdcProvider.DecodedChange> drain(
            Db2SqlReplicationCdcProvider provider, DataSourceEntity source,
            CdcCaptureEntity capture, long id) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(45));
        List<CdcProvider.DecodedChange> matching = new ArrayList<>();
        while (Instant.now().isBefore(deadline)) {
            CdcProvider.PollResult result = provider.poll(source, capture, 500, 5_000);
            capture.setConfirmedLsn(result.confirmedLsn());
            result.changes().stream()
                    .filter(change -> String.valueOf(id).equals(change.values.get("CUSTOMER_ID")))
                    .forEach(matching::add);
            if (matching.stream().map(change -> change.op).distinct().count() >= 3) return matching;
            Thread.sleep(750);
        }
        return matching;
    }

    private static void insertCustomer(DataSourceEntity source, long id) throws Exception {
        execute(source, "INSERT INTO FTDM_VIRT_DEMO.CUSTOMER "
                        + "(CUSTOMER_ID,CUSTOMER_NO,FULL_NAME,EMAIL_ADDRESS,CUSTOMER_STATUS,CREATED_AT) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE', CURRENT TIMESTAMP)",
                id, "CDC-" + id, "CDC Integration " + id, "cdc-" + id + "@example.test");
    }

    private static void updateCustomer(DataSourceEntity source, long id) throws Exception {
        execute(source, "UPDATE FTDM_VIRT_DEMO.CUSTOMER SET FULL_NAME=? WHERE CUSTOMER_ID=?",
                "CDC Updated " + id, id);
    }

    private static void deleteCustomer(DataSourceEntity source, long id) throws Exception {
        execute(source, "DELETE FROM FTDM_VIRT_DEMO.CUSTOMER WHERE CUSTOMER_ID=?", id);
    }

    private static void execute(DataSourceEntity source, String sql, Object... values) throws Exception {
        try (Connection connection = open(source); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private static Connection open(DataSourceEntity source) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", source.getUsername());
        properties.setProperty("password", source.getPassword());
        return DriverManager.getConnection(source.getJdbcUrl(), properties);
    }

    private static DataSourceEntity source() {
        DataSourceEntity source = new DataSourceEntity();
        source.setName("Local Db2 CDC integration");
        source.setKind("DB2UDB");
        source.setJdbcUrl(System.getenv("FORGETDM_DB2_CDC_URL"));
        source.setUsername(System.getenv("FORGETDM_DB2_CDC_USER"));
        source.setPassword(System.getenv("FORGETDM_DB2_CDC_PASS"));
        return source;
    }
}
