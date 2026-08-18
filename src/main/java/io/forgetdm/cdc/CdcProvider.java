package io.forgetdm.cdc;

import io.forgetdm.datasource.DataSourceEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine-specific transaction-log capture. One implementation per source family
 * (PostgreSQL logical replication, Oracle LogMiner, and IBM Db2 SQL Replication).
 */
public interface CdcProvider {

    /** True if this provider can capture from the given data source. */
    boolean supports(DataSourceEntity ds);

    /** Human name of the log mechanism, e.g. "PostgreSQL logical replication". */
    String mechanism();

    /** Data-source-aware mechanism name for families such as Db2 LUW and Db2 for z/OS. */
    default String mechanism(DataSourceEntity ds) { return mechanism(); }

    /** Short plugin/engine identifier stored on the capture, e.g. "test_decoding" or "logminer". */
    String pluginName();

    /** Check the source is configured for log-based CDC before any slot is created. */
    Preflight preflight(DataSourceEntity ds);

    /** Check readiness using capture-definition options that must remain stable after enablement. */
    default Preflight preflight(DataSourceEntity ds, CaptureOptions options) { return preflight(ds); }

    /** Validate that an engine-specific capture definition covers the requested schema/tables. */
    default void validateScope(DataSourceEntity ds, String schema, List<String> tables) { }

    default void validateScope(DataSourceEntity ds, String schema, List<String> tables,
                               CaptureOptions options) {
        validateScope(ds, schema, tables);
    }

    /**
     * Current transaction-log position (Postgres LSN / Oracle SCN / Db2 commit sequence) read cheaply, without creating a
     * slot — used to pin a source's consistency point during a coordinated snapshot. Null if the
     * position can't be read.
     */
    default String currentLogPosition(DataSourceEntity ds) { return null; }

    default String currentLogPosition(DataSourceEntity ds, CdcCaptureEntity capture) {
        return currentLogPosition(ds);
    }

    /**
     * How far the confirmed checkpoint lags the current log position — Postgres: WAL bytes;
     * Oracle: SCN delta; Db2: committed UOW count. Null if it can't be computed.
     */
    default Long lag(DataSourceEntity ds, String confirmedPosition) { return null; }

    default Long lag(DataSourceEntity ds, CdcCaptureEntity capture, String confirmedPosition) {
        return lag(ds, confirmedPosition);
    }

    /** Unit returned by {@link #lag(DataSourceEntity, String)}. */
    default String lagUnit() { return "positions"; }

    /** Create (idempotently) the replication slot; returns the starting LSN checkpoint. */
    SlotInfo createSlot(DataSourceEntity ds, String slotName);

    default SlotInfo createSlot(DataSourceEntity ds, String slotName, CaptureOptions options) {
        return createSlot(ds, slotName);
    }

    /** Drop the replication slot so the server can reclaim WAL. */
    void dropSlot(DataSourceEntity ds, String slotName);

    default void dropSlot(DataSourceEntity ds, CdcCaptureEntity capture) {
        dropSlot(ds, capture.getSlotName());
    }

    /**
     * Read pending changes from the log starting at {@code capture.confirmedLsn}, up to
     * {@code maxChanges} rows or {@code budgetMillis}, then confirm/flush the new LSN so
     * the server can advance the slot. Only rows actually altered are returned — no rescan.
     */
    PollResult poll(DataSourceEntity ds, CdcCaptureEntity capture, int maxChanges, long budgetMillis);

    // ---------------------------------------------------------------- value types

    record Preflight(boolean ok, String logLevel, boolean privileged, List<String> messages) {}

    record CaptureOptions(String controlSchema) {}

    record SlotInfo(String slotName, String restartLsn, String confirmedLsn) {}

    /** A single decoded row change. {@code pk} is best-effort (primary-key columns only). */
    final class DecodedChange {
        public String lsn;
        public Long xid;
        public String schema;
        public String table;
        public String op;               // I | U | D
        public Map<String, String> pk = new LinkedHashMap<>();
        public Map<String, String> values = new LinkedHashMap<>();
    }

    record PollResult(List<DecodedChange> changes, String confirmedLsn, boolean reachedEnd) {}
}
