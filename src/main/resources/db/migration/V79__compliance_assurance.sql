-- V79 — Compliance assurance: prove masking coverage, prove absence of real PII,
--       register approved exceptions, and answer subject-erasure (RTBF) requests.
--
-- Design notes
--   * A scan is a run header; findings are its rows. Findings NEVER store a raw PII value —
--     only a salted hash prefix, a pattern label and counts. That keeps the evidence trail
--     itself free of the data it is protecting.
--   * The exception register records approved prod-data-in-non-prod with an expiry, so an
--     expired exception becomes a finding instead of quietly becoming permanent.
--   * All three tables carry the standard ownership/visibility tenancy columns.

CREATE TABLE IF NOT EXISTS compliance_scan (
    id                  BIGSERIAL PRIMARY KEY,
    scan_type           VARCHAR(32)  NOT NULL,        -- COVERAGE | LEAK | CARDINALITY | SUBJECT | FULL
    name                VARCHAR(200),
    environment         VARCHAR(64),
    target_data_source_id BIGINT,
    source_data_source_id BIGINT,
    policy_id           BIGINT,
    schema_name         VARCHAR(128),
    subject_value_hash  VARCHAR(64),                  -- SUBJECT scans: salted hash, never the raw value
    status              VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',  -- RUNNING | DONE | FAILED
    result              VARCHAR(16),                  -- PASS | WARN | FAIL
    columns_scanned     INTEGER      NOT NULL DEFAULT 0,
    rows_scanned        BIGINT       NOT NULL DEFAULT 0,
    fail_count          INTEGER      NOT NULL DEFAULT 0,
    warn_count          INTEGER      NOT NULL DEFAULT 0,
    summary             VARCHAR(2000),
    error               VARCHAR(2000),
    evidence_json       TEXT,
    started_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at         TIMESTAMP,
    owner_user_id       BIGINT,
    owner_username      VARCHAR(128),
    owner_group_id      BIGINT,
    visibility          VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE'
);

CREATE INDEX IF NOT EXISTS idx_compliance_scan_type    ON compliance_scan (scan_type);
CREATE INDEX IF NOT EXISTS idx_compliance_scan_started ON compliance_scan (started_at DESC);
CREATE INDEX IF NOT EXISTS idx_compliance_scan_target  ON compliance_scan (target_data_source_id);

CREATE TABLE IF NOT EXISTS compliance_finding (
    id              BIGSERIAL PRIMARY KEY,
    scan_id         BIGINT       NOT NULL REFERENCES compliance_scan (id) ON DELETE CASCADE,
    severity        VARCHAR(16)  NOT NULL,            -- FAIL | WARN | INFO
    check_name      VARCHAR(48)  NOT NULL,            -- COVERAGE | LEAK_PATTERN | LEAK_MATCH | CARDINALITY | UNIQUENESS | EXCEPTION_EXPIRED | SUBJECT_PRESENT | CROSSWALK
    schema_name     VARCHAR(128),
    table_name      VARCHAR(128),
    column_name     VARCHAR(128),
    pii_type        VARCHAR(64),
    affected_rows   BIGINT       NOT NULL DEFAULT 0,
    detail          VARCHAR(2000) NOT NULL,
    remediation     VARCHAR(1000),
    evidence_hash   VARCHAR(64),                      -- salted hash of a witness value (never the value)
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_compliance_finding_scan     ON compliance_finding (scan_id);
CREATE INDEX IF NOT EXISTS idx_compliance_finding_severity ON compliance_finding (severity);

CREATE TABLE IF NOT EXISTS pii_exception (
    id                BIGSERIAL PRIMARY KEY,
    data_source_id    BIGINT       NOT NULL,
    environment       VARCHAR(64)  NOT NULL,
    scope             VARCHAR(400) NOT NULL,          -- e.g. "schema.table.column" or "whole schema"
    pii_type          VARCHAR(64),
    justification     VARCHAR(2000) NOT NULL,
    compensating_controls VARCHAR(2000),
    requested_by      VARCHAR(128) NOT NULL,
    approved_by       VARCHAR(128),
    approved_at       TIMESTAMP,
    expires_at        TIMESTAMP    NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING | APPROVED | REJECTED | REVOKED | EXPIRED
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP,
    owner_user_id     BIGINT,
    owner_username    VARCHAR(128),
    owner_group_id    BIGINT,
    visibility        VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE'
);

CREATE INDEX IF NOT EXISTS idx_pii_exception_source ON pii_exception (data_source_id);
CREATE INDEX IF NOT EXISTS idx_pii_exception_status ON pii_exception (status);
CREATE INDEX IF NOT EXISTS idx_pii_exception_expiry ON pii_exception (expires_at);
