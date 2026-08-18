package io.forgetdm.compliance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The leak scan's credibility rests entirely on this class: it must flag values that could be real
 * production data, and must NOT flag the placeholders that correct masking produces. A false positive
 * here turns the whole compliance report into noise that gets ignored.
 */
class PiiRealityCheckTest {

    // ------------------------------------------------------------------- SSN

    @Test
    @DisplayName("issuable SSNs are flagged, in both dashed and bare form")
    void realSsnsAreFlagged() {
        assertTrue(PiiRealityCheck.looksReal("SSN", "123-45-6789"));
        assertTrue(PiiRealityCheck.looksReal("SSN", "123456789"));
        assertTrue(PiiRealityCheck.looksReal("SSN", " 078-05-1120 "));
    }

    @Test
    @DisplayName("SSNs the SSA cannot issue are not flagged — these are masking output, not leaks")
    void unissuableSsnsAreNotFlagged() {
        assertFalse(PiiRealityCheck.looksReal("SSN", "000-45-6789"), "area 000 is never issued");
        assertFalse(PiiRealityCheck.looksReal("SSN", "666-45-6789"), "area 666 is never issued");
        assertFalse(PiiRealityCheck.looksReal("SSN", "900-45-6789"), "area 900+ is never issued");
        assertFalse(PiiRealityCheck.looksReal("SSN", "123-00-6789"), "group 00 is never issued");
        assertFalse(PiiRealityCheck.looksReal("SSN", "123-45-0000"), "serial 0000 is never issued");
        assertFalse(PiiRealityCheck.looksReal("SSN", "111-11-1111"), "repeated digits are a placeholder");
        assertFalse(PiiRealityCheck.looksReal("SSN", "***-**-6789"), "redacted values are not real");
        assertFalse(PiiRealityCheck.looksReal("SSN", "12345"), "wrong length");
    }

    // ----------------------------------------------------------------- cards

    @Test
    @DisplayName("Luhn-valid card numbers are flagged; random digit strings are not")
    void cardsRequireLuhn() {
        assertTrue(PiiRealityCheck.looksReal("CREDIT_CARD", "4111111111111111"));
        assertTrue(PiiRealityCheck.looksReal("CREDIT_CARD", "4111 1111 1111 1111"));
        assertTrue(PiiRealityCheck.looksReal("CREDIT_CARD", "5500-0000-0000-0004"));

        assertFalse(PiiRealityCheck.looksReal("CREDIT_CARD", "4111111111111112"), "fails Luhn");
        assertFalse(PiiRealityCheck.looksReal("CREDIT_CARD", "1234567890123456"), "fails Luhn");
        assertFalse(PiiRealityCheck.looksReal("CREDIT_CARD", "4444444444444444"), "repeated digits");
        assertFalse(PiiRealityCheck.looksReal("CREDIT_CARD", "411111"), "too short");
    }

    // ------------------------------------------------------------------ IBAN

    @Test
    @DisplayName("IBANs are flagged only when the mod-97 check digits are correct")
    void ibanRequiresChecksum() {
        assertTrue(PiiRealityCheck.looksReal("IBAN", "GB82WEST12345698765432"));
        assertTrue(PiiRealityCheck.looksReal("IBAN", "gb82 west 1234 5698 7654 32".toUpperCase()));
        assertTrue(PiiRealityCheck.looksReal("IBAN", "DE89370400440532013000"));

        assertFalse(PiiRealityCheck.looksReal("IBAN", "GB82WEST12345698765433"), "wrong check digits");
        assertFalse(PiiRealityCheck.looksReal("IBAN", "XX00NOTANIBAN0000"), "not a valid IBAN");
    }

    // ----------------------------------------------------------------- email

    @Test
    @DisplayName("deliverable emails are flagged; reserved test domains are safe by construction")
    void emailsRespectReservedDomains() {
        assertTrue(PiiRealityCheck.looksReal("EMAIL", "jane.doe@acmebank.com"));
        assertTrue(PiiRealityCheck.looksReal("EMAIL", "j.doe+tag@sub.acmebank.co.uk"));

        assertFalse(PiiRealityCheck.looksReal("EMAIL", "jane.doe@acmebank.test"), ".test is reserved");
        assertFalse(PiiRealityCheck.looksReal("EMAIL", "jane@example.com"), "example.com is reserved");
        assertFalse(PiiRealityCheck.looksReal("EMAIL", "jane@host.invalid"), ".invalid is reserved");
        assertFalse(PiiRealityCheck.looksReal("EMAIL", "not-an-email"), "not an email at all");
    }

    // ----------------------------------------------------------------- phone

