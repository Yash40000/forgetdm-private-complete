-- V78 — Make self-service recipes fully catalog-driven (no hardcoded asset logic).
--
-- Each recipe now carries:
--   attributes_json — typed, synonym-aware attribute vocabulary the interpreter extracts generically
--   backing_json    — how to provision it (table, id, business key, columns, link) so the provisioner
--                     is generic. Adding a new asset becomes a data row, not code.

ALTER TABLE td_recipe ADD COLUMN IF NOT EXISTS backing_json TEXT;

-- Anchor: Customer
UPDATE td_recipe SET
  attributes_json = '[]',
  backing_json = '{"table":"sst_customer","idColumn":"customer_id","idPrefix":"C","label":"full_name","businessKey":{"column":"cif","type":"varchar(20)","label":"CIF","gen":"digits:8"},"columns":[{"column":"full_name","type":"varchar(120)","gen":"fullname"}]}'
WHERE recipe_key = 'CUSTOMER';

-- DDA account
UPDATE td_recipe SET
  keywords = 'dda,demand deposit,checking,current account,deposit account,chequing',
  attributes_json = '[{"name":"balance","label":"Balance","type":"money","synonyms":["balance","amount"],"default":"0.00"},{"name":"status","label":"Status","type":"enum","options":["OPEN","DORMANT","CLOSED"],"default":"OPEN"}]',
  backing_json = '{"table":"sst_dda_account","idColumn":"account_id","idPrefix":"A","link":{"column":"customer_id"},"businessKey":{"column":"account_no","type":"varchar(24)","label":"Account No","gen":"prefixdigits:DDA:8"},"columns":[{"column":"balance","type":"numeric(18,2)","from":"balance","unit":"USD","default":"0.00"},{"column":"status","type":"varchar(16)","from":"status","default":"OPEN"},{"column":"opened_date","type":"date","gen":"today"}]}'
WHERE recipe_key = 'DDA';

-- Mortgage loan
UPDATE td_recipe SET
  attributes_json = '[{"name":"status","label":"Status","type":"enum","options":["ACTIVE","CLOSED","DELINQUENT"],"default":"ACTIVE"},{"name":"principal","label":"Principal","type":"money","synonyms":["principal","amount","loan amount"],"default":"250000.00"}]',
  backing_json = '{"table":"sst_mortgage_loan","idColumn":"loan_id","idPrefix":"L","link":{"column":"customer_id"},"businessKey":{"column":"loan_no","type":"varchar(24)","label":"Loan No","gen":"prefixdigits:MTG:8"},"columns":[{"column":"status","type":"varchar(16)","from":"status","default":"ACTIVE"},{"column":"principal","type":"numeric(18,2)","from":"principal","unit":"USD","default":"250000.00"},{"column":"rate","type":"varchar(8)","gen":"const:5.25%"}]}'
WHERE recipe_key = 'MORTGAGE';

-- New asset added as pure data (no code) — proves the catalog is generic.
INSERT INTO td_recipe (recipe_key, name, description, asset_type, keywords, attributes_json, anchor, sort_order, backing_json)
VALUES ('SAVINGS', 'Savings account', 'An interest-bearing savings account.', 'ACCOUNT',
  'savings,saving account,deposit savings',
  '[{"name":"balance","label":"Balance","type":"money","synonyms":["balance","amount"],"default":"0.00"},{"name":"status","label":"Status","type":"enum","options":["OPEN","DORMANT","CLOSED"],"default":"OPEN"}]',
  FALSE, 3,
  '{"table":"sst_savings_account","idColumn":"account_id","idPrefix":"S","link":{"column":"customer_id"},"businessKey":{"column":"account_no","type":"varchar(24)","label":"Account No","gen":"prefixdigits:SAV:8"},"columns":[{"column":"balance","type":"numeric(18,2)","from":"balance","unit":"USD","default":"0.00"},{"column":"status","type":"varchar(16)","from":"status","default":"OPEN"}]}')
ON CONFLICT (recipe_key) DO NOTHING;
