package io.forgetdm.mainframe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.core.copybook.Copybook;
import io.forgetdm.core.copybook.CopybookParser;
import io.forgetdm.core.copybook.RecordCodec;
import io.forgetdm.core.copybook.RecordValue;
import io.forgetdm.core.copybook.codec.Ebcdic;
import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.core.mask.MaskingSemantics;
import io.forgetdm.mainframe.transport.LocalTransport;
import io.forgetdm.mainframe.transport.TransportFactory;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Acceptance proof: one governed policy produces identical values in a JDBC table and copybook file. */
class TableFileMaskingParityIntegrationTest {
    private static final String SECRET = "table-file-parity-secret";
    private static final String SEED = "release-2026-08";
    private static final Ebcdic EBCDIC = new Ebcdic("Cp037");

    @TempDir Path temp;

    @Test
    void masksTenFieldsIdenticallyAcrossJdbcAndFixedBlockCopybook() throws Exception {
        List<FieldSpec> fields = fields();
        Map<String, String> source = sourceRow();
        Map<String, String> databaseMasked = maskAsRelationalPolicy(fields, source);
        Map<String, String> storedTable = jdbcRoundTrip(fields, databaseMasked);

        Path sourceDir = Files.createDirectories(temp.resolve("source"));
        Path targetDir = Files.createDirectories(temp.resolve("target"));
        String copybookSource = copybook(fields);
        Copybook copybook = CopybookParser.parse(copybookSource);
        int lrecl = copybook.primaryRecord().length();
        Files.write(sourceDir.resolve("CUSTOMER.DAT"), sourceRecord(fields, source));

        MainframeJobEntity job = job();
        MainframeJobFileEntity file = jobFile(fields, lrecl);
        MainframeMaskingService service = service(job, file, copybookSource, lrecl, sourceDir, targetDir);

        service.run(job.getId());

        assertEquals("COMPLETED", job.getStatus(), job.getMessage());
        assertEquals(1, file.getRecordCount());
        assertEquals(lrecl, file.getInputBytes());
        assertEquals(lrecl, file.getOutputBytes());
        assertNotNull(file.getInputSha256());
        assertNotNull(file.getOutputSha256());
        assertNotEquals(file.getInputSha256(), file.getOutputSha256());

        byte[] delivered = Files.readAllBytes(targetDir.resolve("MASKED.CUSTOMER.DAT"));
        RecordCodec codec = new RecordCodec(copybook.primaryRecord(), EBCDIC);
        RecordValue decoded = codec.decode(delivered);
        Map<String, String> fileMasked = new LinkedHashMap<>();
        for (FieldSpec field : fields) {
            fileMasked.put(field.column(), decoded.get(field.copybookField()).value().stripTrailing());
        }

        assertEquals(storedTable, fileMasked,
                "database and copybook adapters must emit identical canonical masked values");
        long changed = fields.stream().filter(field ->
                !source.get(field.column()).equals(fileMasked.get(field.column()))).count();
        assertTrue(changed >= 8, "the proof must obfuscate at least 8 of the 10 governed fields");
    }

    private MainframeMaskingService service(MainframeJobEntity job, MainframeJobFileEntity file,
                                            String copybookSource, int lrecl,
                                            Path sourceDir, Path targetDir) {
        MainframeJobRepository jobs = mock(MainframeJobRepository.class);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.save(any())).thenAnswer(call -> call.getArgument(0));
        MainframeJobFileRepository files = mock(MainframeJobFileRepository.class);
        when(files.findByJobIdOrderByOrdinalAsc(job.getId())).thenReturn(List.of(file));
        when(files.save(any())).thenAnswer(call -> call.getArgument(0));

        MainframeConnectionEntity source = connection(1L, "source", sourceDir);
        MainframeConnectionEntity target = connection(2L, "target", targetDir);
        MainframeConnectionRepository connections = mock(MainframeConnectionRepository.class);
        when(connections.findById(1L)).thenReturn(Optional.of(source));
        when(connections.findById(2L)).thenReturn(Optional.of(target));

