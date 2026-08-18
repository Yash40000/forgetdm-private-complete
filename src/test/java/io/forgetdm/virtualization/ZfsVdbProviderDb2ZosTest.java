package io.forgetdm.virtualization;

import io.forgetdm.datasource.DataSourceEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ZfsVdbProviderDb2ZosTest {

    @Test
    void recognizesDb2ZosWithoutMisclassifyingLuw() {
        assertThat(ZfsVdbProvider.isDb2Zos(source("DB2ZOS"))).isTrue();
        assertThat(ZfsVdbProvider.isDb2Zos(source("DB2_ZOS"))).isTrue();
        assertThat(ZfsVdbProvider.isDb2Zos(source("DB2UDB"))).isFalse();
        assertThat(ZfsVdbProvider.isDb2Zos(source("DB2LUW"))).isFalse();
    }

    private static DataSourceEntity source(String kind) {
        DataSourceEntity source = new DataSourceEntity();
        source.setKind(kind);
        return source;
    }
}
