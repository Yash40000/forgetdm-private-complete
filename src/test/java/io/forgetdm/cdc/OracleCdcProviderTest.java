package io.forgetdm.cdc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleCdcProviderTest {

    @Test
    void oracle11GuidanceDoesNotRecommendUnsupportedLogMiningPrivilege() {
        String guidance = OracleCdcProvider.logMinerGrantGuidance(11, "TEMENOS_TDM");

        assertTrue(guidance.contains("Oracle 11g does not support GRANT LOGMINING"));
        assertTrue(guidance.contains("GRANT SELECT ANY TRANSACTION TO TEMENOS_TDM"));
        assertTrue(guidance.contains("GRANT SELECT ON SYS.V_$LOGMNR_CONTENTS TO TEMENOS_TDM"));
        assertFalse(guidance.contains("GRANT LOGMINING TO TEMENOS_TDM"));
    }

    @Test
    void modernOracleGuidanceIncludesLogMiningPrivilege() {
        String guidance = OracleCdcProvider.logMinerGrantGuidance(19, "CDC_USER");

        assertTrue(guidance.contains("GRANT LOGMINING TO CDC_USER"));
        assertTrue(guidance.contains("GRANT EXECUTE ON SYS.DBMS_LOGMNR TO CDC_USER"));
    }

    @Test
    void guidanceSanitizesTheConfiguredUsername() {
        String guidance = OracleCdcProvider.logMinerGrantGuidance(11, "CDC_USER; DROP USER X");

        assertFalse(guidance.contains("DROP USER"));
        assertTrue(guidance.contains("CDC_USERDROPUSERX"));
    }
}
