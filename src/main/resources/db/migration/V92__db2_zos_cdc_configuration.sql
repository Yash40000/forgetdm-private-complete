ALTER TABLE cdc_capture
    ADD COLUMN IF NOT EXISTS control_schema VARCHAR(128);

COMMENT ON COLUMN cdc_capture.control_schema IS
    'IBM SQL Replication Capture control schema (for example ASN); persisted per Db2 LUW or z/OS source.';
