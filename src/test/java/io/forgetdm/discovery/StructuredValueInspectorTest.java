package io.forgetdm.discovery;

import io.forgetdm.core.temenos.TemenosCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredValueInspectorTest {

    @Test
    void decodesTemenosMarksAndKeyValueLeavesWithLogicalPaths() {
        String value = "RECID=CUSTOMER-1" + TemenosCodec.FM
                + "NAME=Jordan Mercer" + TemenosCodec.FM
                + "ID.NO=28123456789" + TemenosCodec.VM + "P123456789";

        StructuredValueInspector.Inspection inspection = StructuredValueInspector.inspect(value);

        assertEquals(StructuredValueInspector.Format.TEMENOS, inspection.format());
        assertEquals(List.of("$/FM[1]/RECID", "$/FM[2]/NAME", "$/FM[3]/VM[1]/ID.NO",
                        "$/FM[3]/VM[2]/ID.NO"),
                inspection.leaves().stream().map(StructuredValueInspector.Leaf::path).toList());
        assertEquals("full_name", inspection.leaves().get(1).semanticName());
        assertEquals("national_id", inspection.leaves().get(2).semanticName());
        assertEquals("national_id", inspection.leaves().get(3).semanticName());
        assertFalse(inspection.truncated());
    }

    @Test
    void securelyExtractsXmlTextAndAttributesWithoutExpandingDoctype() {
        String xml = "<Document><Party role=\"debtor\"><EmailAddress>jordan@example.test</EmailAddress>"
                + "<PhoneNumber>+97455551234</PhoneNumber></Party></Document>";

        StructuredValueInspector.Inspection inspection = StructuredValueInspector.inspect(xml);

        assertEquals(StructuredValueInspector.Format.XML, inspection.format());
        assertTrue(inspection.leaves().stream().anyMatch(leaf -> leaf.path().endsWith("/@role")
                && leaf.value().equals("debtor")));
        assertTrue(inspection.leaves().stream().anyMatch(leaf -> leaf.semanticName().equals("email")
                && leaf.value().equals("jordan@example.test")));

        String xxe = "<!DOCTYPE x [<!ENTITY ext SYSTEM \"file:///does-not-read\">]><x>&ext;</x>";
        StructuredValueInspector.Inspection rejected = StructuredValueInspector.inspect(xxe);
        assertEquals(StructuredValueInspector.Format.SCALAR, rejected.format());
        assertTrue(rejected.leaves().get(0).value().contains("DOCTYPE"));
    }
}
