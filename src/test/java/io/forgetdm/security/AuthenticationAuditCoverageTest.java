package io.forgetdm.security;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AuthenticationAuditCoverageTest {

    @Test
    void recordsStructuredLoginFailureSuccessAndLogoutWithoutSecrets() {
        JdbcTemplate jdbc = database();
        AuditService audit = mock(AuditService.class);
        AccessControlService access = new AccessControlService(jdbc, audit, "unused", 12);
        String password = "correct-horse-battery-staple";
        jdbc.update("INSERT INTO forge_users VALUES (1,'qa-user','QA User',?,TRUE,CURRENT_TIMESTAMP,NULL)",
                PasswordHasher.hash(password));
        jdbc.update("INSERT INTO forge_user_roles VALUES (1,'TESTER')");

        assertThrows(ApiException.class, () -> access.login("qa-user", "wrong-secret"));
        AccessControlService.LoginResult login = access.login("qa-user", password);
        access.logout(login.token());

        ArgumentCaptor<String> actor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> category = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resourceType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resourceId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resourceName = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(audit, times(3)).record(actor.capture(), action.capture(), category.capture(),
                resourceType.capture(), resourceId.capture(), resourceName.capture(), outcome.capture(),
                detail.capture(), metadata.capture());

        assertEquals(List.of("LOGIN_FAILED", "LOGIN_SUCCESS", "LOGOUT"), action.getAllValues());
        assertEquals(List.of("FAILURE", "SUCCESS", "SUCCESS"), outcome.getAllValues());
        assertTrue(category.getAllValues().stream().allMatch("SECURITY"::equals));
        assertTrue(resourceType.getAllValues().stream().allMatch("auth-session"::equals));
        assertEquals("qa-user", resourceName.getAllValues().get(0));
        assertEquals("1", resourceId.getAllValues().get(1));
        assertEquals("1", resourceId.getAllValues().get(2));

        String recorded = String.join("|", actor.getAllValues())
                + String.join("|", detail.getAllValues())
                + metadata.getAllValues().stream().filter(v -> v != null).reduce("", String::concat);
        assertFalse(recorded.contains(password));
        assertFalse(recorded.contains("wrong-secret"));
        assertFalse(recorded.contains(login.token()));
        assertFalse(recorded.contains("token_hash"));
    }

    private static JdbcTemplate database() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:auth_audit_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE forge_users(id BIGINT PRIMARY KEY,username VARCHAR(120),display_name VARCHAR(160),password_hash VARCHAR(500),active BOOLEAN,created_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE forge_groups(id BIGINT PRIMARY KEY,name VARCHAR(120),description VARCHAR(500),created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE forge_user_groups(user_id BIGINT,group_id BIGINT)");
        jdbc.execute("CREATE TABLE forge_user_roles(user_id BIGINT,role_name VARCHAR(80))");
        jdbc.execute("CREATE TABLE forge_group_roles(group_id BIGINT,role_name VARCHAR(80))");
        jdbc.execute("CREATE TABLE forge_sessions(token_hash VARCHAR(128) PRIMARY KEY,user_id BIGINT,created_at TIMESTAMP,expires_at TIMESTAMP,last_seen_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE forge_api_tokens(id VARCHAR(36),user_id BIGINT,name VARCHAR(160),token_hash VARCHAR(120),token_prefix VARCHAR(24),created_at TIMESTAMP,expires_at TIMESTAMP,last_used_at TIMESTAMP,revoked_at TIMESTAMP)");
        return jdbc;
    }
}
