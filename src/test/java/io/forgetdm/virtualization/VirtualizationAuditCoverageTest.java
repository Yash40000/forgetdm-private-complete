package io.forgetdm.virtualization;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceRepository;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VirtualizationAuditCoverageTest {

    @Test
    void cancelOperationWritesStructuredAuditWithOperationIdentity() {
        AuditService audit = mock(AuditService.class);
        VirtOps ops = mock(VirtOps.class);
        VirtualizationService service = service(audit, ops);
        when(ops.cancel("op-123")).thenReturn(true);
        when(ops.view("op-123")).thenReturn(Map.of(
                "id", "op-123",
                "kind", "PROVISION",
                "label", "Provision VDB customer360",
                "status", "RUNNING",
                "stages", List.of(Map.of("name", "materialize", "status", "RUNNING"))));

        asOperator(() -> service.cancelOperation("op-123"));

        verify(audit).record(eq("virt-operator"), eq("VIRT_OPERATION_CANCEL_REQUESTED"),
                eq("VIRTUALIZATION"), eq("virtual-operation"), eq("op-123"),
                eq("Provision VDB customer360"), eq("SUCCESS"),
                eq("Virtualization operation cancellation requested"),
                argThat(metadata -> metadata.contains("\"kind\":\"PROVISION\"")
                        && metadata.contains("\"status\":\"RUNNING\"")
                        && metadata.contains("\"stageCount\":1")));
    }

    @Test
    void cancelOperationDoesNotAuditSuccessWhenOperationIsNotRunning() {
        AuditService audit = mock(AuditService.class);
        VirtOps ops = mock(VirtOps.class);
        VirtualizationService service = service(audit, ops);
        when(ops.cancel("done-op")).thenReturn(false);

        assertThrows(ApiException.class, () -> asOperator(() -> service.cancelOperation("done-op")));

        verify(audit, never()).record(any(), eq("VIRT_OPERATION_CANCEL_REQUESTED"),
                eq("VIRTUALIZATION"), eq("virtual-operation"), eq("done-op"),
                any(), eq("SUCCESS"), eq("Virtualization operation cancellation requested"), any());
    }

    private static VirtualizationService service(AuditService audit, VirtOps ops) {
        return new VirtualizationService(
                mock(VirtualSnapshotRepository.class),
                mock(VirtualDatabaseRepository.class),
                mock(TimeFlowRepository.class),
                mock(DataSourceRepository.class),
                mock(DataSourceService.class),
                mock(ConnectionFactory.class),
                audit,
                mock(TimeFlowEngine.class),
                mock(ChunkStore.class),
                mock(ContainerVdbProvider.class),
                mock(ZfsVdbProvider.class),
                mock(TargetEnvironmentRepository.class),
                ops);
    }

    private static <T> T asOperator(java.util.function.Supplier<T> work) {
        AccessPrincipal principal = new AccessPrincipal(42L, "virt-operator", "Virt Operator",
                Set.of("TDM_ARCHITECT"), Set.of("virtualization.manage"));
        return AccessContext.callAs(principal, null, work);
    }
}
