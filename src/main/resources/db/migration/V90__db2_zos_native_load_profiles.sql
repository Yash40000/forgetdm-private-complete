CREATE TABLE db2_zos_load_profiles (
  id                       BIGSERIAL PRIMARY KEY,
  data_source_id           BIGINT NOT NULL UNIQUE REFERENCES data_sources(id) ON DELETE CASCADE,
  mainframe_connection_id  BIGINT NOT NULL REFERENCES mf_connections(id),
  subsystem                VARCHAR(8) NOT NULL,
  work_hlq                 VARCHAR(26) NOT NULL,
  procedure_name           VARCHAR(8) NOT NULL DEFAULT 'DSNUPROC',
  job_class                VARCHAR(1) NOT NULL DEFAULT 'A',
  message_class            VARCHAR(1) NOT NULL DEFAULT 'X',
  job_accounting           VARCHAR(64),
  work_unit                VARCHAR(8) NOT NULL DEFAULT 'SYSDA',
  logging_mode             VARCHAR(24) NOT NULL DEFAULT 'RECOVERABLE',
  max_return_code          INTEGER NOT NULL DEFAULT 0,
  poll_seconds             INTEGER NOT NULL DEFAULT 5,
  timeout_seconds          INTEGER NOT NULL DEFAULT 3600,
  cleanup_remote           BOOLEAN NOT NULL DEFAULT TRUE,
  created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_db2_zos_load_profile_connection
  ON db2_zos_load_profiles(mainframe_connection_id);
