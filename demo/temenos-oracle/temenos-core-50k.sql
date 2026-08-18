whenever sqlerror exit sql.sqlcode
set define off
set serveroutput on
set timing on
set feedback on
set pagesize 200
set linesize 240

prompt ================================================================
prompt ForgeTDM Temenos-style Oracle lab
prompt Creates isolated schema TEMENOS_TDM with exactly 50,000 rows
prompt All names, identifiers, balances, narratives, and messages are synthetic
prompt ================================================================

connect / as sysdba

create user TEMENOS_TDM identified by ForgeTdm2026
  default tablespace USERS
  temporary tablespace TEMP
  quota unlimited on USERS;

grant create session, create table, create view, create sequence, create procedure to TEMENOS_TDM;

connect TEMENOS_TDM/ForgeTdm2026@XE

create table FBNK_CUSTOMER (
  RECID                 varchar2(35) not null,
  CUSTOMER_NO           varchar2(20) not null,
  MNEMONIC              varchar2(20) not null,
  SHORT_NAME            varchar2(80) not null,
  NAME_1                varchar2(100) not null,
  NAME_2                varchar2(100),
  NATIONALITY           char(2) not null,
  RESIDENCE             char(2) not null,
  LANGUAGE_CODE         varchar2(3) not null,
  SECTOR_CODE           varchar2(8) not null,
  INDUSTRY_CODE         varchar2(8),
  CUSTOMER_STATUS       varchar2(12) not null,
  KYC_RISK_CLASS        varchar2(12) not null,
  DATE_OF_BIRTH         date not null,
  MV_ID_TYPE            varchar2(1000),
  MV_ID_NUMBER          varchar2(2000),
  MV_ADDRESS            varchar2(3000),
  MV_PHONE              varchar2(1000),
  XMLRECORD             clob not null,
  RECORD_VERSION        number(10) default 1 not null,
  INPUTTER              varchar2(40) not null,
  AUTHORISER            varchar2(40) not null,
  DATE_TIME             timestamp not null,
  constraint PK_FBNK_CUSTOMER primary key (CUSTOMER_NO),
  constraint UK_FBNK_CUSTOMER_RECID unique (RECID),
  constraint CK_FBNK_CUSTOMER_STATUS check (CUSTOMER_STATUS in ('ACTIVE','DORMANT','RESTRICTED')),
  constraint CK_FBNK_CUSTOMER_KYC check (KYC_RISK_CLASS in ('LOW','MEDIUM','HIGH'))
);

create table FBNK_CUSTOMER_CONTACT (
  CONTACT_ID            varchar2(30) not null,
  CUSTOMER_NO           varchar2(20) not null,
  CONTACT_TYPE          varchar2(12) not null,
  CONTACT_VALUE         varchar2(180) not null,
  MV_USAGE              varchar2(500),
  PREFERRED_FLAG        char(1) not null,
  VERIFIED_FLAG         char(1) not null,
  EFFECTIVE_DATE        date not null,
  EXPIRY_DATE           date,
  RECORD_VERSION        number(10) default 1 not null,
  DATE_TIME             timestamp not null,
  constraint PK_FBNK_CUST_CONTACT primary key (CONTACT_ID),
  constraint FK_FBNK_CUST_CONTACT foreign key (CUSTOMER_NO) references FBNK_CUSTOMER(CUSTOMER_NO),
  constraint CK_FBNK_CONTACT_TYPE check (CONTACT_TYPE in ('MOBILE','EMAIL','ADDRESS')),
  constraint CK_FBNK_CONTACT_PREF check (PREFERRED_FLAG in ('Y','N')),
  constraint CK_FBNK_CONTACT_VERIFY check (VERIFIED_FLAG in ('Y','N'))
);

create table FBNK_ACCOUNT (
  RECID                 varchar2(35) not null,
  ACCOUNT_NO            varchar2(20) not null,
  CUSTOMER_NO           varchar2(20) not null,
  CATEGORY_CODE         varchar2(8) not null,
  CURRENCY_CODE         char(3) not null,
  COMPANY_CODE          varchar2(8) not null,
  BRANCH_CODE           varchar2(8) not null,
  ACCOUNT_TITLE         varchar2(120) not null,
  IBAN                   varchar2(34) not null,
  OPENING_DATE          date not null,
  ACCOUNT_STATUS        varchar2(12) not null,
  WORKING_BALANCE       number(18,2) not null,
  AVAILABLE_BALANCE     number(18,2) not null,
  LOCKED_AMOUNT         number(18,2) not null,
  INTEREST_BASIS        varchar2(20) not null,
  MV_SIGNATORY          varchar2(2000),
  MV_POSTING_RESTRICT   varchar2(1000),
  XMLRECORD             clob not null,
  RECORD_VERSION        number(10) default 1 not null,
  INPUTTER              varchar2(40) not null,
  AUTHORISER            varchar2(40) not null,
  DATE_TIME             timestamp not null,
  constraint PK_FBNK_ACCOUNT primary key (ACCOUNT_NO),
  constraint UK_FBNK_ACCOUNT_RECID unique (RECID),
  constraint UK_FBNK_ACCOUNT_IBAN unique (IBAN),
  constraint FK_FBNK_ACCOUNT_CUSTOMER foreign key (CUSTOMER_NO) references FBNK_CUSTOMER(CUSTOMER_NO),
  constraint CK_FBNK_ACCOUNT_STATUS check (ACCOUNT_STATUS in ('ACTIVE','DORMANT','BLOCKED','CLOSED'))
);

create table FBNK_AA_ARRANGEMENT (
  ARRANGEMENT_ID        varchar2(30) not null,
  CUSTOMER_NO           varchar2(20) not null,
  ACCOUNT_NO            varchar2(20) not null,
  PRODUCT_LINE          varchar2(20) not null,
  PRODUCT_GROUP         varchar2(30) not null,
  PRODUCT_CODE          varchar2(30) not null,
  CURRENCY_CODE         char(3) not null,
  ARRANGEMENT_STATUS    varchar2(12) not null,
  START_DATE            date not null,
  MATURITY_DATE         date,
  ORIG_CONTRACT_DATE    date not null,
  MV_PARTY_ROLE         varchar2(1000) not null,
  MV_LINKED_APPS        varchar2(2000),
  ARRANGEMENT_VERSION   number(10) not null,
  XMLRECORD             clob not null,
  DATE_TIME             timestamp not null,
  constraint PK_FBNK_AA_ARRANGEMENT primary key (ARRANGEMENT_ID),
  constraint FK_FBNK_AA_ARR_CUSTOMER foreign key (CUSTOMER_NO) references FBNK_CUSTOMER(CUSTOMER_NO),
  constraint FK_FBNK_AA_ARR_ACCOUNT foreign key (ACCOUNT_NO) references FBNK_ACCOUNT(ACCOUNT_NO),
  constraint CK_FBNK_AA_STATUS check (ARRANGEMENT_STATUS in ('CURRENT','MATURED','CANCELLED'))
);

