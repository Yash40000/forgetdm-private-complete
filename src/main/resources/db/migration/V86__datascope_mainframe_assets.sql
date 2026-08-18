-- DataScope mainframe file assets.
--
-- A DataScope may be relational, mainframe-only, or hybrid. File contents are never stored in
-- PostgreSQL: these rows are control-plane metadata used to resolve and launch immutable mf_jobs.

ALTER TABLE dataset_definitions ADD COLUMN IF NOT EXISTS scope_kind VARCHAR(20) NOT NULL DEFAULT 'RELATIONAL';
ALTER TABLE dataset_definitions ALTER COLUMN data_source_id DROP NOT NULL;

CREATE TABLE datascope_mainframe_assets (
  id                       BIGSERIAL PRIMARY KEY,
  dataset_id               BIGINT NOT NULL REFERENCES dataset_definitions(id) ON DELETE CASCADE,
  logical_role             VARCHAR(120) NOT NULL,
  source_connection_id     BIGINT NOT NULL REFERENCES mf_connections(id),
  target_connection_id     BIGINT REFERENCES mf_connections(id),
  source_name_pattern      VARCHAR(400) NOT NULL,
  target_name_template     VARCHAR(400),
  copybook_id              BIGINT NOT NULL REFERENCES mf_copybooks(id),
  dsorg                    VARCHAR(20) NOT NULL DEFAULT 'PS',
  recfm                    VARCHAR(8) NOT NULL DEFAULT 'FB',
  lrecl                    INTEGER,
  code_page                VARCHAR(40),
  selection_mode           VARCHAR(20) NOT NULL DEFAULT 'ALL',
  key_field_paths          TEXT,
  entity_key_field_path    VARCHAR(300),
  filter_expression        TEXT,
  enabled                  BOOLEAN NOT NULL DEFAULT TRUE,
  ordinal_no               INTEGER NOT NULL DEFAULT 0,
  created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_datascope_mf_asset_role UNIQUE (dataset_id, logical_role),
  CONSTRAINT ck_datascope_mf_asset_recfm CHECK (recfm IN ('F', 'FB', 'V', 'VB')),
  CONSTRAINT ck_datascope_mf_asset_selection CHECK (selection_mode IN ('ALL', 'ENTITY_KEYS', 'FILTER'))
);

CREATE INDEX idx_datascope_mf_assets_dataset
    ON datascope_mainframe_assets(dataset_id, ordinal_no, id);
CREATE INDEX idx_datascope_mf_assets_source
    ON datascope_mainframe_assets(source_connection_id);
CREATE INDEX idx_datascope_mf_assets_copybook
    ON datascope_mainframe_assets(copybook_id);

-- Lineage from a physical mainframe job back to the DataScope / Business Entity orchestration.
ALTER TABLE mf_jobs ADD COLUMN IF NOT EXISTS dataset_id BIGINT REFERENCES dataset_definitions(id) ON DELETE SET NULL;
ALTER TABLE mf_jobs ADD COLUMN IF NOT EXISTS business_entity_id BIGINT REFERENCES business_entities(id) ON DELETE SET NULL;
ALTER TABLE mf_jobs ADD COLUMN IF NOT EXISTS execution_plan_id BIGINT;
ALTER TABLE mf_jobs ADD COLUMN IF NOT EXISTS run_group_id VARCHAR(120);
ALTER TABLE mf_jobs ADD COLUMN IF NOT EXISTS manifest_json TEXT;

CREATE INDEX IF NOT EXISTS idx_mf_jobs_datascope ON mf_jobs(dataset_id, created_at);
CREATE INDEX IF NOT EXISTS idx_mf_jobs_business_entity ON mf_jobs(business_entity_id, created_at);
CREATE INDEX IF NOT EXISTS idx_mf_jobs_run_group ON mf_jobs(run_group_id);
