-- V77 — Tester-first self-service test data (SELF_SERVICE_TEST_DATA_BLUEPRINT)
--
-- Turns a plain-language request ("a customer with a $100 DDA and an active mortgage") into
-- provisioned, linked records plus a plain-language receipt — without the tester touching tables,
-- blueprints, or policies.
--
-- td_recipe   — the business-asset catalog (what a tester can ask for, in business terms).
-- td_request  — one tester request: the text, the interpreted plan, and the provisioning receipt.

CREATE TABLE td_recipe (
    id             BIGSERIAL PRIMARY KEY,
    recipe_key     VARCHAR(64) NOT NULL UNIQUE,   -- CUSTOMER | DDA | MORTGAGE ...
    name           VARCHAR(120) NOT NULL,          -- "DDA account"
    description    TEXT,
    asset_type     VARCHAR(32) NOT NULL,           -- CUSTOMER (anchor) | ACCOUNT | LOAN
    keywords       TEXT NOT NULL,                  -- comma-separated match terms
    attributes_json TEXT,                          -- editable attribute vocabulary (name/label/type/default/options)
    anchor         BOOLEAN NOT NULL DEFAULT FALSE, -- the root business object (Customer)
    sort_order     INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE td_request (
    id             BIGSERIAL PRIMARY KEY,
    request_text   TEXT NOT NULL,
    environment    VARCHAR(32) NOT NULL DEFAULT 'SIT',
    purpose        VARCHAR(200),
    quantity       INT NOT NULL DEFAULT 1,
    status         VARCHAR(16) NOT NULL DEFAULT 'PLANNED',  -- PLANNED | READY | FAILED
    plan_json      TEXT,
    receipt_json   TEXT,
    error          TEXT,
    owner_user_id  BIGINT,
    owner_username VARCHAR(128),
    owner_group_id BIGINT,
    visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_td_request_owner ON td_request (owner_username, created_at DESC);

-- Seed the starter catalog (Customer anchor + DDA + Mortgage), enough for the blueprint's worked example.
INSERT INTO td_recipe (recipe_key, name, description, asset_type, keywords, attributes_json, anchor, sort_order) VALUES
('CUSTOMER', 'Customer', 'A retail banking customer profile (the anchor everything links to).', 'CUSTOMER',
 'customer,client,party,profile,account holder', '[]', TRUE, 0),
('DDA', 'DDA account', 'A Demand Deposit Account (checking/current account).', 'ACCOUNT',
 'dda,demand deposit,checking,current account,deposit account',
 '[{"name":"balance","label":"Balance","type":"money","default":"0.00"},{"name":"status","label":"Status","type":"enum","options":["OPEN","DORMANT","CLOSED"],"default":"OPEN"}]',
 FALSE, 1),
('MORTGAGE', 'Mortgage loan', 'A home mortgage loan associated with the customer.', 'LOAN',
 'mortgage,home loan,housing loan',
 '[{"name":"status","label":"Status","type":"enum","options":["ACTIVE","CLOSED","DELINQUENT"],"default":"ACTIVE"},{"name":"principal","label":"Principal","type":"money","default":"250000.00"}]',
 FALSE, 2);
