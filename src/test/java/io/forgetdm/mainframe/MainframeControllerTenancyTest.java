package io.forgetdm.mainframe;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.mainframe.transport.MainframeTransport;
import io.forgetdm.mainframe.transport.TransportFactory;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MainframeControllerTenancyTest {

    private static final AccessPrincipal ALPHA = principal(1L, "alpha", 10L, false);
    private static final AccessPrincipal ALPHA_PEER = principal(3L, "alpha-peer", 10L, false);
    private static final AccessPrincipal ADMIN = principal(99L, "admin", 99L, true);

    private MainframeConnectionRepository connections;
    private CopybookDefRepository copybooks;
    private CopybookMaskRepository masks;
    private MainframeJobRepository jobs;
    private MainframeJobFileRepository jobFiles;
    private TransportFactory transports;
    private MainframeMaskingService masking;
    private AuditService audit;
    private MainframeController controller;

    @BeforeEach
    void setUp() {
        connections = mock(MainframeConnectionRepository.class);
        copybooks = mock(CopybookDefRepository.class);
        masks = mock(CopybookMaskRepository.class);
        jobs = mock(MainframeJobRepository.class);
        jobFiles = mock(MainframeJobFileRepository.class);
        transports = mock(TransportFactory.class);
        masking = mock(MainframeMaskingService.class);
        audit = mock(AuditService.class);
        controller = new MainframeController(connections, copybooks, masks, jobs, jobFiles, transports,
                masking, mock(MainframeGenService.class), new OwnershipGuard(audit), audit);
    }

    @Test
    void listEndpointsReturnOnlyCallerGroupAndSharedObjectsWhileAdminSeesAll() {
        MainframeConnectionEntity alphaConnection = connection(1L, 1L, 10L, OwnershipGuard.GROUP);
        MainframeConnectionEntity betaConnection = connection(2L, 2L, 20L, OwnershipGuard.GROUP);
        MainframeConnectionEntity sharedConnection = connection(3L, null, null, OwnershipGuard.SHARED);
        CopybookDefEntity alphaCopybook = copybook(11L, 1L, 10L, OwnershipGuard.GROUP);
        CopybookDefEntity betaCopybook = copybook(12L, 2L, 20L, OwnershipGuard.GROUP);
        CopybookDefEntity sharedCopybook = copybook(13L, null, null, OwnershipGuard.SHARED);
        MainframeJobEntity alphaJob = job(21L, 1L, 10L, OwnershipGuard.GROUP, "PENDING");
        MainframeJobEntity betaJob = job(22L, 2L, 20L, OwnershipGuard.GROUP, "PENDING");
        MainframeJobEntity sharedJob = job(23L, null, null, OwnershipGuard.SHARED, "PENDING");

        when(connections.findAll()).thenAnswer(call -> new ArrayList<>(List.of(
                alphaConnection, betaConnection, sharedConnection)));
        when(copybooks.findAll()).thenAnswer(call -> new ArrayList<>(List.of(
                alphaCopybook, betaCopybook, sharedCopybook)));
        when(jobs.findAll()).thenAnswer(call -> new ArrayList<>(List.of(alphaJob, betaJob, sharedJob)));

        assertEquals(Set.of(1L, 3L), Set.copyOf(as(ALPHA, controller::listConnections).stream()
                .map(MainframeConnectionEntity::getId).toList()));
        assertEquals(Set.of(11L, 13L), Set.copyOf(as(ALPHA, controller::listCopybooks).stream()
                .map(row -> (Long) row.get("id")).toList()));
        assertEquals(Set.of(21L, 23L), Set.copyOf(as(ALPHA, controller::listJobs).stream()
                .map(MainframeJobEntity::getId).toList()));

        assertEquals(3, as(ADMIN, controller::listConnections).size());
        assertEquals(3, as(ADMIN, controller::listCopybooks).size());
        assertEquals(3, as(ADMIN, controller::listJobs).size());
    }

    @Test
    void orphanedNonSharedObjectsFailClosedForAuthenticatedCallers() {
        MainframeConnectionEntity orphanedConnection = connection(31L, null, null, OwnershipGuard.GROUP);
        orphanedConnection.setOwnerUsername("deleted-owner");
        CopybookDefEntity orphanedCopybook = copybook(32L, null, null, OwnershipGuard.GROUP);
        orphanedCopybook.setOwnerUsername("deleted-owner");
        MainframeJobEntity orphanedJob = job(33L, null, null, OwnershipGuard.GROUP, "PENDING");
        orphanedJob.setOwnerUsername("deleted-owner");

        when(connections.findAll()).thenAnswer(call -> new ArrayList<>(List.of(orphanedConnection)));
        when(copybooks.findAll()).thenAnswer(call -> new ArrayList<>(List.of(orphanedCopybook)));
        when(jobs.findAll()).thenAnswer(call -> new ArrayList<>(List.of(orphanedJob)));
        when(connections.findById(31L)).thenReturn(Optional.of(orphanedConnection));
        when(copybooks.findById(32L)).thenReturn(Optional.of(orphanedCopybook));
        when(jobs.findById(33L)).thenReturn(Optional.of(orphanedJob));

        assertTrue(as(ALPHA, controller::listConnections).isEmpty());
        assertTrue(as(ALPHA, controller::listCopybooks).isEmpty());
        assertTrue(as(ALPHA, controller::listJobs).isEmpty());
        assertForbidden(() -> as(ALPHA, () -> controller.listFiles(31L, "*")));
        assertForbidden(() -> as(ALPHA, () -> controller.getCopybook(32L)));
        assertForbidden(() -> as(ALPHA, () -> controller.getJob(33L)));

        assertEquals(1, as(ADMIN, controller::listConnections).size());
        assertEquals(1, as(ADMIN, controller::listCopybooks).size());
        assertEquals(1, as(ADMIN, controller::listJobs).size());
        verifyNoInteractions(transports);
        verifyNoInteractions(jobFiles);
    }

    @Test
    void connectionIdRoutesDenyCrossGroupBeforeTransportOrMutation() {
        MainframeConnectionEntity betaConnection = connection(2L, 2L, 20L, OwnershipGuard.GROUP);
        when(connections.findById(2L)).thenReturn(Optional.of(betaConnection));

        MainframeConnectionEntity update = connection(null, 1L, 10L, OwnershipGuard.GROUP);
        assertForbidden(() -> as(ALPHA, () -> controller.updateConnection(2L, update)));
        assertForbidden(() -> as(ALPHA, () -> controller.deleteConnection(2L)));
        assertForbidden(() -> as(ALPHA, () -> controller.testConnection(2L)));
        assertForbidden(() -> as(ALPHA, () -> controller.listFiles(2L, "HLQ.*")));
        assertForbidden(() -> as(ALPHA, () -> controller.statFile(2L, "HLQ.FILE")));

        verify(connections, never()).deleteById(2L);
        verify(connections, never()).save(any());
        verifyNoInteractions(transports);
    }

    @Test
    void connectionCreateStampsCallerAndUpdateCannotReplaceOwnership() {
        MainframeConnectionEntity submitted = connection(null, 2L, 20L, OwnershipGuard.SHARED);
        submitted.setName("alpha-lpar");
        when(connections.findByName("alpha-lpar")).thenReturn(Optional.empty());
        when(connections.save(any())).thenAnswer(call -> {
            MainframeConnectionEntity value = call.getArgument(0);
            if (value.getId() == null) value.setId(7L);
            return value;
        });

        MainframeConnectionEntity created = as(ALPHA, () -> controller.createConnection(submitted));
        assertEquals(1L, created.getOwnerUserId());
        assertEquals("alpha", created.getOwnerUsername());
        assertEquals(10L, created.getOwnerGroupId());
        assertEquals(OwnershipGuard.GROUP, created.getVisibility());

        when(connections.findById(7L)).thenReturn(Optional.of(created));
        when(connections.findByName("alpha-lpar")).thenReturn(Optional.of(created));
        MainframeTransport transport = mock(MainframeTransport.class);
        when(transports.forConnection(created)).thenReturn(transport);
        when(transport.list(created, "*")).thenReturn(List.of());
        assertEquals(true, as(ALPHA_PEER, () -> controller.testConnection(7L)).get("ok"));

        MainframeConnectionEntity update = connection(null, 2L, 20L, OwnershipGuard.SHARED);
        update.setName("alpha-lpar");
        MainframeConnectionEntity saved = as(ALPHA_PEER, () -> controller.updateConnection(7L, update));
        assertSame(created, saved);
        assertEquals(1L, saved.getOwnerUserId());
        assertEquals("alpha", saved.getOwnerUsername());
        assertEquals(10L, saved.getOwnerGroupId());
        assertEquals(OwnershipGuard.GROUP, saved.getVisibility());
    }

    @Test
    void copybookAndMaskRoutesAuthorizeTheCopybookParent() {
        CopybookDefEntity betaCopybook = copybook(12L, 2L, 20L, OwnershipGuard.GROUP);
        when(copybooks.findById(12L)).thenReturn(Optional.of(betaCopybook));
        MainframeController.CopybookReq update = new MainframeController.CopybookReq(
                "beta-copybook", betaCopybook.getSource(), "Cp037");

        assertForbidden(() -> as(ALPHA, () -> controller.getCopybook(12L)));
        assertForbidden(() -> as(ALPHA, () -> controller.updateCopybook(12L, update)));
        assertForbidden(() -> as(ALPHA, () -> controller.deleteCopybook(12L)));
        assertForbidden(() -> as(ALPHA, () -> controller.copybookFields(12L)));
        assertForbidden(() -> as(ALPHA, () -> controller.copybookMasks(12L)));
        assertForbidden(() -> as(ALPHA, () -> controller.saveCopybookMasks(12L,
                List.of(new MainframeController.MaskReq("CUSTOMER-NAME", "HASH", null, null)))));

        verifyNoInteractions(masks);
        verify(copybooks, never()).save(any());
        verify(copybooks, never()).deleteById(12L);
    }

    @Test
    void copybookCreateStampsCallerAndSameGroupCanReadChildMasks() {
        when(copybooks.findByName("alpha-copybook")).thenReturn(Optional.empty());
        when(copybooks.save(any())).thenAnswer(call -> {
            CopybookDefEntity value = call.getArgument(0);
            value.setId(31L);
            return value;
        });
        MainframeController.CopybookReq request = new MainframeController.CopybookReq(
                "alpha-copybook", validCopybookSource(), "Cp037");

        CopybookDefEntity created = as(ALPHA, () -> controller.createCopybook(request));
        assertEquals(1L, created.getOwnerUserId());
        assertEquals("alpha", created.getOwnerUsername());
        assertEquals(10L, created.getOwnerGroupId());
        assertEquals(OwnershipGuard.GROUP, created.getVisibility());

        when(copybooks.findById(31L)).thenReturn(Optional.of(created));
        CopybookMaskEntity mask = new CopybookMaskEntity();
        mask.setCopybookId(31L);
        when(masks.findByCopybookId(31L)).thenReturn(List.of(mask));
        assertEquals(1, as(ALPHA_PEER, () -> controller.copybookMasks(31L)).size());
    }

    @Test
    void registryMutationsWriteStructuredAuditWithoutSecretsOrCopybookBodies() {
        MainframeConnectionEntity submitted = connection(null, null, null, OwnershipGuard.GROUP);
        submitted.setName("alpha-zowe");
        submitted.setType("ZOWE");
        submitted.setHost("zowe.secret.example");
        submitted.setPort(443);
        submitted.setBasePath("/zosmf");
        submitted.setUsername("mf-user");
        submitted.setPassword("do-not-log");
        submitted.setBaseDir(null);
        when(connections.findByName("alpha-zowe")).thenReturn(Optional.empty());
        when(connections.save(any())).thenAnswer(call -> {
            MainframeConnectionEntity value = call.getArgument(0);
            if (value.getId() == null) value.setId(41L);
            return value;
        });

        MainframeConnectionEntity createdConnection = as(ALPHA, () -> controller.createConnection(submitted));
        when(connections.findById(41L)).thenReturn(Optional.of(createdConnection));
        when(connections.findByName("alpha-zowe")).thenReturn(Optional.of(createdConnection));
        MainframeConnectionEntity update = connection(null, null, null, OwnershipGuard.GROUP);
        update.setName("alpha-zowe");
        update.setType("ZOWE");
        update.setHost("zowe.secret.example");
        update.setPort(443);
        update.setBasePath("/zosmf2");
        update.setUsername("mf-user");
        update.setPassword("new-secret");
        update.setBaseDir(null);
        as(ALPHA, () -> controller.updateConnection(41L, update));
        as(ALPHA, () -> controller.deleteConnection(41L));

        String source = String.join("\n", "01 CUSTOMER-RECORD.", "   05 SECRET-ACCOUNT PIC X(20).");
        MainframeController.CopybookReq createCopybook = new MainframeController.CopybookReq("alpha-copybook", source, "Cp037");
        when(copybooks.findByName("alpha-copybook")).thenReturn(Optional.empty());
        when(copybooks.save(any())).thenAnswer(call -> {
            CopybookDefEntity value = call.getArgument(0);
            if (value.getId() == null) value.setId(51L);
            return value;
        });
        CopybookDefEntity createdCopybook = as(ALPHA, () -> controller.createCopybook(createCopybook));
        when(copybooks.findById(51L)).thenReturn(Optional.of(createdCopybook));
        when(masks.findByCopybookId(51L)).thenReturn(List.of());
        as(ALPHA, () -> controller.updateCopybook(51L, createCopybook));

        when(masks.save(any())).thenAnswer(call -> {
            CopybookMaskEntity value = call.getArgument(0);
            if (value.getId() == null) value.setId(61L);
            return value;
        });
        List<MainframeController.MaskReq> rules = List.of(
                new MainframeController.MaskReq("CUSTOMER-RECORD.SECRET-ACCOUNT", "LITERAL", "plain-secret", "another-secret"));
        as(ALPHA, () -> controller.saveCopybookMasks(51L, rules));

        CopybookMaskEntity existingMask = new CopybookMaskEntity();
        existingMask.setId(61L);
        existingMask.setCopybookId(51L);
        existingMask.setFieldPath("CUSTOMER-RECORD.SECRET-ACCOUNT");
        existingMask.setFunction("LITERAL");
        when(masks.findByCopybookId(51L)).thenReturn(List.of(existingMask));
        as(ALPHA, () -> controller.deleteCopybook(51L));

        verify(audit).record(eq("alpha"), eq("MAINFRAME_CONNECTION_CREATED"), eq("MAINFRAME"),
                eq("mainframe-connection"), eq("41"), eq("alpha-zowe"), eq("SUCCESS"), any(), any());
        verify(audit).record(eq("alpha"), eq("MAINFRAME_CONNECTION_UPDATED"), eq("MAINFRAME"),
                eq("mainframe-connection"), eq("41"), eq("alpha-zowe"), eq("SUCCESS"), any(), any());
        verify(audit).record(eq("alpha"), eq("MAINFRAME_CONNECTION_DELETED"), eq("MAINFRAME"),
                eq("mainframe-connection"), eq("41"), eq("alpha-zowe"), eq("SUCCESS"), any(), any());
        verify(audit).record(eq("alpha"), eq("MAINFRAME_COPYBOOK_CREATED"), eq("MAINFRAME"),
                eq("mainframe-copybook"), eq("51"), eq("alpha-copybook"), eq("SUCCESS"), any(), any());
        verify(audit).record(eq("alpha"), eq("MAINFRAME_COPYBOOK_UPDATED"), eq("MAINFRAME"),
                eq("mainframe-copybook"), eq("51"), eq("alpha-copybook"), eq("SUCCESS"), any(), any());
        verify(audit).record(eq("alpha"), eq("MAINFRAME_COPYBOOK_MASKS_REPLACED"), eq("MAINFRAME"),
                eq("mainframe-copybook"), eq("51"), eq("alpha-copybook"), eq("SUCCESS"), any(), any());
        verify(audit).record(eq("alpha"), eq("MAINFRAME_COPYBOOK_DELETED"), eq("MAINFRAME"),
                eq("mainframe-copybook"), eq("51"), eq("alpha-copybook"), eq("SUCCESS"), any(), any());

        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(audit, org.mockito.Mockito.atLeast(7)).record(any(), any(), any(), any(), any(), any(), any(), any(), metadata.capture());
        String combinedMetadata = String.join("\n", metadata.getAllValues());
        assertTrue(combinedMetadata.contains("\"hostConfigured\":true"));
        assertTrue(combinedMetadata.contains("\"sourceLength\""));
        assertTrue(combinedMetadata.contains("\"maskRuleCount\":1"));
        assertTrue(combinedMetadata.contains("\"functionCount\":1"));
        assertTrue(combinedMetadata.contains("\"functions\":[\"LITERAL\"]"));
        assertTrue(!combinedMetadata.contains("do-not-log"));
        assertTrue(!combinedMetadata.contains("new-secret"));
        assertTrue(!combinedMetadata.contains("zowe.secret.example"));
        assertTrue(!combinedMetadata.contains("SECRET-ACCOUNT PIC"));
        assertTrue(!combinedMetadata.contains("plain-secret"));
        assertTrue(!combinedMetadata.contains("another-secret"));
        assertTrue(!combinedMetadata.contains("CUSTOMER-RECORD.SECRET-ACCOUNT"));
    }

    @Test
    void jobCreationValidatesEveryReferencedObjectBeforeWritingAndStampsOwner() {
        MainframeConnectionEntity alphaSource = connection(1L, 1L, 10L, OwnershipGuard.GROUP);
        MainframeConnectionEntity betaConnection = connection(2L, 2L, 20L, OwnershipGuard.GROUP);
        MainframeConnectionEntity alphaTarget = connection(3L, 3L, 10L, OwnershipGuard.GROUP);
        CopybookDefEntity alphaCopybook = copybook(10L, 1L, 10L, OwnershipGuard.GROUP);
        CopybookDefEntity betaCopybook = copybook(20L, 2L, 20L, OwnershipGuard.GROUP);
        when(connections.findById(1L)).thenReturn(Optional.of(alphaSource));
        when(connections.findById(2L)).thenReturn(Optional.of(betaConnection));
        when(connections.findById(3L)).thenReturn(Optional.of(alphaTarget));
        when(copybooks.findById(10L)).thenReturn(Optional.of(alphaCopybook));
        when(copybooks.findById(20L)).thenReturn(Optional.of(betaCopybook));

        assertForbidden(() -> createJob(ALPHA, 2L, 3L, 10L, null));
        assertForbidden(() -> createJob(ALPHA, 1L, 2L, 10L, null));
        assertForbidden(() -> createJob(ALPHA, 1L, 3L, 20L, null));
        assertForbidden(() -> createJob(ALPHA, 1L, 3L, 10L, 2L));
        verify(jobs, never()).save(any());
        verify(jobFiles, never()).save(any());
        verifyNoInteractions(masking);

        when(jobs.save(any())).thenAnswer(call -> {
            MainframeJobEntity value = call.getArgument(0);
            value.setId(100L);
            return value;
        });
        when(jobFiles.save(any())).thenAnswer(call -> call.getArgument(0));
        when(jobFiles.findByJobIdOrderByOrdinalAsc(100L)).thenReturn(List.of());
        Map<String, Object> result = createJob(ALPHA, 1L, 3L, 10L, null);
        MainframeJobEntity created = (MainframeJobEntity) result.get("job");

        assertEquals(1L, created.getOwnerUserId());
        assertEquals("alpha", created.getOwnerUsername());
        assertEquals(10L, created.getOwnerGroupId());
        assertEquals(OwnershipGuard.GROUP, created.getVisibility());
        assertEquals("alpha", created.getCreatedBy());
        verify(masking).submitAsync(100L);
    }

    @Test
    void jobReadDoesNotExposeChildFilesAcrossGroups() {
        MainframeJobEntity betaJob = job(22L, 2L, 20L, OwnershipGuard.GROUP, "PENDING");
        when(jobs.findById(22L)).thenReturn(Optional.of(betaJob));

        assertForbidden(() -> as(ALPHA, () -> controller.getJob(22L)));

        verify(jobFiles, never()).findByJobIdOrderByOrdinalAsc(22L);
    }

    private Map<String, Object> createJob(AccessPrincipal caller, Long sourceId, Long targetId,
                                          Long copybookId, Long fileTargetId) {
        MainframeController.JobFileReq file = new MainframeController.JobFileReq(
                "HLQ.INPUT", copybookId, "FB", 20, "Cp037", fileTargetId, "HLQ.OUTPUT");
        MainframeController.JobReq request = new MainframeController.JobReq(
                "mainframe isolation", sourceId, targetId, "42", List.of(file));
        return as(caller, () -> controller.createJob(request));
    }

    private static MainframeConnectionEntity connection(Long id, Long ownerUserId, Long ownerGroupId,
                                                        String visibility) {
        MainframeConnectionEntity value = new MainframeConnectionEntity();
        value.setId(id);
        value.setName(id == null ? "connection" : "connection-" + id);
        value.setType("LOCAL");
        value.setBaseDir("D:/mainframe");
        value.setOwnerUserId(ownerUserId);
        value.setOwnerUsername(ownerUserId == null ? null : "owner-" + ownerUserId);
        value.setOwnerGroupId(ownerGroupId);
        value.setVisibility(visibility);
        return value;
    }

    private static CopybookDefEntity copybook(Long id, Long ownerUserId, Long ownerGroupId, String visibility) {
        CopybookDefEntity value = new CopybookDefEntity();
        value.setId(id);
        value.setName("copybook-" + id);
        value.setSource(validCopybookSource());
        value.setCodePage("Cp037");
        value.setOwnerUserId(ownerUserId);
        value.setOwnerUsername(ownerUserId == null ? null : "owner-" + ownerUserId);
        value.setOwnerGroupId(ownerGroupId);
        value.setVisibility(visibility);
        return value;
    }

    private static MainframeJobEntity job(Long id, Long ownerUserId, Long ownerGroupId,
                                          String visibility, String status) {
        MainframeJobEntity value = new MainframeJobEntity();
        value.setId(id);
        value.setName("job-" + id);
        value.setStatus(status);
        value.setOwnerUserId(ownerUserId);
        value.setOwnerUsername(ownerUserId == null ? null : "owner-" + ownerUserId);
        value.setOwnerGroupId(ownerGroupId);
        value.setVisibility(visibility);
        return value;
    }

    private static String validCopybookSource() {
        return String.join("\n", "01 CUSTOMER-RECORD.", "   05 CUSTOMER-ID PIC X(20).");
    }

    private static AccessPrincipal principal(Long userId, String username, Long groupId, boolean admin) {
        Set<String> permissions = admin ? Set.of("admin.all") : Set.of("mainframe.manage");
        return new AccessPrincipal(userId, username, username, Set.of(), permissions,
                List.of(new AccessControlService.GroupLite(groupId, "group-" + groupId)));
    }

    private static <T> T as(AccessPrincipal principal, Supplier<T> work) {
        return AccessContext.callAs(principal, null, work);
    }

    private static void assertForbidden(org.junit.jupiter.api.function.Executable action) {
        ApiException error = assertThrows(ApiException.class, action);
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        assertTrue(error.getMessage().contains("another group"));
    }
}
