package io.forgetdm.compliance;

import io.forgetdm.vault.MaskingSecretResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Salted, one-way hashing for compliance evidence.
 *
 * <p>The whole point of a leak scan is to compare production values against a non-production
 * target, but the evidence it produces must never itself contain PII, or the audit trail becomes
 * the next breach. Every value is reduced to
 * {@code SHA-256(context | secret | normalized(value))}:
 *
 * <ul>
 *   <li>Comparisons stay exact: identical inputs hash identically, so a leaked value is found.</li>
 *   <li>The stored witness is irreversible and salted with the deployment's masking secret.</li>
 *   <li>Values are normalized before hashing so cosmetic separators do not hide a match.</li>
 * </ul>
 */
@Component
public class ComplianceHasher {

    private static final String CONTEXT = "ForgeTDM/Compliance/v1\0";

    private final MaskingSecretResolver secrets;

    public ComplianceHasher(MaskingSecretResolver secrets) {
        this.secrets = secrets;
    }

    /** Full 64-hex salted digest of a value, or null when the value is absent. */
    public String hash(String value) {
        String normalized = normalize(value);
        if (normalized == null) return null;
        return digest(normalized);
    }

    /**
     * A shortened digest for display in a finding. It lets an auditor match witnesses without
     * seeing either source value.
     */
    public String witness(String value) {
        String full = hash(value);
        return full == null ? null : full.substring(0, 16);
    }

    /**
     * Canonical form used before hashing: trimmed, upper-cased, and stripped of separators that
     * vary cosmetically between systems.
     */
    public static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        String compact = trimmed.replaceAll("[\\s\\-()./]", "").toUpperCase(Locale.ROOT);
        return compact.isEmpty() ? null : compact;
    }

    private String digest(String normalized) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(CONTEXT.getBytes(StandardCharsets.UTF_8));
            md.update(salt().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable for compliance hashing", e);
        }
    }

    private String salt() {
        try {
            String secret = secrets.resolve().secret();
            return secret == null || secret.isBlank() ? "forgetdm-compliance-default-salt" : secret;
        } catch (RuntimeException e) {
            // A missing vault must not stop a compliance scan.
            return "forgetdm-compliance-default-salt";
        }
    }
}