create table FBNK_AA_PROPERTY (
  PROPERTY_ID           varchar2(35) not null,
  ARRANGEMENT_ID        varchar2(30) not null,
  PROPERTY_CLASS        varchar2(30) not null,
  PROPERTY_NAME         varchar2(40) not null,
  EFFECTIVE_DATE        date not null,
  EXPIRY_DATE           date,
  MV_ATTRIBUTE_NAME     varchar2(2000) not null,
  MV_ATTRIBUTE_VALUE    varchar2(4000) not null,
  PROPERTY_STATUS       varchar2(12) not null,
  RECORD_VERSION        number(10) not null,
  DATE_TIME             timestamp not null,
  constraint PK_FBNK_AA_PROPERTY primary key (PROPERTY_ID),
  constraint FK_FBNK_AA_PROP_ARR foreign key (ARRANGEMENT_ID) references FBNK_AA_ARRANGEMENT(ARRANGEMENT_ID),
  constraint CK_FBNK_AA_PROP_STATUS check (PROPERTY_STATUS in ('ACTIVE','EXPIRED','PENDING'))
);

create table FBNK_STMT_ENTRY (
  STMT_ENTRY_ID         varchar2(35) not null,
  ACCOUNT_NO            varchar2(20) not null,
  ARRANGEMENT_ID        varchar2(30) not null,
  TRANSACTION_REF       varchar2(35) not null,
  TRANSACTION_CODE      varchar2(8) not null,
  DEBIT_CREDIT_IND      char(1) not null,
  CURRENCY_CODE         char(3) not null,
  AMOUNT_FCY            number(18,2) not null,
  AMOUNT_LCY            number(18,2) not null,
  EXCHANGE_RATE         number(18,8) not null,
  BOOKING_DATE          date not null,
  VALUE_DATE            date not null,
  NARRATIVE             varchar2(1000) not null,
  MV_NARRATIVE          varchar2(3000),
  COMPANY_CODE          varchar2(8) not null,
  INPUTTER              varchar2(40) not null,
  AUTHORISER            varchar2(40) not null,
  DATE_TIME             timestamp not null,
  constraint PK_FBNK_STMT_ENTRY primary key (STMT_ENTRY_ID),
  constraint UK_FBNK_STMT_TXN_REF unique (TRANSACTION_REF),
  constraint FK_FBNK_STMT_ACCOUNT foreign key (ACCOUNT_NO) references FBNK_ACCOUNT(ACCOUNT_NO),
  constraint FK_FBNK_STMT_ARR foreign key (ARRANGEMENT_ID) references FBNK_AA_ARRANGEMENT(ARRANGEMENT_ID),
  constraint CK_FBNK_STMT_DC check (DEBIT_CREDIT_IND in ('D','C'))
);

create table FBNK_FUNDS_TRANSFER (
  FT_REFERENCE          varchar2(35) not null,
  DEBIT_ACCOUNT_NO      varchar2(20) not null,
  CREDIT_ACCOUNT_NO     varchar2(20) not null,
  ORDERING_CUSTOMER_NO  varchar2(20) not null,
  BENEFICIARY_NAME      varchar2(140) not null,
  BENEFICIARY_BANK_BIC  varchar2(11) not null,
  AMOUNT                number(18,2) not null,
  CURRENCY_CODE         char(3) not null,
  PAYMENT_DETAILS       varchar2(500) not null,
  MV_CHARGE_CODE        varchar2(1000),
  MV_CHARGE_AMOUNT      varchar2(1000),
  CHARGE_BEARER         varchar2(4) not null,
  PROCESSING_STATUS     varchar2(14) not null,
  VALUE_DATE            date not null,
  INPUTTER              varchar2(40) not null,
  AUTHORISER            varchar2(40) not null,
  DATE_TIME             timestamp not null,
  constraint PK_FBNK_FUNDS_TRANSFER primary key (FT_REFERENCE),
  constraint FK_FBNK_FT_DEBIT_ACCOUNT foreign key (DEBIT_ACCOUNT_NO) references FBNK_ACCOUNT(ACCOUNT_NO),
  constraint FK_FBNK_FT_CREDIT_ACCOUNT foreign key (CREDIT_ACCOUNT_NO) references FBNK_ACCOUNT(ACCOUNT_NO),
  constraint FK_FBNK_FT_CUSTOMER foreign key (ORDERING_CUSTOMER_NO) references FBNK_CUSTOMER(CUSTOMER_NO),
  constraint CK_FBNK_FT_STATUS check (PROCESSING_STATUS in ('COMPLETED','PENDING','REJECTED','REVERSED'))
);

create table FBNK_TPH_PAYMENT_ORDER (
  PAYMENT_ORDER_ID      varchar2(35) not null,
  FT_REFERENCE          varchar2(35) not null,
  PAYMENT_SCHEME        varchar2(12) not null,
  MESSAGE_TYPE          varchar2(12) not null,
  UETR                  varchar2(36) not null,
  END_TO_END_ID         varchar2(35) not null,
  INSTRUCTED_AMOUNT     number(18,2) not null,
  CURRENCY_CODE         char(3) not null,
  DEBTOR_NAME           varchar2(140) not null,
  CREDITOR_NAME         varchar2(140) not null,
  CREDITOR_IBAN         varchar2(34) not null,
  PAYMENT_STATUS        varchar2(14) not null,
  SANCTIONS_RESULT      varchar2(12) not null,
  AML_RISK_SCORE        number(5,2) not null,
  MX_MESSAGE            clob not null,
  CREATION_TS           timestamp not null,
  SETTLEMENT_TS         timestamp,
  constraint PK_FBNK_TPH_PAYMENT primary key (PAYMENT_ORDER_ID),
  constraint UK_FBNK_TPH_UETR unique (UETR),
  constraint UK_FBNK_TPH_E2E unique (END_TO_END_ID),
  constraint FK_FBNK_TPH_FT foreign key (FT_REFERENCE) references FBNK_FUNDS_TRANSFER(FT_REFERENCE),
  constraint CK_FBNK_TPH_STATUS check (PAYMENT_STATUS in ('ACCEPTED','SETTLED','HELD','REJECTED'))
);

