package io.forgetdm.topology;

import io.forgetdm.datasource.DataSourceEntity;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologyMetadataReaderTest {

    @Test
    void capturesSchemaColumnsKeysAndDeclaredRelationships() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:topology-capture;DB_CLOSE_DELAY=-1")) {
            connection.createStatement().execute("CREATE SCHEMA BANK");
            connection.createStatement().execute("""
                    CREATE TABLE BANK.CUSTOMERS (
                        CUSTOMER_ID BIGINT PRIMARY KEY,
                        CUSTOMER_NO VARCHAR(20) NOT NULL UNIQUE,
                        DISPLAY_NAME VARCHAR(120)
                    )
                    """);
            connection.createStatement().execute("""
                    CREATE TABLE BANK.ACCOUNTS (
                        ACCOUNT_ID BIGINT PRIMARY KEY,
                        CUSTOMER_ID BIGINT NOT NULL,
                        BALANCE DECIMAL(18,2),
                        CONSTRAINT FK_ACCOUNT_CUSTOMER FOREIGN KEY (CUSTOMER_ID)
                            REFERENCES BANK.CUSTOMERS(CUSTOMER_ID)
                    )
                    """);
            DataSourceEntity source = new DataSourceEntity();
            source.setKind("H2");
            AtomicInteger tableCount = new AtomicInteger();

            TopologyMetadataReader.Capture capture = new TopologyMetadataReader().read(
                    connection, source, "BANK", () -> {}, new TopologyMetadataReader.Progress() {
                        @Override
                        public void tablesDiscovered(int count) {
                            tableCount.set(count);
                        }
                    });

            assertEquals(2, capture.tables().size());
            assertEquals(2, tableCount.get());
            assertEquals(3, capture.columns().get("customers").size());
            assertTrue(capture.columns().get("customers").stream()
                    .anyMatch(column -> column.name().equalsIgnoreCase("CUSTOMER_ID") && column.primaryKey()));
            assertEquals(1, capture.foreignKeys().size());
            TopologyMetadataReader.ForeignKeyDef relationship = capture.foreignKeys().get(0);
            assertEquals("ACCOUNTS", relationship.childTable());
            assertEquals("CUSTOMERS", relationship.parentTable());
            assertEquals("CUSTOMER_ID", relationship.childColumns().get(0));
            assertEquals("CUSTOMER_ID", relationship.parentColumns().get(0));
        }
    }
}
