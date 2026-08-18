package io.forgetdm.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.datasource.DataSourceEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Db2SqlReplicationCdcProviderTest {

    private final Db2SqlReplicationCdcProvider provider =
            new Db2SqlReplicationCdcProvider("ASN", new ObjectMapper());

    @Test
    void supportsDb2LuwUdbAndDb2Zos() {
        assertThat(provider.supports(source("DB2", "jdbc:db2://localhost:50000/FORGETDM"))).isTrue();
        assertThat(provider.supports(source("DB2UDB", "jdbc:db2://localhost:50000/FORGETDM"))).isTrue();
        assertThat(provider.supports(source("DB2LUW", "jdbc:db2://localhost:50000/FORGETDM"))).isTrue();
        DataSourceEntity zos = source("DB2ZOS", "jdbc:db2://mainframe:446/QIB");
        assertThat(provider.supports(zos)).isTrue();
        assertThat(provider.mechanism(zos)).contains("z/OS", "CD tables");
        assertThat(provider.supports(source("POSTGRES", "jdbc:postgresql://localhost/test"))).isFalse();
    }

    @Test
    void acceptsOnlyCommittedRowOperations() {
        assertThat(Db2SqlReplicationCdcProvider.normalizeOperation("i")).isEqualTo("I");
        assertThat(Db2SqlReplicationCdcProvider.normalizeOperation(" U ")).isEqualTo("U");
        assertThat(Db2SqlReplicationCdcProvider.normalizeOperation("D")).isEqualTo("D");
        assertThat(Db2SqlReplicationCdcProvider.normalizeOperation("B")).isNull();
        assertThat(Db2SqlReplicationCdcProvider.normalizeOperation(null)).isNull();
    }

    @Test
    void validatesNativeDb2CommitSequenceShape() {
        assertThat(Db2SqlReplicationCdcProvider.isCommitSequence(
                "0000000000064C370000000000000000")).isTrue();
        assertThat(Db2SqlReplicationCdcProvider.isCommitSequence("64C37")).isFalse();
        assertThat(Db2SqlReplicationCdcProvider.isCommitSequence(null)).isFalse();
    }

    @Test
    void normalizesAndValidatesPerSourceCaptureSchema() {
        assertThat(Db2SqlReplicationCdcProvider.normalizeControlSchema("asnqib", "ASN"))
                .isEqualTo("ASNQIB");
        assertThat(Db2SqlReplicationCdcProvider.normalizeControlSchema("", "ASN"))
                .isEqualTo("ASN");
        assertThatThrownBy(() -> Db2SqlReplicationCdcProvider.normalizeControlSchema("ASN; DROP", "ASN"))
                .hasMessageContaining("control schema");
    }

    private static DataSourceEntity source(String kind, String jdbcUrl) {
        DataSourceEntity source = new DataSourceEntity();
        source.setKind(kind);
        source.setJdbcUrl(jdbcUrl);
        return source;
    }
}
