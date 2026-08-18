ALTER TABLE business_entity_members
    ADD COLUMN IF NOT EXISTS field_rules_json TEXT;
