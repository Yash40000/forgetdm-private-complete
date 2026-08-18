whenever sqlerror exit sql.sqlcode
set define off
set feedback on
set timing on

connect TEMENOS_TDM/ForgeTdm2026@XE

prompt Repairing Temenos dynamic-array delimiters for AL32UTF8...
prompt VM=U+00FD, SM=U+00FC, FM=U+00FE

update FBNK_CUSTOMER c
set MV_ID_TYPE = 'QID' || to_char(unistr('\00FD')) || 'PASSPORT' ||
      case when mod(to_number(substr(c.CUSTOMER_NO, 2)), 8) = 0
        then to_char(unistr('\00FD')) || 'CR' else '' end,
    MV_ID_NUMBER = '28' || lpad(to_number(substr(c.CUSTOMER_NO, 2)), 9, '0') ||
      to_char(unistr('\00FD')) || 'P' || lpad(to_number(substr(c.CUSTOMER_NO, 2)) * 13, 10, '0') ||
      case when mod(to_number(substr(c.CUSTOMER_NO, 2)), 8) = 0
        then to_char(unistr('\00FD')) || 'CR-' || lpad(to_number(substr(c.CUSTOMER_NO, 2)), 8, '0') else '' end,
    MV_ADDRESS = 'ZONE' || lpad(mod(to_number(substr(c.CUSTOMER_NO, 2)), 98) + 1, 2, '0') ||
      to_char(unistr('\00FC')) || 'STREET' || lpad(mod(to_number(substr(c.CUSTOMER_NO, 2)) * 7, 999) + 1, 3, '0') ||
      to_char(unistr('\00FD')) || case mod(to_number(substr(c.CUSTOMER_NO, 2)), 6)
        when 0 then 'DOHA' when 1 then 'AL RAYYAN' when 2 then 'AL WAKRAH'
        when 3 then 'LUSAIL' when 4 then 'AL KHOR' else 'UMM SALAL' end || to_char(unistr('\00FC')) || 'QA',
    MV_PHONE = '+974' || to_char(30000000 + mod(to_number(substr(c.CUSTOMER_NO, 2)) * 7919, 69999999)) ||
      to_char(unistr('\00FD')) || '+974' || to_char(40000000 + mod(to_number(substr(c.CUSTOMER_NO, 2)) * 3571, 59999999)),
    XMLRECORD = to_clob('RECID=' || c.RECID || to_char(unistr('\00FE')) ||
      'NAME=' || upper(c.SHORT_NAME) || to_char(unistr('\00FE')) ||
      'ID.TYPE=QID' || to_char(unistr('\00FD')) || 'PASSPORT' || to_char(unistr('\00FE')) ||
      'ID.NO=28' || lpad(to_number(substr(c.CUSTOMER_NO, 2)), 9, '0') || to_char(unistr('\00FD')) ||
      'P' || lpad(to_number(substr(c.CUSTOMER_NO, 2)) * 13, 10, '0') || to_char(unistr('\00FE')) ||
      'KYC=' || c.KYC_RISK_CLASS);

update FBNK_CUSTOMER_CONTACT c
set MV_USAGE = case c.CONTACT_TYPE
  when 'MOBILE' then 'PERSONAL' || to_char(unistr('\00FD')) || 'SMS' || to_char(unistr('\00FC')) || 'OTP'
  when 'EMAIL' then 'STATEMENT' || to_char(unistr('\00FD')) || 'ALERT' || to_char(unistr('\00FC')) || 'MARKETING'
  else 'HOME' || to_char(unistr('\00FD')) || 'CORRESPONDENCE' end;

update FBNK_ACCOUNT a
set MV_SIGNATORY = a.CUSTOMER_NO || to_char(unistr('\00FC')) || 'OWNER' ||
      case when mod(to_number(substr(a.ACCOUNT_NO, 3)), 9) = 0
        then to_char(unistr('\00FD')) || 'C' || lpad(mod(to_number(substr(a.ACCOUNT_NO, 3)) + 111, 5000) + 1, 9, '0') ||
          to_char(unistr('\00FC')) || 'JOINT' else '' end,
    MV_POSTING_RESTRICT = case when a.ACCOUNT_STATUS = 'BLOCKED'
      then 'DEBIT' || to_char(unistr('\00FD')) || 'CREDIT' else 'NONE' end,
    XMLRECORD = to_clob('RECID=' || a.RECID || to_char(unistr('\00FE')) ||
      'CUSTOMER=' || a.CUSTOMER_NO || to_char(unistr('\00FE')) ||
      'CURRENCY=' || a.CURRENCY_CODE || to_char(unistr('\00FE')) ||
      'BALANCE=' || to_char(a.WORKING_BALANCE, 'FM9999999990D00'));

update FBNK_AA_ARRANGEMENT r
set MV_PARTY_ROLE = r.CUSTOMER_NO || to_char(unistr('\00FC')) || 'OWNER' ||
      case when mod(to_number(substr(r.ARRANGEMENT_ID, 3)), 10) = 0
        then to_char(unistr('\00FD')) || 'C' || lpad(mod(to_number(substr(r.ARRANGEMENT_ID, 3)) + 77, 5000) + 1, 9, '0') ||
          to_char(unistr('\00FC')) || 'CO.OWNER' else '' end,
    MV_LINKED_APPS = 'ACCOUNT' || to_char(unistr('\00FC')) || r.ACCOUNT_NO || to_char(unistr('\00FD')) ||
      'CUSTOMER' || to_char(unistr('\00FC')) || r.CUSTOMER_NO,
    XMLRECORD = to_clob('ARRANGEMENT=' || r.ARRANGEMENT_ID || to_char(unistr('\00FE')) ||
      'CUSTOMER=' || r.CUSTOMER_NO || to_char(unistr('\00FE')) ||
      'ACCOUNT=' || r.ACCOUNT_NO || to_char(unistr('\00FE')) ||
      'PRODUCT=' || r.PRODUCT_CODE);