create table FBNK_LIMIT (
  LIMIT_REFERENCE       varchar2(35) not null,
  CUSTOMER_NO           varchar2(20) not null,
  ACCOUNT_NO            varchar2(20) not null,
  LIMIT_TYPE            varchar2(20) not null,
  CURRENCY_CODE         char(3) not null,
  APPROVED_AMOUNT       number(18,2) not null,
  UTILIZED_AMOUNT       number(18,2) not null,
  AVAILABLE_AMOUNT      number(18,2) not null,
  EXPIRY_DATE           date not null,
  SECURED_FLAG          char(1) not null,
  MV_COLLATERAL_REF     varchar2(2000),
  LIMIT_STATUS          varchar2(12) not null,
  DATE_TIME             timestamp not null,
  constraint PK_FBNK_LIMIT primary key (LIMIT_REFERENCE),
  constraint FK_FBNK_LIMIT_CUSTOMER foreign key (CUSTOMER_NO) references FBNK_CUSTOMER(CUSTOMER_NO),
  constraint FK_FBNK_LIMIT_ACCOUNT foreign key (ACCOUNT_NO) references FBNK_ACCOUNT(ACCOUNT_NO),
  constraint CK_FBNK_LIMIT_STATUS check (LIMIT_STATUS in ('ACTIVE','EXPIRED','SUSPENDED')),
  constraint CK_FBNK_LIMIT_SECURED check (SECURED_FLAG in ('Y','N')),
  constraint CK_FBNK_LIMIT_BALANCE check (AVAILABLE_AMOUNT = APPROVED_AMOUNT - UTILIZED_AMOUNT)
);

create table FBNK_EB_AUDIT_TRAIL (
  AUDIT_ID              varchar2(35) not null,
  CUSTOMER_NO           varchar2(20) not null,
  APPLICATION_NAME      varchar2(30) not null,
  ENTITY_NAME           varchar2(40) not null,
  RECORD_ID             varchar2(40) not null,
  OPERATION_TYPE        varchar2(12) not null,
  BEFORE_IMAGE          clob,
  AFTER_IMAGE           clob,
  USER_ID               varchar2(40) not null,
  SESSION_ID            varchar2(50) not null,
  CHANNEL_CODE          varchar2(15) not null,
  EVENT_TS              timestamp not null,
  constraint PK_FBNK_EB_AUDIT primary key (AUDIT_ID),
  constraint FK_FBNK_AUDIT_CUSTOMER foreign key (CUSTOMER_NO) references FBNK_CUSTOMER(CUSTOMER_NO),
  constraint CK_FBNK_AUDIT_OPERATION check (OPERATION_TYPE in ('CREATE','UPDATE','AUTHORISE','REVERSAL'))
);

prompt Loading 5,000 customers...
insert /*+ append */ into FBNK_CUSTOMER (
  RECID, CUSTOMER_NO, MNEMONIC, SHORT_NAME, NAME_1, NAME_2,
  NATIONALITY, RESIDENCE, LANGUAGE_CODE, SECTOR_CODE, INDUSTRY_CODE,
  CUSTOMER_STATUS, KYC_RISK_CLASS, DATE_OF_BIRTH,
  MV_ID_TYPE, MV_ID_NUMBER, MV_ADDRESS, MV_PHONE, XMLRECORD,
  RECORD_VERSION, INPUTTER, AUTHORISER, DATE_TIME
)
select
  'CUSTOMER-' || lpad(level, 10, '0'),
  'C' || lpad(level, 9, '0'),
  'CUS' || lpad(level, 7, '0'),
  case mod(level, 16)
    when 0 then 'Abdullah Al Kuwari' when 1 then 'Mohammed Al Marri'
    when 2 then 'Ahmed Al Thani' when 3 then 'Khalid Al Mohannadi'
    when 4 then 'Yousef Al Sulaiti' when 5 then 'Hamad Al Mannai'
    when 6 then 'Nasser Al Kaabi' when 7 then 'Faisal Al Nuaimi'
    when 8 then 'Aisha Al Ansari' when 9 then 'Fatima Al Emadi'
    when 10 then 'Mariam Al Sada' when 11 then 'Noora Al Dosari'
    when 12 then 'Layla Hassan' when 13 then 'Sara Ibrahim'
    when 14 then 'Omar Mahmoud' else 'Ali Rahman' end,
  case mod(level, 16)
    when 0 then 'Abdullah' when 1 then 'Mohammed' when 2 then 'Ahmed' when 3 then 'Khalid'
    when 4 then 'Yousef' when 5 then 'Hamad' when 6 then 'Nasser' when 7 then 'Faisal'
    when 8 then 'Aisha' when 9 then 'Fatima' when 10 then 'Mariam' when 11 then 'Noora'
    when 12 then 'Layla' when 13 then 'Sara' when 14 then 'Omar' else 'Ali' end,
  case mod(level, 16)
    when 0 then 'Al Kuwari' when 1 then 'Al Marri' when 2 then 'Al Thani' when 3 then 'Al Mohannadi'
    when 4 then 'Al Sulaiti' when 5 then 'Al Mannai' when 6 then 'Al Kaabi' when 7 then 'Al Nuaimi'
    when 8 then 'Al Ansari' when 9 then 'Al Emadi' when 10 then 'Al Sada' when 11 then 'Al Dosari'
    when 12 then 'Hassan' when 13 then 'Ibrahim' when 14 then 'Mahmoud' else 'Rahman' end,
  case when mod(level, 10) < 7 then 'QA' when mod(level, 10) = 7 then 'IN' when mod(level, 10) = 8 then 'GB' else 'US' end,
  'QA',
  case when mod(level, 5) = 0 then 'ARA' else 'ENG' end,
  case when mod(level, 7) = 0 then '2001' when mod(level, 7) = 1 then '1001' else '3001' end,
  case when mod(level, 6) = 0 then 'FIN' when mod(level, 6) = 1 then 'GOV' else 'RET' end,
  case when mod(level, 97) = 0 then 'RESTRICTED' when mod(level, 29) = 0 then 'DORMANT' else 'ACTIVE' end,
  case when mod(level, 17) = 0 then 'HIGH' when mod(level, 5) = 0 then 'MEDIUM' else 'LOW' end,
  date '1950-01-01' + mod(level * 37, 22000),
  'QID' || to_char(unistr('\00FD')) || 'PASSPORT' || case when mod(level, 8) = 0 then to_char(unistr('\00FD')) || 'CR' else '' end,
  '28' || lpad(level, 9, '0') || to_char(unistr('\00FD')) || 'P' || lpad(level * 13, 10, '0') ||
    case when mod(level, 8) = 0 then to_char(unistr('\00FD')) || 'CR-' || lpad(level, 8, '0') else '' end,
  'ZONE' || lpad(mod(level, 98) + 1, 2, '0') || to_char(unistr('\00FC')) || 'STREET' || lpad(mod(level * 7, 999) + 1, 3, '0') ||
    to_char(unistr('\00FD')) || case mod(level, 6) when 0 then 'DOHA' when 1 then 'AL RAYYAN' when 2 then 'AL WAKRAH'
      when 3 then 'LUSAIL' when 4 then 'AL KHOR' else 'UMM SALAL' end || to_char(unistr('\00FC')) || 'QA',
  '+974' || to_char(30000000 + mod(level * 7919, 69999999)) || to_char(unistr('\00FD')) ||
    '+974' || to_char(40000000 + mod(level * 3571, 59999999)),
  to_clob('RECID=CUSTOMER-' || lpad(level, 10, '0') || to_char(unistr('\00FE')) ||
    'NAME=' || case mod(level, 4) when 0 then 'ABDULLAH AL KUWARI' when 1 then 'MOHAMMED AL MARRI'
      when 2 then 'AISHA AL ANSARI' else 'FATIMA AL EMADI' end || to_char(unistr('\00FE')) ||
    'ID.TYPE=QID' || to_char(unistr('\00FD')) || 'PASSPORT' || to_char(unistr('\00FE')) ||
    'ID.NO=28' || lpad(level, 9, '0') || to_char(unistr('\00FD')) || 'P' || lpad(level * 13, 10, '0') || to_char(unistr('\00FE')) ||
    'KYC=' || case when mod(level,17)=0 then 'HIGH' when mod(level,5)=0 then 'MEDIUM' else 'LOW' end),
  1 + mod(level, 6),
  'T24.BATCH' || lpad(mod(level, 12) + 1, 2, '0'),
  'SUPERVISOR' || lpad(mod(level, 5) + 1, 2, '0'),
  timestamp '2025-01-01 00:00:00' + numtodsinterval(mod(level * 211, 31536000), 'SECOND')
