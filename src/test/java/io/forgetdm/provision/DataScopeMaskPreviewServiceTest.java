package io.forgetdm.provision;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataScopeMaskPreviewServiceTest {

    @Test
    void quotedSchemaPreviewWorksForCaseSensitiveH2VdbSchemas() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:preview_case;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA \"TEMENOS_TDM\"");
                statement.execute("CREATE TABLE \"TEMENOS_TDM\".\"FBNK_XML_PII_TEST\" "
                        + "(\"RECORD_ID\" VARCHAR(40), \"XML_PAYLOAD\" CLOB)");
                statement.execute("INSERT INTO \"TEMENOS_TDM\".\"FBNK_XML_PII_TEST\" VALUES "
                        + "('R1', '<CustomerProfile><FirstName>Yeshpal</FirstName></CustomerProfile>')");
                String qualified = DataScopeMaskPreviewService.qualifiedName(
                        connection, "TEMENOS_TDM", "FBNK_XML_PII_TEST");
                try (ResultSet rows = statement.executeQuery("SELECT * FROM " + qualified)) {
                    rows.next();
                    assertEquals("R1", rows.getString(1));
                    assertEquals("<CustomerProfile><FirstName>Yeshpal</FirstName></CustomerProfile>",
                            DataScopeMaskPreviewService.jdbcText(rows, 2));
                }
            }
        }
    }
}
