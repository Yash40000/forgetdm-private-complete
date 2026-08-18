package io.forgetdm.provision;

import io.forgetdm.audit.AuditService;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ValueListAuditCoverageTest {

    @Test
    void saveImportAndDeleteRecordStructuredIdentityWithoutReferenceValues() throws Exception {
        ValueListRepository repo = mock(ValueListRepository.class);
        DataSourceService dataSources = mock(DataSourceService.class);
        AuditService audit = mock(AuditService.class);
        AtomicReference<ValueListEntity> stored = new AtomicReference<>();
        AtomicLong ids = new AtomicLong(90);
        when(repo.findByNameIgnoreCase(any())).thenAnswer(invocation -> {
            ValueListEntity value = stored.get();
            return value != null && value.getName().equalsIgnoreCase(invocation.getArgument(0))
                    ? Optional.of(value) : Optional.empty();
        });
        when(repo.save(any(ValueListEntity.class))).thenAnswer(invocation -> {
            ValueListEntity value = invocation.getArgument(0);
            if (value.getId() == null) setId(value, ids.incrementAndGet());
            stored.set(value);
            return value;
        });
        when(repo.findById(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get())
                .filter(value -> value.getId().equals(invocation.getArgument(0))));

        String sourceUrl = "jdbc:h2:mem:value_list_audit_source_" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        JdbcTemplate source = new JdbcTemplate(new DriverManagerDataSource(sourceUrl, "sa", ""));
        source.execute("CREATE TABLE product_reference(code VARCHAR(80))");
        source.update("INSERT INTO product_reference(code) VALUES ('SECRET_ALPHA'),('SECRET_ALPHA'),('SECRET_BETA')");
        DataSourceEntity ds = new DataSourceEntity();
        ds.setId(7L);
        ds.setName("reference-source");
        ds.setJdbcUrl(sourceUrl);
        ds.setUsername("sa");
        ds.setPassword("");
        when(dataSources.get(7L)).thenReturn(ds);

        JdbcTemplate config = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:value_list_audit_config_" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        ValueListService service = new ValueListService(repo, dataSources, new ConnectionFactory(), audit,
                new MaskingEngine("value-list-audit-secret"), config);
        AccessPrincipal principal = new AccessPrincipal(5L, "reference-owner", "Reference Owner",
                Set.of("TDM_ENGINEER"), Set.of("synthetic.manage"), List.of());

        ValueListEntity manual = new ValueListEntity();
        manual.setName("bank.product_status");
        manual.setDescription("SECRET DESCRIPTION");
        manual.setSystemTag("bank");
        manual.setListValues("SECRET_ACTIVE|SECRET_CLOSED");
        manual.setVisibility("PRIVATE");
        ValueListEntity saved = AccessContext.callAs(principal, null, () -> service.save(manual));

        ValueListEntity imported = AccessContext.callAs(principal, null, () -> service.importFromColumn(
                new ValueListService.ImportRequest(7L, null, "product_reference", "code",
                        "bank.product_code", "SECRET IMPORT DESCRIPTION", "bank", true, "GLOBAL")));
        AccessContext.callAs(principal, null, () -> {
            service.delete(imported.getId());
            return null;
        });

        verify(audit).record(eq("reference-owner"), eq("VALUE_LIST_SAVED"), eq("SYNTHETIC"),
                eq("VALUE_LIST"), eq(String.valueOf(saved.getId())), eq("bank.product_status"),
                eq("SUCCESS"), eq("Saved reusable value list"), safeMetadata(
                        "SECRET_ACTIVE", "SECRET_CLOSED", "SECRET DESCRIPTION"));
        verify(audit).record(eq("reference-owner"), eq("VALUE_LIST_IMPORTED"), eq("SYNTHETIC"),
                eq("VALUE_LIST"), eq(String.valueOf(imported.getId())), eq("bank.product_code"),
                eq("SUCCESS"), eq("Imported reusable value list"), safeMetadata(
                        "SECRET_ALPHA", "SECRET_BETA", "SECRET IMPORT DESCRIPTION"));
        verify(audit).record(eq("reference-owner"), eq("VALUE_LIST_DELETED"), eq("SYNTHETIC"),
                eq("VALUE_LIST"), eq(String.valueOf(imported.getId())), eq("bank.product_code"),
                eq("SUCCESS"), eq("Deleted reusable value list"), safeMetadata(
                        "SECRET_ALPHA", "SECRET_BETA", "SECRET IMPORT DESCRIPTION"));
    }

    private static String safeMetadata(String... forbidden) {
        return org.mockito.ArgumentMatchers.argThat(metadata -> {
            if (metadata == null || !metadata.contains("\"valueCount\"")) return false;
            for (String value : forbidden) {
                if (metadata.contains(value)) return false;
            }
            return true;
        });
    }

    private static void setId(ValueListEntity value, long id) throws Exception {
        Field field = ValueListEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(value, id);
    }
}