from dual connect by level <= 5000;

prompt Loading 4,000 customer contacts...
insert /*+ append */ into FBNK_CUSTOMER_CONTACT
select
  'CONT-' || lpad(level, 10, '0'),
  'C' || lpad(mod(level - 1, 5000) + 1, 9, '0'),
  case mod(level, 3) when 0 then 'MOBILE' when 1 then 'EMAIL' else 'ADDRESS' end,
  case mod(level, 3)
    when 0 then '+974' || to_char(50000000 + mod(level * 6151, 49999999))
    when 1 then 'customer' || lpad(level, 5, '0') || '@qib-test.example'
    else 'Villa ' || (mod(level, 450) + 1) || ', Zone ' || (mod(level, 98) + 1) || ', Doha, Qatar' end,
  case mod(level, 3)
    when 0 then 'PERSONAL' || to_char(unistr('\00FD')) || 'SMS' || to_char(unistr('\00FC')) || 'OTP'
    when 1 then 'STATEMENT' || to_char(unistr('\00FD')) || 'ALERT' || to_char(unistr('\00FC')) || 'MARKETING'
    else 'HOME' || to_char(unistr('\00FD')) || 'CORRESPONDENCE' end,
  case when mod(level, 4) = 0 then 'N' else 'Y' end,
  case when mod(level, 11) = 0 then 'N' else 'Y' end,
  date '2022-01-01' + mod(level * 17, 1200),
  case when mod(level, 23) = 0 then date '2027-12-31' else null end,
  1 + mod(level, 4),
  timestamp '2025-01-01 00:00:00' + numtodsinterval(mod(level * 241, 31536000), 'SECOND')
from dual connect by level <= 4000;

prompt Loading 7,000 accounts...
insert /*+ append */ into FBNK_ACCOUNT
select
  'ACCOUNT-' || lpad(level, 10, '0'),
  '10' || lpad(level, 12, '0'),
  'C' || lpad(mod(level - 1, 5000) + 1, 9, '0'),
  case mod(level, 5) when 0 then '1001' when 1 then '1002' when 2 then '2001' when 3 then '3001' else '6001' end,
  case mod(level, 8) when 0 then 'USD' when 1 then 'EUR' when 2 then 'GBP' else 'QAR' end,
  'QA001001',
  'BR' || lpad(mod(level, 35) + 1, 3, '0'),
  'QIB TEST ACCOUNT ' || lpad(level, 7, '0'),
  'QA58QNBA' || lpad(level, 21, '0'),
  date '2012-01-01' + mod(level * 29, 4800),
  case when mod(level, 151) = 0 then 'CLOSED' when mod(level, 73) = 0 then 'BLOCKED'
    when mod(level, 31) = 0 then 'DORMANT' else 'ACTIVE' end,
  round(mod(level * 7919, 25000000) / 100, 2),
  round(mod(level * 7919, 25000000) / 100 - mod(level * 97, 250000) / 100, 2),
  round(mod(level * 97, 250000) / 100, 2),
  case when mod(level, 3) = 0 then 'ACT/365' when mod(level, 3) = 1 then 'ACT/360' else '30/360' end,
  'C' || lpad(mod(level - 1, 5000) + 1, 9, '0') || to_char(unistr('\00FC')) || 'OWNER' ||
    case when mod(level, 9) = 0 then to_char(unistr('\00FD')) || 'C' || lpad(mod(level + 111, 5000) + 1, 9, '0') || to_char(unistr('\00FC')) || 'JOINT' else '' end,
  case when mod(level, 73) = 0 then 'DEBIT' || to_char(unistr('\00FD')) || 'CREDIT' else 'NONE' end,
  to_clob('RECID=ACCOUNT-' || lpad(level,10,'0') || to_char(unistr('\00FE')) ||
    'CUSTOMER=C' || lpad(mod(level - 1,5000)+1,9,'0') || to_char(unistr('\00FE')) ||
    'CURRENCY=' || case mod(level,8) when 0 then 'USD' when 1 then 'EUR' when 2 then 'GBP' else 'QAR' end || to_char(unistr('\00FE')) ||
    'BALANCE=' || to_char(round(mod(level*7919,25000000)/100,2),'FM9999999990D00')),
  1 + mod(level, 8),
  'AC.BATCH' || lpad(mod(level, 8) + 1, 2, '0'),
  'AC.AUTH' || lpad(mod(level, 4) + 1, 2, '0'),
  timestamp '2025-01-01 00:00:00' + numtodsinterval(mod(level * 307, 31536000), 'SECOND')
