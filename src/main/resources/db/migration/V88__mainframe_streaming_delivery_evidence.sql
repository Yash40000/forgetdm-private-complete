-- Per-file restart, integrity, and publish evidence for bounded-memory execution.
ALTER TABLE mf_job_files ADD COLUMN records_processed BIGINT NOT NULL DEFAULT 0;
ALTER TABLE mf_job_files ADD COLUMN checkpoint_record BIGINT NOT NULL DEFAULT 0;
ALTER TABLE mf_job_files ADD COLUMN input_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE mf_job_files ADD COLUMN output_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE mf_job_files ADD COLUMN input_sha256 VARCHAR(64);
ALTER TABLE mf_job_files ADD COLUMN output_sha256 VARCHAR(64);
ALTER TABLE mf_job_files ADD COLUMN source_version VARCHAR(300);
ALTER TABLE mf_job_files ADD COLUMN target_version VARCHAR(300);
ALTER TABLE mf_job_files ADD COLUMN staging_name VARCHAR(400);
ALTER TABLE mf_job_files ADD COLUMN started_at TIMESTAMP;
ALTER TABLE mf_job_files ADD COLUMN finished_at TIMESTAMP;
