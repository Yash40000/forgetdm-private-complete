package io.forgetdm.policy;

import io.forgetdm.audit.AuditService;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaskingScriptAuditCoverageTest {

    @Test
    void saveAndDeleteRecordStructuredIdentityWithoutLuaSource() {
        MaskingScriptRepository repo = mock(MaskingScriptRepository.class);
        AuditService audit = mock(AuditService.class);
        MaskingScriptService service = new MaskingScriptService(repo, new MaskingEngine("test-secret"), audit);
        String luaSource = "return value .. '-SECRET-SOURCE'";
        MaskingScriptEntity input = new MaskingScriptEntity();
        input.setName("bank.customer-ref");
        input.setDescription("Internal test");
        input.setLuaSource(luaSource);
        input.setVisibility("GLOBAL");
        when(repo.findByNameIgnoreCase("bank.customer-ref")).thenReturn(Optional.empty());
        when(repo.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            MaskingScriptEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 81L);
            return saved;
        });

        MaskingScriptEntity saved = AccessContext.callAs(principal(), null, () -> service.save(input));
        assertEquals(81L, saved.getId());
        when(repo.findById(81L)).thenReturn(Optional.of(saved));
        AccessContext.callAs(principal(), null, () -> {
            service.delete(81L);
            return null;
        });

        verify(audit).record(eq("mask-author"), eq("MASKING_SCRIPT_SAVED"), eq("MASKING"),
                eq("MASKING_SCRIPT"), eq("81"), eq("bank.customer-ref"), eq("SUCCESS"),
                eq("Created masking script"), argThat(metadata ->
                        metadata.contains("\"operation\":\"CREATE\"")
                                && metadata.contains("\"visibility\":\"GLOBAL\"")
                                && metadata.contains("\"sourceLength\":" + luaSource.length())
                                && !metadata.contains("SECRET-SOURCE")));
        verify(audit).record(eq("mask-author"), eq("MASKING_SCRIPT_DELETED"), eq("MASKING"),
                eq("MASKING_SCRIPT"), eq("81"), eq("bank.customer-ref"), eq("SUCCESS"),
                eq("Deleted masking script"), argThat(metadata ->
                        metadata.contains("\"visibility\":\"GLOBAL\"")
                                && !metadata.contains("SECRET-SOURCE")));
        verify(repo).deleteById(81L);
    }

    private static AccessPrincipal principal() {
        return new AccessPrincipal(7L, "mask-author", "Mask Author", Set.of("TDM_ENGINEER"),
                Set.of("policy.manage"), List.of());
    }
}
