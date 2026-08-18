package io.forgetdm.discovery;

import io.forgetdm.audit.AuditService;
import io.forgetdm.config.ForgeProps;
import io.forgetdm.core.temenos.TemenosCodec;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.policy.MaskingPolicyRepository;
import io.forgetdm.policy.MaskingPolicyEntity;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.policy.MaskingRuleEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscoveryStructuredContentTest {
    private static final long SOURCE_ID = 8181L;

    private final ConnectionFactory connections = new ConnectionFactory();
    private final DataSourceService sources = mock(DataSourceService.class);
    private final ClassificationRepository classifications = mock(ClassificationRepository.class);
    private final List<ClassificationEntity> persisted = new ArrayList<>();
    private final MaskingPolicyRepository policies = mock(MaskingPolicyRepository.class);
    private final MaskingRuleRepository rules = mock(MaskingRuleRepository.class);
    private final List<MaskingRuleEntity> persistedRules = new ArrayList<>();
    private DiscoveryService discovery;
    private String url;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:structured_discovery_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=FALSE;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA TEMENOS_TDM");
            statement.execute("CREATE TABLE TEMENOS_TDM.FBNK_RECORDS "
                    + "(XMLRECORD CLOB, MV_PHONE VARCHAR(1000), MV_ID_TYPE VARCHAR(1000), "
                    + "MV_ID_NUMBER VARCHAR(1000), MX_MESSAGE CLOB)");
        }
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO TEMENOS_TDM.FBNK_RECORDS "
                             + "(XMLRECORD, MV_PHONE, MV_ID_TYPE, MV_ID_NUMBER, MX_MESSAGE) VALUES (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < 3; i++) {
                insert.setString(1, "RECID=CUSTOMER-" + i + TemenosCodec.FM
                        + "NAME=Jordan Mercer" + TemenosCodec.FM
                        + "ID.NO=2812345678" + i + TemenosCodec.VM + "P12345678" + i);
                insert.setString(2, "+9745555123" + i + TemenosCodec.VM + "+9744444123" + i);
                insert.setString(3, "QID" + TemenosCodec.VM + "PASSPORT");
                insert.setString(4, "28" + i + "123456789" + TemenosCodec.VM + "P12345678" + i);
                insert.setString(5, "<Document><Party><FirstName>Jordan</FirstName>"
                        + "<LastName>Mercer</LastName><FullName>Jordan Mercer</FullName>"
                        + "<EmailAddress>jordan" + i + "@example.test</EmailAddress>"
                        + "<Phone>+9745555123" + i + "</Phone>"
                        + "<AccountNumber>9876543210123456</AccountNumber>"
                        + "<CCN>4111111111111111</CCN>"
                        + "<NationalId>123-45-6789</NationalId>"
                        + "<Address>44 West 18th Street New York NY 10011</Address>"
                        + "</Party></Document>");
                insert.addBatch();
            }
            insert.executeBatch();
        }

        DataSourceEntity source = new DataSourceEntity();
        source.setId(SOURCE_ID);
        source.setName("Temenos structured discovery test");
        source.setKind("H2");
        source.setJdbcUrl(url);
        source.setUsername("sa");
        source.setPassword("");
        source.setRole("SOURCE");
        when(sources.getSourceCapable(SOURCE_ID)).thenReturn(source);
        when(sources.get(SOURCE_ID)).thenReturn(source);
        when(classifications.findByDataSourceIdAndSchemaName(any(), anyString()))
                .thenAnswer(ignored -> new ArrayList<>(persisted));
        when(classifications.findByDataSourceIdAndSchemaNameAndStatus(SOURCE_ID, "TEMENOS_TDM", "APPROVED"))
                .thenAnswer(ignored -> persisted.stream().filter(row -> "APPROVED".equals(row.getStatus())).toList());
        when(classifications.save(any(ClassificationEntity.class))).thenAnswer(invocation -> {
            ClassificationEntity row = invocation.getArgument(0);
            persisted.add(row);
            return row;
        });
        when(policies.save(any(MaskingPolicyEntity.class))).thenAnswer(invocation -> {
            MaskingPolicyEntity policy = invocation.getArgument(0);
            policy.setId(91L);
            return policy;
        });
        when(rules.save(any(MaskingRuleEntity.class))).thenAnswer(invocation -> {
            MaskingRuleEntity rule = invocation.getArgument(0);
            persistedRules.add(rule);
            return rule;
        });
        PiiPatternService patterns = mock(PiiPatternService.class);
        when(patterns.resolveEffective()).thenReturn(new PiiPatternService.Effective(Map.of(), Map.of(), Map.of()));
        discovery = new DiscoveryService(classifications, sources, connections,
                policies, rules, mock(AuditService.class),
                new ForgeProps(), patterns);
    }

    @AfterEach
    void tearDown() {
        connections.destroy();
    }

    @Test
    void discoversTemenosAndXmlLeavesWhileKeepingOnePhysicalColumnFinding() {
        List<ClassificationEntity> found = discovery.scan(SOURCE_ID, "TEMENOS_TDM", Set.of(),
                Set.of("FBNK_RECORDS"), null);

        assertEquals(4, found.size());
        ClassificationEntity record = finding("XMLRECORD");
        assertEquals("TEMENOS", record.getContentFormat());
        assertEquals("STRUCTURED_DATA", record.getPiiType());
        assertTrue(record.getLogicalPaths().contains("/NAME [FULL_NAME]"));
        assertTrue(record.getLogicalPaths().contains("/ID.NO [TAX_ID]"));
        assertTrue(record.isPathReviewRequired());

        ClassificationEntity phone = finding("MV_PHONE");
        assertEquals("TEMENOS", phone.getContentFormat());
        assertEquals("STRUCTURED_DATA", phone.getPiiType());
        assertFalse(phone.isPathReviewRequired(), "a homogeneous phone collection is leaf-mask ready");

        assertTrue(persisted.stream().noneMatch(row -> "MV_ID_TYPE".equals(row.getColumnName())),
                "domain/type labels are metadata, not PII values");
        ClassificationEntity identifiers = finding("MV_ID_NUMBER");
        assertEquals("STRUCTURED_DATA", identifiers.getPiiType());
        assertTrue(identifiers.getLogicalPaths().contains("[PERSON_ID]"));
        assertTrue(identifiers.isPathReviewRequired());

        ClassificationEntity xml = finding("MX_MESSAGE");
        assertEquals("XML", xml.getContentFormat());
        assertEquals("STRUCTURED_DATA", xml.getPiiType());
        assertEquals("FORMAT_PRESERVE", xml.getSuggestedFunction());
        List<StructuredReviewCodec.Field> logicalFields = StructuredReviewCodec.decode(xml.getStructuredReview());
        assertEquals(9, logicalFields.size());
        assertTrue(logicalFields.stream().allMatch(field -> "SUGGESTED".equals(field.status())));
        assertEquals("FIRST_NAME", logicalFields.stream()
                .filter(field -> field.selector().contains("FirstName")).findFirst().orElseThrow().piiType());
        assertNotNull(xml.getLogicalPaths());
        assertTrue(xml.getLogicalPaths().contains("EmailAddress"));
        assertTrue(xml.getLogicalPaths().contains("FirstName[1] [FIRST_NAME]"));
        assertTrue(xml.getLogicalPaths().contains("LastName[1] [LAST_NAME]"));
        assertTrue(xml.getLogicalPaths().contains("FullName[1] [FULL_NAME]"));
        assertTrue(xml.getLogicalPaths().contains("AccountNumber[1] [BANK_ACCOUNT]"));
        assertTrue(xml.getLogicalPaths().contains("CCN[1] [CREDIT_CARD]"));
        assertTrue(xml.getLogicalPaths().contains("NationalId[1] [TAX_ID]"));
        assertTrue(xml.getLogicalPaths().contains("Address[1] [FULL_ADDRESS]"));
        assertFalse(xml.getLogicalPaths().contains("FirstName[1] [FULL_NAME]"));
        assertFalse(xml.getLogicalPaths().contains("LastName[1] [FULL_NAME]"));
        assertFalse(xml.getLogicalPaths().contains("Address[1] [ADDRESS]"));
        assertTrue(xml.isPathReviewRequired());
    }

    @Test
    void generatedPolicyUsesLeafPlansEvenWhenACollectionNeedsNoPathReview() {
        discovery.scan(SOURCE_ID, "TEMENOS_TDM", Set.of(), Set.of("FBNK_RECORDS"), null);
        persisted.forEach(row -> {
            List<StructuredReviewCodec.Field> logicalFields = StructuredReviewCodec.decode(row.getStructuredReview());
            row.setStructuredReview(StructuredReviewCodec.encode(logicalFields.stream()
                    .map(field -> field.withDecision("APPROVED", null,
                            field.suggestedParam1(), field.suggestedParam2()))
                    .toList()));
            row.setStatus("APPROVED");
        });

        discovery.generatePolicy(SOURCE_ID, "TEMENOS_TDM", "temenos-structured-policy");

        MaskingRuleEntity phoneRule = persistedRules.stream()
                .filter(rule -> "MV_PHONE".equals(rule.getColumnName())).findFirst().orElseThrow();
        assertNotNull(phoneRule.getStructuredConfig());
        assertEquals("TEMENOS", io.forgetdm.core.mask.StructuredMaskingCodec
                .decode(phoneRule.getStructuredConfig()).format());

        MaskingRuleEntity xmlRule = persistedRules.stream()
                .filter(rule -> "MX_MESSAGE".equals(rule.getColumnName())).findFirst().orElseThrow();
        io.forgetdm.core.mask.StructuredMaskingCodec.Config xmlPlan =
                io.forgetdm.core.mask.StructuredMaskingCodec.decode(xmlRule.getStructuredConfig());
        assertEquals("XML", xmlPlan.format());
        assertEquals(9, xmlPlan.rules().size());
        assertEquals("FIRST_NAME", ruleFor(xmlPlan, "FirstName").function());
        assertEquals("LAST_NAME", ruleFor(xmlPlan, "LastName").function());
        assertEquals("FULL_NAME", ruleFor(xmlPlan, "FullName").function());
        assertEquals("CREDIT_CARD", ruleFor(xmlPlan, "CCN").function());
        assertEquals("BANK_ACCOUNT", ruleFor(xmlPlan, "AccountNumber").function());
    }

    private ClassificationEntity finding(String column) {
        return persisted.stream().filter(row -> column.equals(row.getColumnName())).findFirst().orElseThrow();
    }

    private static io.forgetdm.core.mask.StructuredMaskingCodec.RuleSpec ruleFor(
            io.forgetdm.core.mask.StructuredMaskingCodec.Config config, String pathPart) {
        return config.rules().stream().filter(rule -> rule.selector().contains(pathPart)).findFirst().orElseThrow();
    }
}
