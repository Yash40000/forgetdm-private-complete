package io.forgetdm.topology;

import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/**
 * User-triggered product tour fixture. Nothing is seeded silently: an authorized operator chooses
 * "Load example", receives a private in-process H2 source, and exercises the production discovery
 * path against real JDBC metadata.
 */
@Service
public class TopologySampleService {
    private static final String TOPOLOGY_NAME = "Customer 360 Topology Sample";
    private final TopologyService topologies;
    private final TopologyDiscoveryService discovery;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;

    public TopologySampleService(TopologyService topologies, TopologyDiscoveryService discovery,
                                 DataSourceService dataSources, ConnectionFactory connections) {
        this.topologies = topologies;
        this.discovery = discovery;
        this.dataSources = dataSources;
        this.connections = connections;
    }

    public SampleResult create() {
        String actor = topologies.actor();
        String suffix = actor.replaceAll("[^A-Za-z0-9_-]", "_");
        String sourceName = "Topology Sample - " + suffix;
        DataSourceEntity source = dataSources.list().stream()
                .filter(candidate -> sourceName.equalsIgnoreCase(candidate.getName()))
                .findFirst()
                .orElseGet(() -> createSource(sourceName, suffix));
        createBankingSchema(source);

        TopologyService.TopologySummary topology = topologies.list().stream()
                .filter(candidate -> TOPOLOGY_NAME.equalsIgnoreCase(candidate.name()))
                .findFirst()
                .orElseGet(() -> topologies.create(new TopologyService.CreateTopology(
                        TOPOLOGY_NAME,
                        "Retail banking",
                        "Product tour: customer, account, card, beneficiary, and transaction relationships.",
                        "PRIVATE")));
        List<TopologyService.SourceBinding> bindings = topologies.sourceRows(topology.id());
        if (bindings.stream().noneMatch(binding -> binding.dataSourceId().equals(source.getId())
                && "BANKING".equalsIgnoreCase(binding.schemaName()))) {
            topologies.attachSource(topology.id(),
                    new TopologyService.AttachSource(source.getId(), "BANKING", "Core banking sample"));
        }
        TopologyService.DiscoveryOperation latest = topologies.latestOperation(topology.id());
        TopologyService.DiscoveryOperation operation =
                latest != null && ("RUNNING".equals(latest.status()) || "QUEUED".equals(latest.status()))
                        ? latest : discovery.start(topology.id());
        return new SampleResult(topologies.get(topology.id()), operation);
    }

    private DataSourceEntity createSource(String name, String actorSuffix) {
        DataSourceEntity source = new DataSourceEntity();
        source.setName(name);
        source.setKind("H2");
        source.setRole("SOURCE");
        source.setEnvironment("DEMO");
        source.setTags("topology-sample,product-tour");
        source.setJdbcUrl("jdbc:h2:mem:forgetdm_topology_" + actorSuffix.toLowerCase(Locale.ROOT)
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        source.setUsername("sa");
        source.setPassword("");
        source.setVisibility("PRIVATE");
        return dataSources.create(source);
    }

    private void createBankingSchema(DataSourceEntity source) {
        try (Connection connection = connections.openPooled(source);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS BANKING");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS BANKING.CUSTOMERS (
                        CUSTOMER_ID BIGINT PRIMARY KEY,
                        CUSTOMER_NO VARCHAR(24) NOT NULL UNIQUE,
                        FULL_NAME VARCHAR(160) NOT NULL,
                        EMAIL VARCHAR(180),
                        CREATED_AT TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS BANKING.ACCOUNTS (
                        ACCOUNT_ID BIGINT PRIMARY KEY,
                        CUSTOMER_ID BIGINT NOT NULL,
                        ACCOUNT_NO VARCHAR(34) NOT NULL UNIQUE,
                        ACCOUNT_TYPE VARCHAR(24) NOT NULL,
                        BALANCE DECIMAL(18,2) NOT NULL,
                        CONSTRAINT FK_ACCOUNT_CUSTOMER FOREIGN KEY (CUSTOMER_ID)
                            REFERENCES BANKING.CUSTOMERS(CUSTOMER_ID)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS BANKING.PAYMENT_CARDS (
                        CARD_ID BIGINT PRIMARY KEY,
                        CUSTOMER_ID BIGINT NOT NULL,
                        ACCOUNT_ID BIGINT NOT NULL,
                        CARD_TOKEN VARCHAR(32) NOT NULL UNIQUE,
                        STATUS VARCHAR(16) NOT NULL,
                        CONSTRAINT FK_CARD_CUSTOMER FOREIGN KEY (CUSTOMER_ID)
                            REFERENCES BANKING.CUSTOMERS(CUSTOMER_ID),
                        CONSTRAINT FK_CARD_ACCOUNT FOREIGN KEY (ACCOUNT_ID)
                            REFERENCES BANKING.ACCOUNTS(ACCOUNT_ID)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS BANKING.BENEFICIARIES (
                        BENEFICIARY_ID BIGINT PRIMARY KEY,
                        CUSTOMER_ID BIGINT NOT NULL,
                        BENEFICIARY_NAME VARCHAR(160) NOT NULL,
                        BANK_ACCOUNT_NO VARCHAR(34),
                        CONSTRAINT FK_BENEFICIARY_CUSTOMER FOREIGN KEY (CUSTOMER_ID)
                            REFERENCES BANKING.CUSTOMERS(CUSTOMER_ID)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS BANKING.ACCOUNT_TRANSACTIONS (
                        TRANSACTION_ID BIGINT PRIMARY KEY,
                        ACCOUNT_ID BIGINT NOT NULL,
                        BENEFICIARY_ID BIGINT,
                        AMOUNT DECIMAL(18,2) NOT NULL,
                        POSTED_AT TIMESTAMP NOT NULL,
                        CONSTRAINT FK_TX_ACCOUNT FOREIGN KEY (ACCOUNT_ID)
                            REFERENCES BANKING.ACCOUNTS(ACCOUNT_ID),
                        CONSTRAINT FK_TX_BENEFICIARY FOREIGN KEY (BENEFICIARY_ID)
                            REFERENCES BANKING.BENEFICIARIES(BENEFICIARY_ID)
                    )
                    """);
        } catch (Exception e) {
            throw io.forgetdm.common.ApiException.bad("Could not create the topology sample schema: " + e.getMessage());
        }
    }

    public record SampleResult(TopologyService.TopologySummary topology,
                               TopologyService.DiscoveryOperation discovery) {}
}
