package io.forgetdm.mainframe;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.dataset.DataSetDefinitionEntity;
import io.forgetdm.dataset.DataSetService;
import io.forgetdm.mainframe.transport.MainframeTransport;
import io.forgetdm.mainframe.transport.TransportFactory;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DataScopeMainframeAssetServiceTest {
    private DataScopeMainframeAssetRepository assets;
    private DataSetService datasets;
    private MainframeConnectionRepository connections;
    private CopybookDefRepository copybooks;
    private MainframeTransport transport;
    private DataScopeMainframeAssetService service;

    @BeforeEach
    void setUp() {
        assets = mock(DataScopeMainframeAssetRepository.class);
        datasets = mock(DataSetService.class);
        connections = mock(MainframeConnectionRepository.class);
        copybooks = mock(CopybookDefRepository.class);
        TransportFactory transports = mock(TransportFactory.class);
        transport = mock(MainframeTransport.class);
        when(transports.forConnection(any())).thenReturn(transport);

        DataSetDefinitionEntity definition = new DataSetDefinitionEntity();
        definition.setName("customer-mainframe");
        definition.setScopeKind("MAINFRAME");
        when(datasets.get(41L)).thenReturn(definition);

        MainframeConnectionEntity connection = new MainframeConnectionEntity();
        connection.setId(7L);
        connection.setName("zdev");
        connection.setCodePage("Cp037");
        connection.setVisibility("SHARED");
        when(connections.findById(7L)).thenReturn(Optional.of(connection));
        when(connections.findById(8L)).thenReturn(Optional.of(connection));

        CopybookDefEntity copybook = new CopybookDefEntity();
        copybook.setId(9L);
        copybook.setName("CUSTOMER");
        copybook.setCodePage("Cp037");
        copybook.setSource("""
                01 CUSTOMER-REC.
                   05 CUSTOMER-ID PIC X(10).
                   05 REGION-CODE PIC X(2).
                   05 CUSTOMER-NAME PIC X(30).
                """);
        copybook.setRecordLength(42);
        copybook.setVisibility("SHARED");
        when(copybooks.findById(9L)).thenReturn(Optional.of(copybook));
        when(assets.save(any())).thenAnswer(call -> {
            DataScopeMainframeAssetEntity value = call.getArgument(0);
            if (value.getId() == null) value.setId(101L);
            return value;
        });
        service = new DataScopeMainframeAssetService(assets, datasets, connections, copybooks,
                transports, mock(OwnershipGuard.class), mock(AuditService.class));
    }

    @Test
    void createsAssetWithCompositeCopybookPrimaryKey() {
        DataScopeMainframeAssetEntity request = request();
        request.setKeyFieldPaths("CUSTOMER-REC.CUSTOMER-ID,CUSTOMER-REC.REGION-CODE");
        request.setEntityKeyFieldPath("CUSTOMER-REC.CUSTOMER-ID");

        DataScopeMainframeAssetEntity saved = service.create(41L, request);

        assertEquals(101L, saved.getId());
        assertEquals("CUSTOMER-REC.CUSTOMER-ID,CUSTOMER-REC.REGION-CODE", saved.getKeyFieldPaths());
        assertEquals(42, saved.getLrecl());
        verify(assets).save(any(DataScopeMainframeAssetEntity.class));
    }

    @Test
    void rejectsUnknownCopybookPrimaryKeyField() {
        DataScopeMainframeAssetEntity request = request();
        request.setKeyFieldPaths("CUSTOMER-REC.DOES-NOT-EXIST");

        ApiException failure = assertThrows(ApiException.class, () -> service.create(41L, request));

        assertTrue(failure.getMessage().contains("unknown copybook field path"));
        verify(assets, never()).save(any());
    }

    @Test
    void freezesEveryPatternMatchAndExpandsCollisionSafeTarget() {
        DataScopeMainframeAssetEntity stored = request();
        stored.setId(101L);
        stored.setDatasetId(41L);
        stored.setTargetNameTemplate("TEST.${source}");
        when(assets.findById(101L)).thenReturn(Optional.of(stored));
        when(transport.list(any(), eq("PROD.CUSTOMER.*"))).thenReturn(List.of(
                new MainframeTransport.RemoteFile("PROD.CUSTOMER.A", "FB", 42, 420L, "PS"),
                new MainframeTransport.RemoteFile("PROD.CUSTOMER.B", "FB", 42, 840L, "PS")));

        List<DataScopeMainframeAssetService.ResolvedFile> resolved = service.resolve(41L, 101L);

        assertEquals(2, resolved.size());
        assertEquals("TEST.PROD.CUSTOMER.A", resolved.get(0).targetName());
        assertEquals("TEST.PROD.CUSTOMER.B", resolved.get(1).targetName());
    }

    private static DataScopeMainframeAssetEntity request() {
        DataScopeMainframeAssetEntity request = new DataScopeMainframeAssetEntity();
        request.setLogicalRole("customer-file");
        request.setSourceConnectionId(7L);
        request.setTargetConnectionId(8L);
        request.setSourceNamePattern("PROD.CUSTOMER.*");
        request.setCopybookId(9L);
        request.setRecfm("FB");
        request.setDsorg("PS");
        request.setSelectionMode("ALL");
        request.setEnabled(true);
        return request;
    }
}
