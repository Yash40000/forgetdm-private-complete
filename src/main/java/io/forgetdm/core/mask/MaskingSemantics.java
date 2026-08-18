package io.forgetdm.core.mask;

import java.util.Locale;
import java.util.Map;

/** Canonical logical salts and evaluation order shared by every masking adapter. */
public final class MaskingSemantics {
    private static final Map<String, String> DEFAULT_SALTS = Map.ofEntries(
            Map.entry("FIRST_NAME", "name.first"),
            Map.entry("LAST_NAME", "name.last"),
            Map.entry("FULL_NAME", "name.full"),
            Map.entry("EMAIL", "email"),
            Map.entry("SSN", "ssn"),
            Map.entry("CREDIT_CARD", "ccn"),
            Map.entry("PHONE", "phone"),
            Map.entry("CITY_STATE_ZIP", "geo"),
            Map.entry("ADDRESS_STREET", "addr"),
            Map.entry("ADDRESS_US", "addr.us"),
            Map.entry("COMPANY", "company"),
            Map.entry("DOB_AGE_BAND", "dob"),
            Map.entry("BANK_ACCOUNT", "bank.account"),
            Map.entry("IBAN", "iban"),
            Map.entry("SWIFT_BIC", "swift.bic"),
            Map.entry("ABA_ROUTING", "routing.aba"),
            Map.entry("NATIONAL_ID", "national.id"),
            Map.entry("IP_ADDRESS", "network.ip"),
            Map.entry("MAC_ADDRESS", "network.mac"),
            Map.entry("DIRECT_LOOKUP", "lookup.direct"),
            Map.entry("HASH_LOOKUP", "lookup.hash")
    );

    private MaskingSemantics() { }

    public static String defaultSalt(String function) {
        if (function == null) return null;
        return DEFAULT_SALTS.get(function.trim().toUpperCase(Locale.ROOT));
    }

    public static String canonicalSalt(String function, String explicitSalt) {
        if (explicitSalt != null && !explicitSalt.isBlank()) return explicitSalt.trim();
        return defaultSalt(function);
    }

    /** Stable aliases consumed by context-dependent FULL_NAME and EMAIL transforms. */
    public static String contextAlias(String function) {
        if (function == null) return null;
        return switch (function.trim().toUpperCase(Locale.ROOT)) {
            case "FIRST_NAME" -> "first_name";
            case "LAST_NAME" -> "last_name";
            default -> null;
        };
    }

    public static int evaluationPriority(String function) {
        if (function == null) return 1;
        return switch (function.trim().toUpperCase(Locale.ROOT)) {
            case "FIRST_NAME", "LAST_NAME" -> 0;
            case "FULL_NAME", "EMAIL", "SCRIPT" -> 2;
            default -> 1;
        };
    }
}
