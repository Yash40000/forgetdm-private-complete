package io.forgetdm.security;

import io.forgetdm.audit.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessControlFilterAuditFallbackTest {

    @Test
    void successfulMaterialRequestGetsFallbackAuditWhenControllerDidNotAudit() throws Exception {
        Result result = execute("POST", "/api/datasources", (req, res) -> {
        }, Set.of("datasource.manage"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(result.audit()).record(eq("audit-user"), eq("HTTP_MATERIAL_ACTION"), eq("SYSTEM"),
                eq("api-request"), any(), eq("/api/datasources"), eq("SUCCESS"),
                eq("POST /api/datasources completed without a domain-specific audit event"),
                metadata.capture());
        assertTrue(metadata.getValue().contains("\"fallback\":true"));
        assertTrue(metadata.getValue().contains("\"method\":\"POST\""));
    }

    @Test
    void explicitDomainAuditSuppressesFallbackAudit() throws Exception {
        Result result = execute("POST", "/api/datasources", (req, res) ->
                req.setAttribute(AuditService.AUDIT_RECORDED_ATTRIBUTE, Boolean.TRUE),
                Set.of("datasource.manage"));

        verify(result.audit(), never()).record(any(), eq("HTTP_MATERIAL_ACTION"), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void readRequestDoesNotGetFallbackAudit() throws Exception {
        Result result = execute("GET", "/api/datasources", (req, res) -> {
        }, Set.of("datasource.read"));

        verify(result.audit(), never()).record(any(), eq("HTTP_MATERIAL_ACTION"), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void failedMaterialRequestDoesNotGetSuccessFallback() throws Exception {
        Result result = execute("POST", "/api/datasources", (req, res) -> ((HttpServletResponse) res).setStatus(409),
                Set.of("datasource.manage"));

        assertEquals(409, result.response().getStatus());
        verify(result.audit(), never()).record(any(), eq("HTTP_MATERIAL_ACTION"), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void successfulMaterialDownloadGetsFallbackAudit() throws Exception {
        Result result = execute("GET", "/api/mappings/runs/42/download", (req, res) -> {
        }, Set.of("mapping.read"));

        verify(result.audit()).record(eq("audit-user"), eq("HTTP_MATERIAL_ACTION"), eq("SYSTEM"),
                eq("api-request"), any(), eq("/api/mappings/runs/42/download"), eq("SUCCESS"),
                eq("GET /api/mappings/runs/42/download completed without a domain-specific audit event"),
                any());
    }

    private static Result execute(String method, String path, FilterChain chain, Set<String> permissions) throws Exception {
        AccessControlService access = mock(AccessControlService.class);
        AuditService audit = mock(AuditService.class);
        AccessPrincipal principal = new AccessPrincipal(42L, "audit-user", "Audit User", Set.of(), permissions);
        when(access.principalFromRequest(any())).thenReturn(Optional.of(principal));
        when(access.tokenFromRequest(any())).thenReturn(Optional.empty());

        AccessControlFilter filter = new AccessControlFilter(access, audit);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return new Result(response, audit);
    }

    private record Result(MockHttpServletResponse response, AuditService audit) {
    }
}
