# Db2 for z/OS native LOAD

ForgeTDM can deliver staged rows to Db2 for z/OS with a physical DSNUTILB `LOAD` job. This path is separate from Db2 LUW: it does not call a local `db2 load` executable and it does not turn millions of rows into JDBC `INSERT` statements.

## Runtime flow

1. ForgeTDM streams the prepared tab-delimited UTF-8 file to a temporary VB data set through z/OSMF RESTfiles.
2. It submits generated DSNUTILB JCL through z/OSMF RESTjobs.
3. It polls JES until a return code is available, with configured timeout and cancellation.
4. It collects JES spool files, parses return code and row metrics, and stores the JCL, LOAD control statement, input hash, and spool as run evidence.
5. Successful runs may remove temporary host data sets. Failed runs retain their names for diagnosis.

The upload uses z/OSMF record framing: each logical UTF-8 row is preceded by a four-byte big-endian record length. This preserves data-set record boundaries without building the full payload in heap.

## Prerequisites

- A ForgeTDM data source with engine `DB2ZOS` and target capability.
- A ZOWE mainframe connection configured for the same LPAR. Vault-backed credentials are recommended.
- z/OSMF RESTfiles and RESTjobs access for the service account.
- JES authority to submit, inspect, cancel, and read spool for the generated jobs.
- Data-set create/write/delete authority under the configured work HLQ.
- Db2 utility authority for the target objects and the site DSNUTILB procedure, normally `DSNUPROC`.
- Network and TLS trust between ForgeTDM and z/OSMF.

Db2 utility privileges and RACF profiles are site-specific. The readiness test proves z/OSMF and JES visibility; the first controlled non-production LOAD proves DSNUTILB and object authority.

## Configure in the UI

Open **Data Sources**, then **Native loaders**. On the **DB2ZOS** card select **Configure z/OS LOAD**.

Configure:

| Field | Purpose |
|---|---|
| Db2 z/OS target | JDBC target whose provisioning loads use this profile |
| z/OSMF connection | Existing Vault-backed ZOWE connection |
| Db2 subsystem | `SYSTEM` value passed to DSNUPROC |
| Work data-set HLQ | One to three qualifiers for temporary SYSREC/SYSDISC/SYSERR/SYSMAP data sets |
| Utility procedure | Site utility procedure, normally `DSNUPROC` |
| JES classes | Site-approved job and message classes |
| JES accounting information | Optional site-approved accounting value for the JOB card, for example `(ACCT)` |
| Work unit | Utility data-set unit, commonly `SYSDA` |
| Logging policy | Recoverable or explicitly approved minimal logging |
| Maximum return code | `0` for strict acceptance; at most `4` for an approved warning policy |
| Poll and timeout | JES monitoring controls |
| Cleanup | Remove temporary data sets only after successful acceptance |

Save the profile, then run **Test z/OSMF readiness**.

## Load semantics

| ForgeTDM action | Db2 utility behavior |
|---|---|
| Insert / append | `RESUME YES` |
| Replace / truncate-before-load | `REPLACE REUSE` |
| Update / merge / upsert | JDBC/SQL path; rejected by native preflight |

Recoverable mode emits `LOG YES`.

Minimal-logging mode emits `LOG NO NOCOPYPEND`. This lowers utility logging but changes recovery posture and therefore requires explicit Db2 operations approval. ForgeTDM does not claim that `NOCOPYPEND` replaces a site backup or recoverability policy.

## Safety boundaries

- A physical input record may not exceed 32,752 bytes in this first implementation. Oversized rows are rejected before any host allocation or job submission.
- The current path is intended for ordinary delimited scalar columns. Very large LOB/XML values that need Db2 separate-file LOAD conventions should remain on the streaming JDBC path until a site-specific LOB profile is configured.
- Identifiers are validated before JCL generation.
- Credentials never appear in JCL, command evidence, or spool artifacts.
- A timeout or interrupted run requests JES cancellation.
- Return-code acceptance and warning policy are explicit per target.

## Evidence and diagnosis

Each native run records:

- target, subsystem, profile, z/OSMF connection, and logging policy;
- expected rows, maximum record size, and staged-file SHA-256;
- generated data-set names, JCL, and LOAD control statement;
- JES job name/id/status and return code;
- parsed loaded/rejected counts when DSNUTILB reports them;
- all available spool DD content;
- remote cleanup or retention decision.

On failure, start with the first DSNU error in spool, then verify SYSERR/SYSDISC and the retained remote data-set names.

## IBM references

- [Db2 13 LOAD control statement syntax](https://www.ibm.com/docs/en/db2-for-zos/13.0.0?topic=load-syntax-options-control-statement)
- [Db2 13 LOAD control statement examples](https://www.ibm.com/docs/en/db2-for-zos/13.0.0?topic=load-sample-control-statements)
- [Replacing data with LOAD](https://www.ibm.com/docs/en/db2-for-zos/13.0.0?topic=load-replacing-data)
- [How LOAD loads Db2 tables](https://www.ibm.com/docs/en/db2-for-zos/13.0.0?topic=utility-how-load-loads-db2-tables)
- [Improving LOAD performance](https://www.ibm.com/docs/en/db2-for-zos/13.0.0?topic=load-improving-performance)