from dual connect by level <= 7000;

prompt Loading 7,000 AA arrangements...
insert /*+ append */ into FBNK_AA_ARRANGEMENT
select
  'AA' || lpad(level, 12, '0'),
  'C' || lpad(mod(level - 1, 5000) + 1, 9, '0'),
  '10' || lpad(level, 12, '0'),
  case mod(level, 5) when 0 then 'DEPOSITS' when 1 then 'LENDING' when 2 then 'ACCOUNTS'
    when 3 then 'CARDS' else 'PAYMENTS' end,
  case mod(level, 5) when 0 then 'TERM.DEPOSIT' when 1 then 'PERSONAL.LOAN' when 2 then 'CURRENT.ACCOUNT'
    when 3 then 'DEBIT.CARD' else 'DOMESTIC.PAYMENT' end,
  case mod(level, 7) when 0 then 'QIB.SAVINGS' when 1 then 'QIB.CURRENT' when 2 then 'QIB.PLATINUM'
    when 3 then 'QIB.MURABAHA' when 4 then 'QIB.WAKALA' when 5 then 'QIB.PREPAID' else 'QIB.PAYMENT' end,
  case mod(level, 8) when 0 then 'USD' when 1 then 'EUR' when 2 then 'GBP' else 'QAR' end,
  case when mod(level, 149) = 0 then 'CANCELLED' when mod(level, 61) = 0 then 'MATURED' else 'CURRENT' end,
  date '2018-01-01' + mod(level * 23, 2800),
  case when mod(level, 5) in (0, 1) then date '2027-01-01' + mod(level * 31, 2200) else null end,
  date '2018-01-01' + mod(level * 23, 2800),
  'C' || lpad(mod(level - 1, 5000) + 1, 9, '0') || to_char(unistr('\00FC')) || 'OWNER' ||
    case when mod(level, 10) = 0 then to_char(unistr('\00FD')) || 'C' || lpad(mod(level + 77, 5000) + 1, 9, '0') || to_char(unistr('\00FC')) || 'CO.OWNER' else '' end,
  'ACCOUNT' || to_char(unistr('\00FC')) || '10' || lpad(level, 12, '0') || to_char(unistr('\00FD')) ||
    'CUSTOMER' || to_char(unistr('\00FC')) || 'C' || lpad(mod(level - 1, 5000) + 1, 9, '0'),
  1 + mod(level, 9),
  to_clob('ARRANGEMENT=AA' || lpad(level,12,'0') || to_char(unistr('\00FE')) ||
    'CUSTOMER=C' || lpad(mod(level-1,5000)+1,9,'0') || to_char(unistr('\00FE')) ||
    'ACCOUNT=10' || lpad(level,12,'0') || to_char(unistr('\00FE')) ||
    'PRODUCT=' || case mod(level,7) when 0 then 'QIB.SAVINGS' when 1 then 'QIB.CURRENT'
      when 2 then 'QIB.PLATINUM' when 3 then 'QIB.MURABAHA' when 4 then 'QIB.WAKALA'
      when 5 then 'QIB.PREPAID' else 'QIB.PAYMENT' end),
  timestamp '2025-01-01 00:00:00' + numtodsinterval(mod(level * 331, 31536000), 'SECOND')
from dual connect by level <= 7000;

prompt Loading 7,000 AA properties with multi-value/sub-value payloads...
insert /*+ append */ into FBNK_AA_PROPERTY
select
  'AAPROP-' || lpad(level, 10, '0'),
  'AA' || lpad(level, 12, '0'),
  case mod(level, 5) when 0 then 'INTEREST' when 1 then 'SETTLEMENT' when 2 then 'PAYMENT.SCHEDULE'
    when 3 then 'LIMIT' else 'ACCOUNT' end,
  case mod(level, 5) when 0 then 'FIXED.RATE' when 1 then 'PAYIN.ACCOUNT' when 2 then 'BULLET.SCHEDULE'
    when 3 then 'ARRANGEMENT.LIMIT' else 'LINKED.ACCOUNT' end,
  date '2024-01-01' + mod(level * 13, 1000),
  case when mod(level, 47) = 0 then date '2027-12-31' else null end,
  'RATE' || to_char(unistr('\00FD')) || 'FREQUENCY' || to_char(unistr('\00FD')) || 'PAYMENT.TYPE' || to_char(unistr('\00FD')) || 'SETTLEMENT.ACCOUNT',
  to_char(2.5 + mod(level, 600) / 100, 'FM990D00') || to_char(unistr('\00FD')) ||
    case mod(level, 4) when 0 then 'M' when 1 then 'Q' when 2 then 'H' else 'A' end || to_char(unistr('\00FD')) ||
    case when mod(level, 2) = 0 then 'ANNUITY' else 'BULLET' end || to_char(unistr('\00FD')) ||
    '10' || lpad(level, 12, '0') || to_char(unistr('\00FC')) || 'PRIMARY',
  case when mod(level, 79) = 0 then 'PENDING' when mod(level, 43) = 0 then 'EXPIRED' else 'ACTIVE' end,
  1 + mod(level, 7),
  timestamp '2025-01-01 00:00:00' + numtodsinterval(mod(level * 349, 31536000), 'SECOND')
from dual connect by level <= 7000;