        CopybookDefEntity definition = new CopybookDefEntity();
        definition.setId(9L);
        definition.setName("CUSTOMER-PARITY");
        definition.setSource(copybookSource);
        definition.setCodePage("Cp037");
        definition.setRecordLength(lrecl);
        definition.setVisibility("SHARED");
        CopybookDefRepository copybooks = mock(CopybookDefRepository.class);
        when(copybooks.findById(9L)).thenReturn(Optional.of(definition));

        LocalTransport local = new LocalTransport();
        TransportFactory transports = mock(TransportFactory.class);
        when(transports.forConnection(any())).thenReturn(local);
        CopybookMaskRepository legacyMasks = mock(CopybookMaskRepository.class);
        when(legacyMasks.findByCopybookId(9L)).thenReturn(List.of());
        return new MainframeMaskingService(jobs, files, connections, copybooks, legacyMasks,
                transports, new MaskingEngine(SECRET), mock(java.util.concurrent.ExecutorService.class),
                mock(AuditService.class), mock(OwnershipGuard.class));
    }

    private static MainframeJobEntity job() {
        MainframeJobEntity job = new MainframeJobEntity();
        job.setId(71L);
        job.setName("table-file parity");
        job.setStatus("PENDING");
        job.setSourceConnectionId(1L);
        job.setTargetConnectionId(2L);
        job.setMaskingSeed(SEED);
        job.setPolicyId(41L);
        job.setDatasetId(51L);
        job.setCreatedBy("acceptance-test");
        job.setVisibility("SHARED");
        job.setFilesTotal(1);
        return job;
    }

    private static MainframeJobFileEntity jobFile(List<FieldSpec> fields, int lrecl) throws Exception {
        MainframeMaskPlan plan = new MainframeMaskPlan(41L, 201L,
                fields.stream().sorted(Comparator.comparingInt(field ->
                                MaskingSemantics.evaluationPriority(field.function().name())))
                        .map(field -> new MainframeMaskPlan.Rule("CUSTOMER-REC." + field.copybookField(),
                                field.ruleId(), "CUSTOMER", field.column(), field.function().name(),
                                field.param1(), field.param2(), field.salt())).toList());
        MainframeJobFileEntity file = new MainframeJobFileEntity();
        file.setId(72L);
        file.setJobId(71L);
        file.setAssetId(201L);
        file.setSourceName("CUSTOMER.DAT");
        file.setTargetName("MASKED.CUSTOMER.DAT");
        file.setTargetConnectionId(2L);
        file.setCopybookId(9L);
        file.setRecfm("FB");
        file.setLrecl(lrecl);
        file.setCodePage("Cp037");
        file.setStatus("PENDING");
        file.setMappingCount(fields.size());
        file.setMaskPlanJson(new ObjectMapper().findAndRegisterModules().writeValueAsString(plan));
        return file;
    }

    private static MainframeConnectionEntity connection(long id, String name, Path directory) {
        MainframeConnectionEntity connection = new MainframeConnectionEntity();
        connection.setId(id);
        connection.setName(name);
        connection.setType("LOCAL");
        connection.setBaseDir(directory.toString());
        connection.setCodePage("Cp037");
        connection.setVisibility("SHARED");
        return connection;
    }

    private static Map<String, String> maskAsRelationalPolicy(List<FieldSpec> fields,
                                                              Map<String, String> source) {
        MaskingEngine engine = new MaskingEngine(SECRET).withSeed(SEED);
        MaskContext context = new MaskContext(1);
        context.row.putAll(source);
        Map<String, String> result = new LinkedHashMap<>();
        fields.stream().sorted(Comparator.comparingInt(field ->
                        MaskingSemantics.evaluationPriority(field.function().name())))
                .forEach(field -> {
                    String masked = engine.mask(field.function(), field.salt(), source.get(field.column()),
                            field.param1(), field.param2(), context);
                    result.put(field.column(), masked);
                    context.masked.put(field.column(), masked);
                    String alias = MaskingSemantics.contextAlias(field.function().name());
                    if (alias != null) context.masked.put(alias, masked);
                });
        return result;
    }

    private static Map<String, String> jdbcRoundTrip(List<FieldSpec> fields, Map<String, String> values) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:table_file_parity_" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        String columns = String.join(",", fields.stream().map(FieldSpec::column).toList());
        jdbc.execute("create table masked_customer (" + String.join(",",
                fields.stream().map(field -> field.column() + " varchar(128)").toList()) + ")");
        jdbc.update("insert into masked_customer (" + columns + ") values ("
                        + String.join(",", java.util.Collections.nCopies(fields.size(), "?")) + ")",
                fields.stream().map(field -> values.get(field.column())).toArray());
        return jdbc.queryForObject("select " + columns + " from masked_customer", (result, rowNumber) -> {
            Map<String, String> row = new LinkedHashMap<>();
            for (FieldSpec field : fields) row.put(field.column(), result.getString(field.column()));
            return row;
        });
    }

    private static String copybook(List<FieldSpec> fields) {
        StringBuilder source = new StringBuilder("01 CUSTOMER-REC.\n");
        for (FieldSpec field : fields) {
            source.append("   05 ").append(field.copybookField()).append(" PIC X(")
                    .append(field.width()).append(").\n");
        }
        return source.toString();
    }

    private static byte[] sourceRecord(List<FieldSpec> fields, Map<String, String> values) {
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        for (FieldSpec field : fields) record.writeBytes(EBCDIC.encode(values.get(field.column()), field.width()));
        return record.toByteArray();
    }

    private static List<FieldSpec> fields() {
        return List.of(
                spec(1, "CUST-ID", "customer_id", MaskFunction.FORMAT_PRESERVE, null, null, "customer.id", 16),
                spec(2, "FIRST-NAME", "first_name", MaskFunction.FIRST_NAME, null, null, "name.first", 32),
                spec(3, "LAST-NAME", "last_name", MaskFunction.LAST_NAME, null, null, "name.last", 32),
                spec(4, "EMAIL", "email", MaskFunction.EMAIL, "NAME_SAFE", "SAFE_DOMAIN", "email", 64),
                spec(5, "PHONE", "phone", MaskFunction.PHONE, null, null, "phone", 32),
                spec(6, "SSN", "ssn", MaskFunction.SSN, null, null, "ssn", 16),
                spec(7, "CARD-NO", "card_number", MaskFunction.CREDIT_CARD, null, null, "ccn", 24),
                spec(8, "ACCOUNT-NO", "account_number", MaskFunction.BANK_ACCOUNT, null, null, "bank.account", 24),
                spec(9, "BIRTH-DATE", "birth_date", MaskFunction.DATE_SHIFT, "365", null, "birth.date", 10),
                spec(10, "STREET", "street", MaskFunction.ADDRESS_STREET, null, null, "addr", 64));
    }

    private static FieldSpec spec(long id, String file, String column, MaskFunction function,
                                  String param1, String param2, String salt, int width) {
        return new FieldSpec(id, file, column, function, param1, param2, salt, width);
    }

    private static Map<String, String> sourceRow() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("customer_id", "CUST-000042");
        row.put("first_name", "Yeshpal");
        row.put("last_name", "Solanki");
        row.put("email", "yeshpal.solanki@example.com");
        row.put("phone", "+1 (212) 555-0142");
        row.put("ssn", "123-45-6789");
        row.put("card_number", "4111111111111111");
        row.put("account_number", "000123456789");
        row.put("birth_date", "1987-06-14");
        row.put("street", "742 Evergreen Terrace");
        return row;
    }

    private record FieldSpec(Long ruleId, String copybookField, String column, MaskFunction function,
                             String param1, String param2, String salt, int width) { }
}
