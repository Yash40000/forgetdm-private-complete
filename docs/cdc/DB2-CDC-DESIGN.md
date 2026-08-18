# IBM Db2 LUW / UDB CDC

**Status:** implemented and live-tested with IBM Db2 SQL Replication.

ForgeTDM uses IBM's `asncap` Capture program for Db2 LUW/UDB. Capture reads committed changes
from the Db2 recovery log and writes them to registered change-data (CD) tables. Forge reads only
those CD tables over JDBC, preserving IBM commit order and checkpoints; it does not poll or rescan
the source business tables.

Db2 for z/OS is intentionally excluded from this provider. It needs a separately qualified capture
and operational model.

## Runtime flow

1. A DBA enables archive logging and `DATA CAPTURE CHANGES` through IBM SQL Replication.
2. `asncap` reads the recovery log and writes committed `I`, `U`, and `D` row images to CD tables.
3. Forge preflight verifies active registrations in `ASN.IBMSNAP_REGISTER` and a current Capture
   checkpoint in `ASN.IBMSNAP_RESTART`.
4. Enabling CDC stores the current 32-character Db2 commit sequence as Forge's starting checkpoint.
5. Each poll merges the registered CD-table streams by `IBMSNAP_COMMITSEQ` and
   `IBMSNAP_INTENTSEQ`, without splitting a commit across poll batches.
6. Forge buffers decoded changes with source schema/table, operation, values, and primary-key
   columns. Existing TimeFlow and incremental-provisioning services consume that common stream.

## One-time DBA setup

### 1. Enable recoverable logging

`LOGARCHMETH1` must use an archive method such as `DISK:<path>` rather than circular logging.
Follow the normal Db2 backup procedure after changing database logging configuration.

### 2. Create Capture control tables and registrations

Run through IBM `asnclp` using the required source database:

```text
ASNCLP SESSION SET TO SQL REPLICATION;
SET RUN SCRIPT NOW STOP ON SQL ERROR ON;
SET SERVER CAPTURE TO DB FORGETDM;
CREATE CONTROL TABLES FOR CAPTURE SERVER;
CREATE REGISTRATION (APP.CUSTOMER) DIFFERENTIAL REFRESH STAGE APP.CD_CUSTOMER;
CREATE REGISTRATION (APP.ACCOUNT)  DIFFERENTIAL REFRESH STAGE APP.CD_ACCOUNT;
QUIT;
```

Use enterprise naming, storage, retention, and tablespace standards for production CD tables.

### 3. Start IBM Capture

```text
asncap CAPTURE_SERVER=FORGETDM CAPTURE_SCHEMA=ASN STARTMODE=WARMSI \
  LOGSTDOUT=Y AUTOPRUNE=Y RETENTION_LIMIT=10080
```

Start registrations through the standard Apply-program handshake or an IBM-supported `CAPSTART`
signal. Forge deliberately does not start or stop shared IBM registrations.

### 4. Grant least-privilege access

The Forge source account needs:

- `CONNECT` to the source database.
- `SELECT` on `ASN.IBMSNAP_REGISTER`, `ASN.IBMSNAP_RESTART`, and `ASN.IBMSNAP_UOW`.
- `SELECT` on every registered CD table used by its capture scope.
- Metadata visibility sufficient to read primary-key definitions for registered source tables.

No source-table write privilege is required for CDC.

## Forge configuration

The default Capture schema is `ASN`. Override it only when the DBA uses another schema:

```properties
forgetdm.cdc.db2-capture-schema=ASN
```

In **Change Data Capture**:

1. Select the Db2 LUW/UDB data source.
2. Confirm readiness reports active IBM registrations and a healthy Capture checkpoint.
3. Enter the source schema and optionally limit the table list.
4. Enable capture. Forge rejects unregistered or stopped requested tables before the run starts.
5. Monitor rows captured, committed-UOW lag, last poll, and buffered changes.

## Checkpoint and transaction guarantees

| Forge concept | IBM Db2 SQL Replication value |
|---|---|
| Capture mechanism | IBM `asncap` over the recovery log |
| Stored checkpoint | Hex `IBMSNAP_COMMITSEQ` |
| In-commit ordering | Hex `IBMSNAP_INTENTSEQ` |
| Operation | `IBMSNAP_OPERATION` (`I`, `U`, `D`) |
| Lag | Captured UOW commits after Forge's confirmed checkpoint |
| Primary key | Source-table JDBC metadata applied to the CD row image |
| Disable behavior | Stops Forge consumption; leaves DBA-owned registrations running |

Forge determines a global commit boundary across all requested registrations before fetching row
images. When the row limit lands inside a multi-row commit, Forge consumes the complete commit so
restart and cross-table ordering remain coherent.

## Verification retained in the project

- `Db2SqlReplicationCdcProviderTest`: engine routing, operation decoding, and checkpoint format.
- `Db2SqlReplicationCdcProviderIntegrationTest`: environment-gated live test against IBM Capture.
- The live test inserts, updates, and deletes one temporary customer, verifies all three committed
  operations and the `CUSTOMER_ID` primary key, and removes the test row in `finally`.

Run the live proof with:

```powershell
$env:FORGETDM_DB2_CDC_URL  = 'jdbc:db2://localhost:50000/FORGETDM'
$env:FORGETDM_DB2_CDC_USER = 'db2inst1'
$env:FORGETDM_DB2_CDC_PASS = '<secret>'
mvn -Dtest=Db2SqlReplicationCdcProviderIntegrationTest test
```

## Operational guardrails

- Monitor `asncap`, CD-table growth, archive-log retention, checkpoint age, and Forge committed-UOW
  lag together.
- Size CD tables for peak transaction volume plus the recovery objective.
- Use IBM retention/pruning controls only after all consumers' recovery requirements are known.
- Treat Capture registration changes as controlled DBA releases; a stopped or missing registration
  blocks Forge enablement rather than silently degrading to source-table polling.
