ALTER TABLE classifications
    ADD COLUMN IF NOT EXISTS structured_review TEXT;

COMMENT ON COLUMN classifications.structured_review IS
    'Per-logical-field PII status and masking recommendation for XML/Temenos structured columns';
