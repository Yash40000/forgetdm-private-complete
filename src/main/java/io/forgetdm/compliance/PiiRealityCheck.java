package io.forgetdm.compliance;

import io.forgetdm.core.util.Luhn;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * "Does this value look like <em>real</em> production PII?"
 *
 * <p>This is the difference between a sampled comparison and a proof of absence. Comparing a target
 * against a source can only speak for the rows it sampled; asking "is any value in this column a
 * structurally valid, checksum-passing, real-world identifier?" can be run over the <em>whole</em>
 * column with no access to production at all.
 *
 * <p>Every check is deliberately stricter than the discovery-time regex. A masked SSN like
 * {@code 000-00-0000} matches the SSN shape but is not issuable, and a random 16-digit string is not
 * a card number unless it passes Luhn. Only values that would be valid in the real world are
 * reported — which keeps false positives low enough for the result to be trusted as evidence.
 */
public final class PiiRealityCheck {
    private PiiRealityCheck() {}

    private static final Pattern SSN = Pattern.compile("^(\\d{3})(\\d{2})(\\d{4})$");
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@([\\w-]+\\.)+[A-Za-z]{2,}$");
    private static final Pattern IBAN = Pattern.compile("^[A-Z]{2}\\d{2}[A-Z0-9]{11,30}$");
    private static final Pattern NANP = Pattern.compile("^1?([2-9]\\d{2})([2-9]\\d{2})(\\d{4})$");
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");

    /** Domains and TLDs reserved for testing — an email on one of these is safe by construction. */
    private static final Set<String> SAFE_TLDS = Set.of("test", "invalid", "example", "localhost", "local");
    private static final Set<String> SAFE_DOMAINS = Set.of("example.com", "example.org", "example.net");

    /** PII types this class can make a real/not-real judgement about. */
    public static boolean supports(String piiType) {
        return switch (upper(piiType)) {
            case "SSN", "NATIONAL_ID", "TAX_ID", "CREDIT_CARD", "IBAN", "BANK_ACCOUNT",
                 "EMAIL", "PHONE", "FAX", "ROUTING" -> true;
            default -> false;
        };
    }

    /**
     * True when {@code raw} is a structurally valid, real-world instance of {@code piiType}.
     * Unsupported types return false — this method never guesses.
     */
    public static boolean looksReal(String piiType, String raw) {
        if (raw == null || raw.isBlank()) return false;
        String type = upper(piiType);
        return switch (type) {
            case "SSN", "NATIONAL_ID", "TAX_ID" -> realSsn(raw);
            case "CREDIT_CARD" -> realCard(raw);
            case "IBAN", "BANK_ACCOUNT" -> realIban(raw);
            case "EMAIL" -> realEmail(raw);
            case "PHONE", "FAX" -> realPhone(raw);
            case "ROUTING" -> realRouting(raw);
            default -> false;
        };
    }

    /** Human-readable reason a value was flagged, for the finding detail. */
    public static String reason(String piiType) {
        return switch (upper(piiType)) {
            case "SSN", "NATIONAL_ID", "TAX_ID" -> "issuable SSN (valid area/group/serial)";
            case "CREDIT_CARD" -> "Luhn-valid card number";
            case "IBAN", "BANK_ACCOUNT" -> "IBAN passing the mod-97 check";
            case "EMAIL" -> "email on a deliverable (non-reserved) domain";
            case "PHONE", "FAX" -> "assignable NANP telephone number";
            case "ROUTING" -> "ABA routing number passing its checksum";
            default -> "real-world-valid identifier";
        };
    }

    // ------------------------------------------------------------------ checks

    /**
     * A US SSN that the SSA could actually have issued: area not 000, 666 or 900-999;
     * group not 00; serial not 0000. Masked placeholders fail all of these.
     */
    static boolean realSsn(String raw) {
        String d = digitsOnly(raw);
        var m = SSN.matcher(d);
        if (!m.matches()) return false;
        int area = Integer.parseInt(m.group(1));
        int group = Integer.parseInt(m.group(2));
        int serial = Integer.parseInt(m.group(3));
        if (area == 0 || area == 666 || area >= 900) return false;
        if (group == 0 || serial == 0) return false;
        // A run of one repeated digit (111111111) is a placeholder, never a real issue.
        return d.chars().distinct().count() != 1;
    }

    static boolean realCard(String raw) {
        String d = digitsOnly(raw);
        if (!DIGITS.matcher(d).matches() || d.length() < 13 || d.length() > 19) return false;
        if (d.chars().distinct().count() == 1) return false;
        return Luhn.isValid(d);
    }

    /** IBAN check digits: rearrange, letters to numbers, mod 97 must equal 1. */
    static boolean realIban(String raw) {
        String compact = raw.replaceAll("[\\s\\-]", "").toUpperCase(Locale.ROOT);
        if (!IBAN.matcher(compact).matches()) return false;
        String rearranged = compact.substring(4) + compact.substring(0, 4);
        int remainder = 0;
        for (char c : rearranged.toCharArray()) {
            String digits = Character.isLetter(c) ? String.valueOf(c - 'A' + 10) : String.valueOf(c);
            for (char digit : digits.toCharArray()) {
                if (digit < '0' || digit > '9') return false;
                remainder = (remainder * 10 + (digit - '0')) % 97;
            }
        }
        return remainder == 1;
    }

    /** Deliverable email: valid shape, and NOT on a TLD/domain reserved for testing. */
    static boolean realEmail(String raw) {
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(v).matches()) return false;
        String domain = v.substring(v.indexOf('@') + 1);
        if (SAFE_DOMAINS.contains(domain)) return false;
        int dot = domain.lastIndexOf('.');
        String tld = dot < 0 ? "" : domain.substring(dot + 1);
        return !SAFE_TLDS.contains(tld);
    }

    /** NANP number with an assignable area code and exchange (neither may start with 0 or 1). */
    static boolean realPhone(String raw) {
        var m = NANP.matcher(digitsOnly(raw));
        if (!m.matches()) return false;
        // 555 is the reserved fictional range — safe by construction.
        return !(m.group(1).equals("555") || m.group(2).equals("555"));
    }

    static boolean realRouting(String raw) {
        String d = digitsOnly(raw);
        if (d.length() != 9 || d.chars().distinct().count() == 1) return false;
        int[] w = {3, 7, 1, 3, 7, 1, 3, 7, 1};
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += (d.charAt(i) - '0') * w[i];
        return sum % 10 == 0;
    }

    // ------------------------------------------------------------------ utils

    private static String digitsOnly(String s) { return s.replaceAll("\\D", ""); }

    private static String upper(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT); }
}
