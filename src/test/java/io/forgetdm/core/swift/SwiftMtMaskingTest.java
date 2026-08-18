package io.forgetdm.core.swift;

import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** SWIFT MT (FIN) structure-preserving masking (RFP §3.3). */
class SwiftMtMaskingTest {

    private final MaskingEngine engine = new MaskingEngine("unit-test-secret");
    private final MaskContext ctx = new MaskContext(1);

    private static final String MT103 = String.join("\r\n",
            "{1:F01BANKBEBBAXXX0000000000}{2:I103DEUTDEFFXXXXN}{4:",
            ":20:REF20240719",
            ":23B:CRED",
            ":32A:240719USD1500,00",
            ":50K:/12345678",
            "JOHN DOE",
            "123 MAIN STREET",
            ":59:/87654321",
            "JANE SMITH",
            ":70:INVOICE 998877",
            ":71A:SHA",
            "-}");

    private String mask(String msg) {
        return engine.mask(MaskFunction.SWIFT_MT, "swift.mt", msg, null, null, ctx);
    }

    @Test void preservesStructureAndFinancialFields() {
        String out = mask(MT103);

        assertTrue(SwiftMtCodec.isSwiftMt(out));
        List<SwiftMtCodec.Block> blocks = SwiftMtCodec.parse(out);
        assertEquals(List.of("1", "2", "4"), blocks.stream().map(b -> b.id).toList());

        // Amount, value date, currency, and bank-operation codes are left intact.
        assertTrue(out.contains(":32A:240719USD1500,00"), "value date / currency / amount must be preserved");
        assertTrue(out.contains(":23B:CRED"));
        assertTrue(out.contains(":71A:SHA"));
        assertTrue(out.contains(":70:INVOICE 998877"), "narrative left intact in this profile");
    }

    @Test void masksIdentifiersAndKeepsFormats() {
        String out = mask(MT103);

        // Original PII/identifiers are gone.
        for (String secret : List.of("JOHN DOE", "JANE SMITH", "12345678", "87654321", "BANKBEBB", "DEUTDEFF")) {
            assertFalse(out.contains(secret), "must not leak " + secret);
        }

        // Account numbers keep their 8-digit format.
        Matcher acct = Pattern.compile(":50K:/(\\d{8})\\b").matcher(out);
        assertTrue(acct.find(), "account keeps its length/format");
        assertNotEquals("12345678", acct.group(1));

        // Header BIC is still a valid 8-char bank code shape and the address length is unchanged.
        Matcher hdr = Pattern.compile("\\{1:F01([A-Z]{4}[A-Z]{2}[A-Z0-9]{2})AXXX0000000000\\}").matcher(out);
        assertTrue(hdr.find(), "block-1 LT address structure preserved with a valid masked BIC8");
        assertNotEquals("BANKBEBB", hdr.group(1));
    }

    @Test void isDeterministicForReferentialIntegrity() {
        assertEquals(mask(MT103), mask(MT103), "same message + salt → same masked output (RI across messages)");
    }

    @Test void nonSwiftValuePassesThrough() {
        String plain = "just a normal string";
        assertEquals(plain, engine.mask(MaskFunction.SWIFT_MT, "swift.mt", plain, null, null, ctx));
    }
}
