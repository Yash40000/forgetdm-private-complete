-- Standalone schema monitors reuse the versioned drift evidence tables without
-- appearing as provisioning blueprints in DataScope.
ALTER TABLE dataset_definitions
    ADD COLUMN drift_monitor_only BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_dataset_drift_monitor
    ON dataset_definitions(drift_monitor_only, data_source_id, schema_name);
