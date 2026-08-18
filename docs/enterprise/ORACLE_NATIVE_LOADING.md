# Oracle Enterprise Native Loading

ForgeTDM uses a governed Oracle SQL*Loader lane for large scalar tables and a bounded JDBC streaming lane for LOB, binary, XML, time-zone-sensitive, update, and merge workloads. The selected route and its reason are retained in each DataScope run.

## Required client setup

Install Oracle Instant Client Tools (including `sqlldr`) on every ForgeTDM worker that can execute Oracle loads. Configure:

```powershell
$env:FORGETDM_ORACLE_SQLLOADER_ENABLED = "true"
$env:FORGETDM_ORACLE_SQLLOADER_BIN = "C:\oracle\instantclient_23_6\sqlldr.exe"
$env:FORGETDM_ORACLE_SQLLOADER_CONNECT = "//oracle-host:1521/service"
$env:FORGETDM_ORACLE_SQLLOADER_AUTH = "PASSWORD_STDIN"
$env:FORGETDM_NATIVE_LOAD_EVIDENCE_DIR = "D:\forgetdm-evidence\native-loader"
$env:FORGETDM_NATIVE_LOAD_EVIDENCE_RETENTION_DAYS = "365"
```

`FORGETDM_ORACLE_SQLLOADER_AUTH` supports:

- `PASSWORD_STDIN`: uses the data-source username and supplies the password only through process stdin. Passwords are never written to control/parameter files or retained commands.
- `WALLET`: uses `FORGETDM_ORACLE_SQLLOADER_CONNECT` as a TNS alias and authenticates with `userid=/@alias`.
- `OS_AUTH`: uses Oracle operating-system authentication with `userid=/`.

For production, prefer Oracle Wallet or an approved external-secret injection mechanism. Restrict the service account and evidence directory to the ForgeTDM worker identity.

## Governed profiles

| Profile | Oracle path | Redo/recovery contract | Intended use |
|---|---|---|---|
| `AUTO` | Direct recoverable for eligible scalar append/replace tables | Recoverable | Default |
| `DIRECT_RECOVERABLE` | SQL*Loader direct path | Recoverable direct load | High-volume standard loads |
| `DIRECT_MINIMAL_REDO` | Direct path with `UNRECOVERABLE` | Rebuild or post-load backup required | Approved reloadable test targets |
| `CONVENTIONAL_SAFE` | SQL*Loader conventional path | Recoverable | Compatibility-sensitive scalar loads |

Minimal redo is fail-closed. It additionally requires:

```powershell
$env:FORGETDM_ORACLE_MINIMAL_REDO_ALLOWED = "true"
```

Do not enable it without an approved recovery procedure. The run evidence records whether a load was recoverable.

## Runtime behavior

1. DataScope streams source rows through bounded server-cursor fetches.
2. Masked rows are committed in restartable checkpoint chunks.
3. Eligible scalar append/replace chunks are staged in owner-only temporary files and loaded through SQL*Loader.
4. CLOB/BLOB/NCLOB/SQLXML, binary, time-zone-sensitive, update, and merge workloads use bounded JDBC streaming.
5. A native failure retains a sanitized loader log and immutable evidence manifest; raw bad records are hashed but not retained.
6. The transient data/control/parameter files are deleted after the chunk completes or fails.
7. Failed chunks retry within the configured limit; committed chunks remain restart checkpoints.

Oracle partition exchange remains available as the highest-isolation cutover pattern: load and validate the staging table, then exchange the approved target partition.

## Evidence and operations

The DataScope run monitor shows:

- actual loader and route reason;
- Oracle profile and recoverability;
- authentication mode (never credentials);
- loaded and rejected row counts;
- exit status and checkpoint;
- evidence manifest hash and retained path.

Each evidence directory contains a sanitized `sqlldr.log` and `manifest.properties`. The manifest records job/chunk/table identity, timestamps, profile, status, row counts, and SHA-256 hashes for the control, log, and bad-file output. Raw `.bad` data is not retained by default.

Evidence packages expire according to `FORGETDM_NATIVE_LOAD_EVIDENCE_RETENTION_DAYS` (365 by default). Set it to `0` for indefinite retention. Place a `.hold` file in an evidence package to suspend automated purge for an investigation or legal hold.

## Production gate

Before enabling `NATIVE` mode for a target:

1. Confirm `sqlldr` readiness on every worker from Data Sources > Native loader workspace.
2. Validate wallet or stdin authentication without credentials in process arguments or files.
3. Test quoted identifiers, dates/timestamps, Unicode, nulls, constraints, partitions, and representative row widths.
4. Test reject-zero failure, chunk retry, process restart, and resume from the last committed checkpoint.
5. Reconcile source, staged, loaded, rejected, and target counts.
6. Validate evidence retention, access control, backup/rebuild policy, and purge policy.
7. Certify both the native lane and the JDBC LOB streaming lane for the Oracle version in use.
