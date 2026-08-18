# Temenos-style Oracle lab

This lab creates an isolated Oracle schema named `TEMENOS_TDM`. It does not
modify `BE_CARDS` or any other existing schema.

The model is synthetic and intentionally resembles common Temenos Transact,
Arrangement Architecture (AA), TAFJ, and TPH persistence patterns. It is not a
copy of a customer or vendor schema.

## Connection

- JDBC URL: `jdbc:oracle:thin:@localhost:1521:XE`
- Schema/user: `TEMENOS_TDM`
- Password: `ForgeTdm2026`

## Data model

| Table | Rows | Purpose |
|---|---:|---|
| `FBNK_CUSTOMER` | 5,000 | Party, KYC, identity, address, phone, TAFJ-style record |
| `FBNK_CUSTOMER_CONTACT` | 4,000 | Typed and effective-dated customer contacts |
| `FBNK_ACCOUNT` | 7,000 | Multi-currency accounts, balances, IBANs, signatories |
| `FBNK_AA_ARRANGEMENT` | 7,000 | AA products and customer/account ownership |
| `FBNK_AA_PROPERTY` | 7,000 | Effective-dated AA property attributes |
| `FBNK_STMT_ENTRY` | 10,000 | Debit/credit postings and narratives |
| `FBNK_FUNDS_TRANSFER` | 3,000 | Domestic/cross-border transfer instructions |
| `FBNK_TPH_PAYMENT_ORDER` | 3,000 | TPH lifecycle and ISO 20022-like payment messages |
| `FBNK_LIMIT` | 2,000 | Customer/account limit utilization and collateral |
| `FBNK_EB_AUDIT_TRAIL` | 2,000 | Versioned record operations and channel evidence |
| **Total** | **50,000** | Exactly ten connected tables |

Unicode `U+00FD`, `U+00FC`, and `U+00FE` represent value marks, sub-value marks,
and field marks in selected fields and CLOB records. The loader uses
`TO_CHAR(UNISTR(...))` so these marks survive Oracle `AL32UTF8`. The load also includes
foreign keys, checks, unique constraints, operational indexes, deterministic
synthetic identities, realistic Qatar banking values, AA relationships,
financial entries, payment messages, and audit history.

## Rebuild

Run the load as a local Oracle administrator:

```powershell
C:\oraclexe\app\oracle\product\11.2.0\server\bin\sqlplus.exe / as sysdba `@D:\forgetdm - Copy\demo\temenos-oracle\temenos-core-50k.sql`
```

The script is a first-time loader. It creates `TEMENOS_TDM` and fails safely if
that schema already exists; it never drops an existing schema. It then validates
the total count and referential integrity.

## Verify without rebuilding

```powershell
C:\oraclexe\app\oracle\product\11.2.0\server\bin\sqlplus.exe TEMENOS_TDM/ForgeTdm2026@XE `@D:\forgetdm - Copy\demo\temenos-oracle\verify-temenos-core.sql`
```
