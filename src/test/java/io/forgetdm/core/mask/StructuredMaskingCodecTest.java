package io.forgetdm.core.mask;

import io.forgetdm.core.temenos.TemenosCodec;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredMaskingCodecTest {
    private final MaskingEngine engine = new MaskingEngine("structured-mask-test-secret");

    @Test
    void selectivelyMasksTemenosLeavesAndPreservesEveryMark() {
        String config = StructuredMaskingCodec.encode("TEMENOS", List.of(
                new StructuredMaskingCodec.RuleSpec("$/FM[*]/NAME", "FULL_NAME", "pii.full_name", "FIRST LAST", "PROPER"),
                new StructuredMaskingCodec.RuleSpec("$/FM[*]/VM[*]/ID.NO", "NATIONAL_ID", "pii.tax_id", "GENERIC", "PRESERVE_FORMAT")
        ));
        String original = "RECID=CUSTOMER-1" + TemenosCodec.FM
                + "NAME=Jordan Mercer" + TemenosCodec.FM
                + "ID.NO=28123456789" + TemenosCodec.VM + "STATUS=ACTIVE";

        String masked = engine.maskStructured(config, original, new MaskContext(1));

        assertTrue(masked.startsWith("RECID=CUSTOMER-1" + TemenosCodec.FM + "NAME="));
        assertTrue(masked.endsWith(TemenosCodec.VM + "STATUS=ACTIVE"));
        assertNotEquals(original, masked);
        assertEquals(count(original, TemenosCodec.FM), count(masked, TemenosCodec.FM));
        assertEquals(count(original, TemenosCodec.VM), count(masked, TemenosCodec.VM));
        assertEquals(count(original, TemenosCodec.SVM), count(masked, TemenosCodec.SVM));
    }

    @Test
    void masksEveryOccurrenceOfAKeyedMultiValueAndKeepsTheKeyOnce() {
        String config = StructuredMaskingCodec.encode("TEMENOS", List.of(
                new StructuredMaskingCodec.RuleSpec("$/FM[*]/VM[*]/ID.NO", "NATIONAL_ID",
                        "pii.tax_id", "GENERIC", "PRESERVE_FORMAT")
        ));
        String original = "RECID=CUSTOMER-1" + TemenosCodec.FM
                + "ID.NO=28123456789" + TemenosCodec.VM + "P123456789";

        String masked = engine.maskStructured(config, original, new MaskContext(1));

        String values = masked.substring(masked.indexOf("ID.NO=") + 6);
        String[] parts = values.split(String.valueOf(TemenosCodec.VM), -1);
        assertEquals(2, parts.length);
        assertNotEquals("28123456789", parts[0]);
        assertNotEquals("P123456789", parts[1]);
        assertEquals(1, masked.split("ID.NO=", -1).length - 1);
    }

    @Test
    void sameSemanticValueMasksIdenticallyAsScalarTemenosAndXml() throws Exception {
        String name = "Jordan Mercer";
        String scalar = engine.mask(MaskFunction.FULL_NAME, "pii.full_name", name,
                "FIRST LAST", "PROPER", new MaskContext(1));
        String temenosConfig = StructuredMaskingCodec.encode("TEMENOS", List.of(
                new StructuredMaskingCodec.RuleSpec("$/FM[*]/NAME", "FULL_NAME", "pii.full_name", "FIRST LAST", "PROPER")
        ));
        String temenos = engine.maskStructured(temenosConfig,
                "RECID=CUSTOMER-1" + TemenosCodec.FM + "NAME=" + name, new MaskContext(1));
        assertEquals(scalar, temenos.substring(temenos.indexOf("NAME=") + 5));

        String xmlConfig = StructuredMaskingCodec.encode("XML", List.of(
                new StructuredMaskingCodec.RuleSpec("$/Document[*]/Party[*]/Name[*]", "FULL_NAME",
                        "pii.full_name", "FIRST LAST", "PROPER")
        ));
        String xml = engine.maskStructured(xmlConfig,
                "<Document><Party><Name>Jordan Mercer</Name><Balance>125.50</Balance></Party></Document>",
                new MaskContext(1));
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(scalar, document.getElementsByTagName("Name").item(0).getTextContent());
        assertEquals("125.50", document.getElementsByTagName("Balance").item(0).getTextContent());
    }

    @Test
    void rejectsXmlEntityDeclarationsInsteadOfLeakingExternalContent() {
        String config = StructuredMaskingCodec.encode("XML", List.of(
                new StructuredMaskingCodec.RuleSpec("$/x[*]", "REDACT", "pii.text", null, null)
        ));
        assertThrows(IllegalArgumentException.class, () -> engine.maskStructured(config,
                "<!DOCTYPE x [<!ENTITY ext SYSTEM 'file:///secret'>]><x>&ext;</x>", new MaskContext(1)));
    }

    private static long count(String value, char mark) {
        return value.chars().filter(ch -> ch == mark).count();
    }
}