prompt Loading 10,000 statement entries...
insert /*+ append */ into FBNK_STMT_ENTRY
select
  'STMT-' || lpad(level, 12, '0'),
  '10' || lpad(mod(level - 1, 7000) + 1, 12, '0'),
  'AA' || lpad(mod(level - 1, 7000) + 1, 12, '0'),
  'TXN' || to_char(date '2025-01-01' + mod(level, 365), 'YYYYMMDD') || lpad(level, 10, '0'),
  case mod(level, 8) when 0 then 'TELLER' when 1 then 'ATM' when 2 then 'POS' when 3 then 'SALARY'
    when 4 then 'FT' when 5 then 'FEE' when 6 then 'PROFIT' else 'REVERSAL' end,
  case when mod(level, 2) = 0 then 'D' else 'C' end,
  case mod(level, 8) when 0 then 'USD' when 1 then 'EUR' when 2 then 'GBP' else 'QAR' end,
  round(10 + mod(level * 1237, 5000000) / 100, 2),
  round((10 + mod(level * 1237, 5000000) / 100) *
    case mod(level, 8) when 0 then 3.64 when 1 then 3.95 when 2 then 4.62 else 1 end, 2),
  case mod(level, 8) when 0 then 3.64 when 1 then 3.95 when 2 then 4.62 else 1 end,
  date '2025-01-01' + mod(level * 7, 550),
  date '2025-01-01' + mod(level * 7 + mod(level, 3), 550),
  case mod(level, 8) when 0 then 'BRANCH CASH TRANSACTION' when 1 then 'ATM WITHDRAWAL DOHA'
    when 2 then 'POS PURCHASE QIB TEST MERCHANT' when 3 then 'MONTHLY SALARY CREDIT'
    when 4 then 'DOMESTIC FUNDS TRANSFER' when 5 then 'ACCOUNT SERVICE FEE'
    when 6 then 'PROFIT DISTRIBUTION' else 'REVERSAL OF TEST TRANSACTION' end,
  'CHANNEL' || to_char(unistr('\00FC')) || case mod(level,4) when 0 then 'MOBILE' when 1 then 'WEB' when 2 then 'BRANCH' else 'ATM' end ||
    to_char(unistr('\00FD')) || 'REFERENCE' || to_char(unistr('\00FC')) || 'QIBTST' || lpad(level,10,'0') ||
    to_char(unistr('\00FD')) || 'PURPOSE' || to_char(unistr('\00FC')) || case mod(level,3) when 0 then 'FAMILY' when 1 then 'BILL' else 'SAVINGS' end,
  'QA001001',
  'STMT.BATCH' || lpad(mod(level, 12) + 1, 2, '0'),
  'STMT.AUTH' || lpad(mod(level, 6) + 1, 2, '0'),
  timestamp '2025-01-01 00:00:00' + numtodsinterval(mod(level * 397, 31536000), 'SECOND')
from dual connect by level <= 10000;

prompt Loading 3,000 funds transfers...
insert /*+ append */ into FBNK_FUNDS_TRANSFER
select
  'FT' || to_char(date '2025-01-01' + mod(level, 365), 'YYYYMMDD') || lpad(level, 9, '0'),
  '10' || lpad(mod(level - 1, 7000) + 1, 12, '0'),
  '10' || lpad(mod(level + 1700, 7000) + 1, 12, '0'),
  'C' || lpad(mod(level - 1, 5000) + 1, 9, '0'),
  case mod(level, 8) when 0 then 'Qatar Test Trading WLL' when 1 then 'Doha Test Services LLC'
    when 2 then 'Al Rayyan Test Supplies' when 3 then 'Gulf Test Logistics'
    when 4 then 'Mariam Al Sada' when 5 then 'Ahmed Al Thani' when 6 then 'Sara Ibrahim' else 'Ali Rahman' end,
  case mod(level, 5) when 0 then 'QNBAQAQA' when 1 then 'DOHBQAQA' when 2 then 'CBQAQAQA'
    when 3 then 'MARFQAQA' else 'QIBKQAQA' end,
  round(50 + mod(level * 1877, 20000000) / 100, 2),
  case mod(level, 8) when 0 then 'USD' when 1 then 'EUR' when 2 then 'GBP' else 'QAR' end,
  case mod(level, 5) when 0 then 'INVOICE PAYMENT TEST' when 1 then 'FAMILY REMITTANCE TEST'
    when 2 then 'TREASURY SETTLEMENT TEST' when 3 then 'SUPPLIER PAYMENT TEST' else 'ACCOUNT TRANSFER TEST' end,
  'TRANSFER' || to_char(unistr('\00FD')) || 'SWIFT' || to_char(unistr('\00FD')) || 'CORRESPONDENT',
  to_char(round(mod(level * 13, 25000) / 100, 2), 'FM9999990D00') || to_char(unistr('\00FD')) ||
    to_char(round(mod(level * 7, 5000) / 100, 2), 'FM9999990D00') || to_char(unistr('\00FD')) || '0.00',
  case mod(level, 3) when 0 then 'OUR' when 1 then 'SHA' else 'BEN' end,
  case when mod(level, 89) = 0 then 'REJECTED' when mod(level, 41) = 0 then 'REVERSED'
    when mod(level, 13) = 0 then 'PENDING' else 'COMPLETED' end,
  date '2025-01-01' + mod(level * 11, 550),
  'FT.BATCH' || lpad(mod(level, 10) + 1, 2, '0'),
  'FT.AUTH' || lpad(mod(level, 5) + 1, 2, '0'),
  timestamp '2025-01-01 00:00:00' + numtodsinterval(mod(level * 419, 31536000), 'SECOND')
from dual connect by level <= 3000;

