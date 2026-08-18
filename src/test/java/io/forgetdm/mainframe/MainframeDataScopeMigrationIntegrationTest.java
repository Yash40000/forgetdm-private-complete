package io.forgetdm.mainframe;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Applies the complete migration chain and verifies the governed streaming schema is present. */
class MainframeDataScopeMigrationIntegrationTest {

    @Test
    void mainframeMigrationsCreateIndustryControlPlaneOnPostgresCompatibleSchema() {
        String url = "jdbc:h2:mem:mainframe_datascope_migrations_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table data_sources (id bigint primary key)");
        jdbc.execute("create table dataset_definitions (id bigint primary key, data_source_id bigint not null)");
        jdbc.execute("create table mf_connections (id bigint primary key)");
        jdbc.execute("create table mf_copybooks (id bigint primary key)");
        jdbc.execute("create table masking_policies (id bigint primary key)");
        jdbc.execute("create table masking_rules (id bigint primary key, policy_id bigint not null)");
        jdbc.execute("create table business_entities (id bigint primary key)");
        jdbc.execute("create table mf_jobs (id bigint primary key, created_at timestamp default current_timestamp)");
        jdbc.execute("create table mf_job_files (id bigint primary key)");

        ResourceDatabasePopulator migrations = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V86__datascope_mainframe_assets.sql"),
                new ClassPathResource("db/migration/V87__mainframe_policy_parity.sql"),
                new ClassPathResource("db/migration/V88__mainframe_streaming_delivery_evidence.sql"),
                new ClassPathResource("db/migration/V89__mainframe_connection_security.sql"));
        migrations.execute(dataSource);

        assertEquals(1, count(jdbc, "datascope_mainframe_assets"));
        assertEquals(1, count(jdbc, "datascope_mainframe_field_mappings"));
        assertEquals(1, column(jdbc, "mf_job_files", "mask_plan_json"));
        assertEquals(1, column(jdbc, "mf_job_files", "input_sha256"));
        assertEquals(1, column(jdbc, "mf_job_files", "source_version"));
        assertEquals(1, column(jdbc, "mf_connections", "password_secret_ref"));
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("select count(*) from information_schema.tables where table_name=?",
                Integer.class, table);
    }

    private static int column(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForObject("select count(*) from information_schema.columns where table_name=? and column_name=?",
                Integer.class, table, column);
    }
}
