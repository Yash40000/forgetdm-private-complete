-- Governed cross-domain masking. File fields bind to the same policy rules used by
-- relational columns; each job file also carries an immutable executable snapshot.

CREATE TABLE datascope_mainframe_field_mappings (
  id             BIGSERIAL PRIMARY KEY,
  asset_id       BIGINT NOT NULL REFERENCES datascope_mainframe_assets(id) ON DELETE CASCADE,
  policy_id      BIGINT NOT NULL REFERENCES masking_policies(id) ON DELETE CASCADE,
  policy_rule_id BIGINT NOT NULL REFERENCES masking_rules(id) ON DELETE RESTRICT,
  field_path     VARCHAR(300) NOT NULL,
  ordinal_no     INTEGER NOT NULL DEFAULT 0,
  created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_ds_mf_field_mapping UNIQUE (asset_id, policy_id, field_path)
);

CREATE INDEX idx_ds_mf_field_mapping_asset_policy
  ON datascope_mainframe_field_mappings(asset_id, policy_id, ordinal_no, id);

ALTER TABLE mf_jobs ADD COLUMN policy_id BIGINT REFERENCES masking_policies(id);

ALTER TABLE mf_job_files ADD COLUMN asset_id BIGINT REFERENCES datascope_mainframe_assets(id) ON DELETE SET NULL;
ALTER TABLE mf_job_files ADD COLUMN mask_plan_json TEXT;
ALTER TABLE mf_job_files ADD COLUMN mapping_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_mf_jobs_policy ON mf_jobs(policy_id);
CREATE INDEX idx_mf_job_files_asset ON mf_job_files(asset_id);