prompt Loading 3,000 TPH payment orders and ISO 20022-like messages...
insert /*+ append */ into FBNK_TPH_PAYMENT_ORDER
select
  'TPH-' || lpad(level, 12, '0'),
  'FT' || to_char(date '2025-01-01' + mod(level, 365), 'YYYYMMDD') || lpad(level, 9, '0'),
  case mod(level, 4) when 0 then 'SWIFT' when 1 then 'RTGS' when 2 then 'ACH' else 'INTERNAL' end,
  case mod(level, 4) when 0 then 'pacs.008' when 1 then 'pacs.009' when 2 then 'pain.001' else 'camt.056' end,
  lower(substr(rawtohex(sys_guid()), 1, 8) || '-' || substr(rawtohex(sys_guid()), 1, 4) || '-' ||
    substr(rawtohex(sys_guid()), 1, 4) || '-' || substr(rawtohex(sys_guid()), 1, 4) || '-' || substr(rawtohex(sys_guid()), 1, 12)),
  'E2E-QIB-' || lpad(level, 12, '0'),
  round(50 + mod(level * 1877, 20000000) / 100, 2),
  case mod(level, 8) when 0 then 'USD' when 1 then 'EUR' when 2 then 'GBP' else 'QAR' end,
  case mod(level, 4) when 0 then 'Abdullah Al Kuwari' when 1 then 'Mohammed Al Marri'
    when 2 then 'Aisha Al Ansari' else 'Fatima Al Emadi' end,
  case mod(level, 4) when 0 then 'Qatar Test Trading WLL' when 1 then 'Doha Test Services LLC'
    when 2 then 'Gulf Test Logistics' else 'Al Rayyan Test Supplies' end,
  'QA74QNBA' || lpad(mod(level + 1700, 7000) + 1, 21, '0'),
  case when mod(level, 89) = 0 then 'REJECTED' when mod(level, 17) = 0 then 'HELD'
    when mod(level, 7) = 0 then 'ACCEPTED' else 'SETTLED' end,
  case when mod(level, 89) = 0 then 'BLOCKED' when mod(level, 17) = 0 then 'REVIEW' else 'CLEAR' end,
  round(mod(level * 37, 10000) / 100, 2),
  to_clob('<Document><FIToFICstmrCdtTrf><GrpHdr><MsgId>QIB-TPH-' || lpad(level,12,'0') ||
    '</MsgId></GrpHdr><CdtTrfTxInf><PmtId><EndToEndId>E2E-QIB-' || lpad(level,12,'0') ||
    '</EndToEndId></PmtId><IntrBkSttlmAmt Ccy="' ||
    case mod(level,8) when 0 then 'USD' when 1 then 'EUR' when 2 then 'GBP' else 'QAR' end || '">' ||
    to_char(round(50 + mod(level*1877,20000000)/100,2),'FM9999999990D00') ||
    '</IntrBkSttlmAmt></CdtTrfTxInf></FIToFICstmrCdtTrf></Document>'),
  timestamp '2025-01-01 00:00:00' + numtodsinterval(mod(level * 431, 31536000), 'SECOND'),
  case when mod(level, 7) = 0 then null else
    timestamp '2025-01-01 00:00:00' + numtodsinterval(mod(level * 431, 31536000) + 90 + mod(level, 900), 'SECOND') end
from dual connect by level <= 3000;

prompt Loading 2,000 limits...
insert /*+ append */ into FBNK_LIMIT
select
  'LIM-' || lpad(level, 12, '0'),
  'C' || lpad(mod(level - 1, 5000) + 1, 9, '0'),
  '10' || lpad(mod(level - 1, 7000) + 1, 12, '0'),
  case mod(level, 4) when 0 then 'OVERDRAFT' when 1 then 'CARD.LIMIT' when 2 then 'TRADE.FINANCE' else 'CUSTOMER.GROUP' end,
  case mod(level, 8) when 0 then 'USD' when 1 then 'EUR' when 2 then 'GBP' else 'QAR' end,
  round(5000 + mod(level * 7919, 50000000) / 100, 2),
  round(mod(level * 3559, 25000000) / 100, 2),
  round(5000 + mod(level * 7919, 50000000) / 100 - mod(level * 3559, 25000000) / 100, 2),
  date '2027-01-01' + mod(level * 19, 1460),
  case when mod(level, 3) = 0 then 'N' else 'Y' end,
  'COLL-' || lpad(level, 10, '0') || to_char(unistr('\00FC')) || 'PROPERTY' || to_char(unistr('\00FD')) ||
    'COLL-' || lpad(level + 2000, 10, '0') || to_char(unistr('\00FC')) || 'DEPOSIT',
  case when mod(level, 113) = 0 then 'SUSPENDED' when mod(level, 67) = 0 then 'EXPIRED' else 'ACTIVE' end,
  timestamp '2025-01-01 00:00:00' + numtodsinterval(mod(level * 461, 31536000), 'SECOND')
from dual connect by level <= 2000;

prompt Loading 2,000 audit events...
insert /*+ append */ into FBNK_EB_AUDIT_TRAIL
select
  'AUD-' || lpad(level, 12, '0'),
  'C' || lpad(mod(level - 1, 5000) + 1, 9, '0'),
  case mod(level, 4) when 0 then 'CUSTOMER' when 1 then 'ACCOUNT' when 2 then 'AA.ARRANGEMENT' else 'FUNDS.TRANSFER' end,
  case mod(level, 4) when 0 then 'FBNK.CUSTOMER' when 1 then 'FBNK.ACCOUNT'
    when 2 then 'FBNK.AA.ARRANGEMENT' else 'FBNK.FUNDS.TRANSFER' end,
  case mod(level, 4) when 0 then 'C' || lpad(mod(level - 1,5000)+1,9,'0')
    when 1 then '10' || lpad(mod(level - 1,7000)+1,12,'0')
    when 2 then 'AA' || lpad(mod(level - 1,7000)+1,12,'0')
    else 'FT' || to_char(date '2025-01-01' + mod(level,365),'YYYYMMDD') || lpad(mod(level - 1,3000)+1,9,'0') end,
  case mod(level, 4) when 0 then 'CREATE' when 1 then 'UPDATE' when 2 then 'AUTHORISE' else 'REVERSAL' end,
  case when mod(level, 4) = 0 then null else to_clob('STATUS=BEFORE' || to_char(unistr('\00FE')) || 'VERSION=' || mod(level,7)) end,
  to_clob('STATUS=AFTER' || to_char(unistr('\00FE')) || 'VERSION=' || (mod(level,7)+1) || to_char(unistr('\00FE')) ||
    'AUDIT.NARRATIVE=AUTHORIZED SYNTHETIC TEMENOS LAB CHANGE'),
  'USER' || lpad(mod(level, 40) + 1, 3, '0'),
  'SESSION-' || lpad(mod(level * 997, 1000000), 8, '0'),
  case mod(level, 5) when 0 then 'TAFJ' when 1 then 'OFS' when 2 then 'MOBILE' when 3 then 'BRANCH' else 'BATCH' end,
  timestamp '2025-01-01 00:00:00' + numtodsinterval(mod(level * 487, 31536000), 'SECOND')
from dual connect by level <= 2000;

commit;

