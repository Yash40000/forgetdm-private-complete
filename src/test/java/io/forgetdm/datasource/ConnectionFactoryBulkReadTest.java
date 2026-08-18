package io.forgetdm.datasource;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionFactoryBulkReadTest {

    @Test
    void bulkReadsUseAnExplicitTransactionAndBoundedFetchWindow() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:bulk_read_test")) {
            ConnectionFactory.BulkReadConfig config = factory.configureBulkRead(connection, 1);

            assertEquals("H2", config.engine());
            assertEquals("FORWARD_ONLY_FETCH", config.cursorMode());
            assertEquals(100, config.fetchRows());
            assertTrue(config.transactionBound());
            assertFalse(connection.getAutoCommit());
        }
    }

    @Test
    void bulkFetchWindowHasAnEnterpriseSafetyCeiling() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:bulk_read_ceiling")) {
            ConnectionFactory.BulkReadConfig config = factory.configureBulkRead(connection, 500_000);

            assertEquals(50_000, config.fetchRows());
        }
    }
}
