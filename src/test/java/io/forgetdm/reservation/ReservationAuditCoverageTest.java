package io.forgetdm.reservation;

import io.forgetdm.audit.AuditService;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.OwnershipGuard;
import io.forgetdm.subset.SubsetService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationAuditCoverageTest {

    @Test
    void findAndReleaseReservationWriteStructuredAuditWithoutRowKeysOrCriteria() throws Exception {
        String url = "jdbc:h2:mem:reservation_audit_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE \"accounts\"(\"id\" BIGINT PRIMARY KEY, status VARCHAR(20), ssn VARCHAR(20))");
            connection.createStatement().execute("INSERT INTO \"accounts\"(\"id\",status,ssn) VALUES (101,'ACTIVE','123-45-6789'),(202,'ACTIVE','123-45-6789'),(303,'CLOSED','000-00-0000')");
        }

        AuditService audit = mock(AuditService.class);
        ReservationRepository repo = mock(ReservationRepository.class);
        DataSourceService dataSources = mock(DataSourceService.class);
        SubsetService subsets = mock(SubsetService.class);
        DataSourceEntity ds = new DataSourceEntity();
        ds.setId(77L);
        ds.setName("reservation-source");
        ds.setJdbcUrl(url);
        ds.setUsername("sa");
        ds.setPassword("");
        when(dataSources.get(77L)).thenReturn(ds);
        when(subsets.primaryKey(any(), eq("accounts"))).thenReturn("id");
        when(repo.findByDataSourceIdAndTableNameAndStatus(77L, "accounts", "ACTIVE")).thenReturn(List.of());
        when(repo.save(any(ReservationEntity.class))).thenAnswer(inv -> {
            ReservationEntity r = inv.getArgument(0);
            if (r.getId() == null) ReflectionTestUtils.setField(r, "id", 501L);
            return r;
        });
        when(repo.findById(501L)).thenAnswer(inv -> {
            ReservationEntity r = new ReservationEntity();
            ReflectionTestUtils.setField(r, "id", 501L);
            r.setDataSourceId(77L);
            r.setTableName("accounts");
            r.setCriteria("status = 'ACTIVE' AND ssn = '123-45-6789'");
            r.setRowKeysJson("[\"101\",\"202\"]");
            r.setReservedBy("qa-reserver");
            r.setPurpose("SECRET purpose note");
            r.setStatus("ACTIVE");
            r.setExpiresAt(Instant.now().plusSeconds(3600));
            r.setOwnerUserId(11L);
            r.setOwnerGroupId(101L);
            r.setVisibility(OwnershipGuard.GROUP);
            return Optional.of(r);
        });

        ReservationService service = new ReservationService(repo, dataSources, new ConnectionFactory(), subsets, audit, new OwnershipGuard(audit));

        asQa(() -> {
            service.findAndReserve(77L, "accounts", "status = 'ACTIVE' AND ssn = '123-45-6789'",
                    2, "qa-reserver", "SECRET purpose note", 4);
            service.release(501L);
            return null;
        });

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(audit, times(2)).record(eq("qa-user"), action.capture(), eq("RESERVE"), eq("RESERVATION"),
                eq("501"), eq("accounts"), eq("SUCCESS"), any(), metadata.capture());

        assertEquals(List.of("DATA_RESERVED", "DATA_RELEASED"), action.getAllValues());
        String joined = String.join("\n", metadata.getAllValues());
        assertFalse(joined.contains("123-45-6789"), joined);
        assertFalse(joined.contains("\\\"101\\\""), joined);
        assertFalse(joined.contains("\\\"202\\\""), joined);
        assertFalse(joined.contains("SECRET purpose note"), joined);
        assertEquals(2, metadata.getAllValues().stream().filter(m -> m.contains("\"reservedCount\":2")).count());
    }

    private static <T> T asQa(java.util.function.Supplier<T> work) {
        AccessPrincipal principal = new AccessPrincipal(11L, "qa-user", "QA User",
                Set.of("TESTER"), Set.of("reservation.manage"),
                List.of(new AccessControlService.GroupLite(101L, "qa")));
        return AccessContext.callAs(principal, null, work);
    }
}
