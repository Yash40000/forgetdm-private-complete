whenever sqlerror exit sql.sqlcode
set pagesize 200
set linesize 220
set feedback off

connect TEMENOS_TDM/ForgeTdm2026@XE

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

column CONSTRAINT_TYPE format a18
select constraint_type, count(*) constraint_count
from user_constraints
where table_name like 'FBNK_%'
group by constraint_type
order by constraint_type;

column CUSTOMER_NO format a12
column ACCOUNT_NO format a16
column ARRANGEMENT_ID format a16
column FT_REFERENCE format a24
column PAYMENT_ORDER_ID format a18

select c.CUSTOMER_NO, a.ACCOUNT_NO, r.ARRANGEMENT_ID, f.FT_REFERENCE, p.PAYMENT_ORDER_ID
from FBNK_CUSTOMER c
join FBNK_ACCOUNT a on a.CUSTOMER_NO = c.CUSTOMER_NO
join FBNK_AA_ARRANGEMENT r on r.ACCOUNT_NO = a.ACCOUNT_NO
join FBNK_FUNDS_TRANSFER f on f.DEBIT_ACCOUNT_NO = a.ACCOUNT_NO
join FBNK_TPH_PAYMENT_ORDER p on p.FT_REFERENCE = f.FT_REFERENCE
where rownum <= 5;

exit
