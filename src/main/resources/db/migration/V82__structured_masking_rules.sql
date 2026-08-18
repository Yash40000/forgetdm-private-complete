ALTER TABLE masking_rules ADD COLUMN structured_config TEXT;
ALTER TABLE masking_rules ADD COLUMN semantic_salt VARCHAR(300);
