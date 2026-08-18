ALTER TABLE mf_connections ADD COLUMN auth_type VARCHAR(20) NOT NULL DEFAULT 'BASIC';
ALTER TABLE mf_connections ADD COLUMN password_secret_ref VARCHAR(300);