update FBNK_AA_PROPERTY p
set MV_ATTRIBUTE_NAME = 'RATE' || to_char(unistr('\00FD')) || 'FREQUENCY' || to_char(unistr('\00FD')) ||
      'PAYMENT.TYPE' || to_char(unistr('\00FD')) || 'SETTLEMENT.ACCOUNT',
    MV_ATTRIBUTE_VALUE = to_char(2.5 + mod(to_number(substr(p.PROPERTY_ID, 8)), 600) / 100, 'FM990D00') ||
      to_char(unistr('\00FD')) || case mod(to_number(substr(p.PROPERTY_ID, 8)), 4)
        when 0 then 'M' when 1 then 'Q' when 2 then 'H' else 'A' end ||
      to_char(unistr('\00FD')) || case when mod(to_number(substr(p.PROPERTY_ID, 8)), 2) = 0 then 'ANNUITY' else 'BULLET' end ||
      to_char(unistr('\00FD')) || '10' || lpad(to_number(substr(p.PROPERTY_ID, 8)), 12, '0') || to_char(unistr('\00FC')) || 'PRIMARY';

update FBNK_STMT_ENTRY s
set MV_NARRATIVE = 'CHANNEL' || to_char(unistr('\00FC')) ||
      case mod(to_number(substr(s.STMT_ENTRY_ID, 6)), 4)
        when 0 then 'MOBILE' when 1 then 'WEB' when 2 then 'BRANCH' else 'ATM' end ||
      to_char(unistr('\00FD')) || 'REFERENCE' || to_char(unistr('\00FC')) || 'QIBTST' ||
      lpad(to_number(substr(s.STMT_ENTRY_ID, 6)), 10, '0') ||
      to_char(unistr('\00FD')) || 'PURPOSE' || to_char(unistr('\00FC')) ||
      case mod(to_number(substr(s.STMT_ENTRY_ID, 6)), 3)
        when 0 then 'FAMILY' when 1 then 'BILL' else 'SAVINGS' end;

update FBNK_FUNDS_TRANSFER f
set MV_CHARGE_CODE = 'TRANSFER' || to_char(unistr('\00FD')) || 'SWIFT' || to_char(unistr('\00FD')) || 'CORRESPONDENT',
    MV_CHARGE_AMOUNT = to_char(round(mod(to_number(substr(f.FT_REFERENCE, 11)) * 13, 25000) / 100, 2), 'FM9999990D00') ||
      to_char(unistr('\00FD')) || to_char(round(mod(to_number(substr(f.FT_REFERENCE, 11)) * 7, 5000) / 100, 2), 'FM9999990D00') ||
      to_char(unistr('\00FD')) || '0.00';

update FBNK_LIMIT l
set MV_COLLATERAL_REF = 'COLL-' || lpad(to_number(substr(l.LIMIT_REFERENCE, 5)), 10, '0') ||
      to_char(unistr('\00FC')) || 'PROPERTY' || to_char(unistr('\00FD')) ||
      'COLL-' || lpad(to_number(substr(l.LIMIT_REFERENCE, 5)) + 2000, 10, '0') ||
      to_char(unistr('\00FC')) || 'DEPOSIT';

update FBNK_EB_AUDIT_TRAIL a
set BEFORE_IMAGE = case when a.OPERATION_TYPE = 'CREATE' then null else
      to_clob('STATUS=BEFORE' || to_char(unistr('\00FE')) || 'VERSION=' || mod(to_number(substr(a.AUDIT_ID, 5)), 7)) end,
    AFTER_IMAGE = to_clob('STATUS=AFTER' || to_char(unistr('\00FE')) ||
      'VERSION=' || (mod(to_number(substr(a.AUDIT_ID, 5)), 7) + 1) || to_char(unistr('\00FE')) ||
      'AUDIT.NARRATIVE=AUTHORIZED SYNTHETIC TEMENOS LAB CHANGE');

commit;

column FIELD_NAME format a30
select 'CUSTOMER_MV_ID' field_name, count(*) rows_with_delimiter
from FBNK_CUSTOMER where instr(MV_ID_NUMBER, to_char(unistr('\00FD'))) > 0
union all
select 'ACCOUNT_MV_SIGNATORY', count(*)
from FBNK_ACCOUNT where instr(MV_SIGNATORY, to_char(unistr('\00FC'))) > 0
union all
select 'AA_PROPERTY_MV_VALUES', count(*)
from FBNK_AA_PROPERTY where instr(MV_ATTRIBUTE_VALUE, to_char(unistr('\00FD'))) > 0
union all
select 'STMT_MV_NARRATIVE', count(*)
from FBNK_STMT_ENTRY where instr(MV_NARRATIVE, to_char(unistr('\00FD'))) > 0
union all
select 'FT_MV_CHARGES', count(*)
from FBNK_FUNDS_TRANSFER where instr(MV_CHARGE_CODE, to_char(unistr('\00FD'))) > 0
union all
select 'LIMIT_MV_COLLATERAL', count(*)
from FBNK_LIMIT where instr(MV_COLLATERAL_REF, to_char(unistr('\00FD'))) > 0;

exit