create index IX_FBNK_ACCOUNT_CUSTOMER on FBNK_ACCOUNT(CUSTOMER_NO);
create index IX_FBNK_AA_CUSTOMER on FBNK_AA_ARRANGEMENT(CUSTOMER_NO);
create index IX_FBNK_AA_ACCOUNT on FBNK_AA_ARRANGEMENT(ACCOUNT_NO);
create index IX_FBNK_AA_PROP_ARR on FBNK_AA_PROPERTY(ARRANGEMENT_ID);
create index IX_FBNK_STMT_ACCOUNT_DATE on FBNK_STMT_ENTRY(ACCOUNT_NO, BOOKING_DATE);
create index IX_FBNK_STMT_ARR on FBNK_STMT_ENTRY(ARRANGEMENT_ID);
create index IX_FBNK_FT_DEBIT on FBNK_FUNDS_TRANSFER(DEBIT_ACCOUNT_NO);
create index IX_FBNK_FT_CREDIT on FBNK_FUNDS_TRANSFER(CREDIT_ACCOUNT_NO);
create index IX_FBNK_TPH_FT on FBNK_TPH_PAYMENT_ORDER(FT_REFERENCE);
create index IX_FBNK_LIMIT_CUSTOMER on FBNK_LIMIT(CUSTOMER_NO);
create index IX_FBNK_AUDIT_CUSTOMER_TS on FBNK_EB_AUDIT_TRAIL(CUSTOMER_NO, EVENT_TS);

begin
  dbms_stats.gather_schema_stats('TEMENOS_TDM', cascade => true);
end;
/

prompt ================================================================
prompt Row counts - expected grand total: 50,000
prompt ================================================================
column TABLE_NAME format a32
column ROW_COUNT format 999,999,999

select 'FBNK_CUSTOMER' table_name, count(*) row_count from FBNK_CUSTOMER
union all select 'FBNK_CUSTOMER_CONTACT', count(*) from FBNK_CUSTOMER_CONTACT
union all select 'FBNK_ACCOUNT', count(*) from FBNK_ACCOUNT
union all select 'FBNK_AA_ARRANGEMENT', count(*) from FBNK_AA_ARRANGEMENT
union all select 'FBNK_AA_PROPERTY', count(*) from FBNK_AA_PROPERTY
union all select 'FBNK_STMT_ENTRY', count(*) from FBNK_STMT_ENTRY
union all select 'FBNK_FUNDS_TRANSFER', count(*) from FBNK_FUNDS_TRANSFER
union all select 'FBNK_TPH_PAYMENT_ORDER', count(*) from FBNK_TPH_PAYMENT_ORDER
union all select 'FBNK_LIMIT', count(*) from FBNK_LIMIT
union all select 'FBNK_EB_AUDIT_TRAIL', count(*) from FBNK_EB_AUDIT_TRAIL;

select sum(row_count) grand_total
from (
  select count(*) row_count from FBNK_CUSTOMER
  union all select count(*) from FBNK_CUSTOMER_CONTACT
  union all select count(*) from FBNK_ACCOUNT
  union all select count(*) from FBNK_AA_ARRANGEMENT
  union all select count(*) from FBNK_AA_PROPERTY
  union all select count(*) from FBNK_STMT_ENTRY
  union all select count(*) from FBNK_FUNDS_TRANSFER
  union all select count(*) from FBNK_TPH_PAYMENT_ORDER
  union all select count(*) from FBNK_LIMIT
  union all select count(*) from FBNK_EB_AUDIT_TRAIL
);

prompt ================================================================
prompt Referential-integrity checks - every value must be zero
prompt ================================================================
column CHECK_NAME format a42
select 'ACCOUNT_WITHOUT_CUSTOMER' check_name, count(*) failures
from FBNK_ACCOUNT a where not exists (select 1 from FBNK_CUSTOMER c where c.CUSTOMER_NO = a.CUSTOMER_NO)
union all
select 'ARRANGEMENT_WITHOUT_ACCOUNT', count(*)
from FBNK_AA_ARRANGEMENT r where not exists (select 1 from FBNK_ACCOUNT a where a.ACCOUNT_NO = r.ACCOUNT_NO)
union all
select 'PROPERTY_WITHOUT_ARRANGEMENT', count(*)
from FBNK_AA_PROPERTY p where not exists (select 1 from FBNK_AA_ARRANGEMENT r where r.ARRANGEMENT_ID = p.ARRANGEMENT_ID)
union all
select 'STMT_WITHOUT_ACCOUNT', count(*)
from FBNK_STMT_ENTRY s where not exists (select 1 from FBNK_ACCOUNT a where a.ACCOUNT_NO = s.ACCOUNT_NO)
union all
select 'FT_WITHOUT_DEBIT_ACCOUNT', count(*)
from FBNK_FUNDS_TRANSFER f where not exists (select 1 from FBNK_ACCOUNT a where a.ACCOUNT_NO = f.DEBIT_ACCOUNT_NO)
union all
select 'PAYMENT_WITHOUT_FT', count(*)
from FBNK_TPH_PAYMENT_ORDER p where not exists (select 1 from FBNK_FUNDS_TRANSFER f where f.FT_REFERENCE = p.FT_REFERENCE)
union all
select 'LIMIT_WITHOUT_CUSTOMER', count(*)
from FBNK_LIMIT l where not exists (select 1 from FBNK_CUSTOMER c where c.CUSTOMER_NO = l.CUSTOMER_NO);

prompt ================================================================
prompt Multi-value evidence (VM=253, SM=252, FM=254)
prompt ================================================================
select CUSTOMER_NO,
       replace(replace(MV_ID_NUMBER, to_char(unistr('\00FD')), '<VM>'), to_char(unistr('\00FC')), '<SM>') MV_IDENTIFIERS,
       replace(replace(MV_ADDRESS, to_char(unistr('\00FD')), '<VM>'), to_char(unistr('\00FC')), '<SM>') MV_ADDRESS_SAMPLE
from FBNK_CUSTOMER
where CUSTOMER_NO = 'C000000001';

select a.CUSTOMER_NO, a.ACCOUNT_NO, r.ARRANGEMENT_ID, p.PROPERTY_NAME,
       replace(replace(p.MV_ATTRIBUTE_VALUE, to_char(unistr('\00FD')), '<VM>'), to_char(unistr('\00FC')), '<SM>') PROPERTY_VALUES
from FBNK_ACCOUNT a
join FBNK_AA_ARRANGEMENT r on r.ACCOUNT_NO = a.ACCOUNT_NO
join FBNK_AA_PROPERTY p on p.ARRANGEMENT_ID = r.ARRANGEMENT_ID
where a.ACCOUNT_NO = '10000000000001';

prompt TEMENOS_TDM load and validation completed successfully.
exit