    @Test
    @DisplayName("assignable NANP numbers are flagged; the 555 fictional range is not")
    void phonesRespectFictionalRange() {
        assertTrue(PiiRealityCheck.looksReal("PHONE", "212-555-0000".replace("555", "664")));
        assertTrue(PiiRealityCheck.looksReal("PHONE", "(415) 664-1234"));

        assertFalse(PiiRealityCheck.looksReal("PHONE", "555-664-1234"), "555 area is fictional");
        assertFalse(PiiRealityCheck.looksReal("PHONE", "415-555-1234"), "555 exchange is fictional");
        assertFalse(PiiRealityCheck.looksReal("PHONE", "015-664-1234"), "area cannot start with 0");
        assertFalse(PiiRealityCheck.looksReal("PHONE", "12345"), "too short");
    }

    // --------------------------------------------------------------- routing

    @Test
    @DisplayName("ABA routing numbers are flagged only when the weighted checksum holds")
    void routingRequiresChecksum() {
        assertTrue(PiiRealityCheck.looksReal("ROUTING", "021000021"));
        assertFalse(PiiRealityCheck.looksReal("ROUTING", "021000022"), "checksum fails");
        assertFalse(PiiRealityCheck.looksReal("ROUTING", "111111111"), "repeated digits");
    }

    // ------------------------------------------------------------ guardrails

    @Test
    @DisplayName("unsupported types and empty values never produce a finding")
    void neverGuesses() {
        assertFalse(PiiRealityCheck.supports("FULL_NAME"));
        assertFalse(PiiRealityCheck.looksReal("FULL_NAME", "Jane Doe"));
        assertFalse(PiiRealityCheck.looksReal("GEOLOCATION", "51.5,-0.1"));
        assertFalse(PiiRealityCheck.looksReal("SSN", null));
        assertFalse(PiiRealityCheck.looksReal("SSN", "   "));
        assertFalse(PiiRealityCheck.looksReal(null, "123-45-6789"));
    }

    @Test
    @DisplayName("checksum-verifiable types are declared supported so the scanner probes them")
    void supportedTypes() {
        assertTrue(PiiRealityCheck.supports("SSN"));
        assertTrue(PiiRealityCheck.supports("CREDIT_CARD"));
        assertTrue(PiiRealityCheck.supports("IBAN"));
        assertTrue(PiiRealityCheck.supports("EMAIL"));
        assertTrue(PiiRealityCheck.supports("PHONE"));
        assertTrue(PiiRealityCheck.supports("ROUTING"));
    }

    // ------------------------------------------------- normalisation contract

    @Test
    @DisplayName("normalisation makes the same identity match across separator styles")
    void normalisationIsSeparatorInsensitive() {
        // This is what stops 123-45-6789 in one system and 123456789 in another from being
        // treated as different values by the hashed leak comparison.
        String a = ComplianceHasher.normalize("123-45-6789");
        String b = ComplianceHasher.normalize(" 123 456 789 ");
        org.junit.jupiter.api.Assertions.assertEquals(a, b);
        org.junit.jupiter.api.Assertions.assertNull(ComplianceHasher.normalize("   "));
        org.junit.jupiter.api.Assertions.assertNull(ComplianceHasher.normalize(null));
    }

    @Test
    @DisplayName("only DIRECT lookups are reversible — that is what puts a crosswalk in erasure scope")
    void reversibilityClassification() {
        assertTrue(SubjectErasureScanner.isReversibleFunction("DIRECT_LOOKUP"));
        assertFalse(SubjectErasureScanner.isReversibleFunction("HASH_LOOKUP"));
        assertFalse(SubjectErasureScanner.isReversibleFunction("SSN"));
        assertFalse(SubjectErasureScanner.isReversibleFunction(null));
    }

    @Test
    @DisplayName("masking functions map to the PII type the pattern scan should validate")
    void functionToPiiType() {
        org.junit.jupiter.api.Assertions.assertEquals("SSN", ComplianceScanner.piiTypeForFunction("SSN"));
        org.junit.jupiter.api.Assertions.assertEquals("CREDIT_CARD", ComplianceScanner.piiTypeForFunction("credit_card"));
        org.junit.jupiter.api.Assertions.assertEquals("ROUTING", ComplianceScanner.piiTypeForFunction("ABA_ROUTING"));
        org.junit.jupiter.api.Assertions.assertEquals("OTHER", ComplianceScanner.piiTypeForFunction("REDACT"));
        org.junit.jupiter.api.Assertions.assertEquals("OTHER", ComplianceScanner.piiTypeForFunction(null));
    }
}
