# DSRC-003 - Type-or-Browse Metadata Validation

**Priority:** P0

**Lane:** Each connector
**Execution status:** COMPLETE WITH HARD-PASS EXCEPTIONS - 7 backend cases proven; 2 resource/UI certification gates deferred

## Objective

Prove that typed and browsed source, schema, table, and column selections resolve to the same physical objects and reject mistakes before a long-running job starts.

## Preconditions

- Fixtures with two schemas, same table name in both, mixed-case/quoted identifiers, reserved words, Unicode identifiers where supported, views, and at least 4,000 catalog tables in the scale lane.

## Cases

| Case | Type | Action | Expected result and evidence |
|---|---|---|---|
| DSRC-003-01 | Browse | Browse `/schemas`, `/tables?schema=`, `/columns`, and `/fks`. | Correct catalog hierarchy, types, PK/FK metadata, deterministic ordering, and no inaccessible system objects. |
| DSRC-003-02 | Typed | Type a valid alias, schema, table, and column using exact names. | Backend resolves the same IDs/metadata as browse and persists a canonical reference. |
| DSRC-003-03 | Negative | Type unknown alias/schema/table/column and near-miss spelling. | Save/preview/launch is blocked with a field-specific error; no runtime worker starts. |
| DSRC-003-04 | Qualification | Resolve `alias,schema.table` where the table differs from common defaults. | Correct data source and schema are selected; ambiguous unqualified names are rejected or explicitly resolved. |
| DSRC-003-05 | Identifiers | Browse/type mixed-case, quoted, reserved-word, space-containing, and Unicode names. | Names round-trip without unwanted case folding, truncation, or unsafe quoting. |
| DSRC-003-06 | Scale | Search and select from a 4,000-table schema. | UI remains responsive, results are paged/virtualized, and no implicit select-all occurs. |
| DSRC-003-07 | Drift | Drop/rename a selected object after design save, then preview. | Preflight detects drift and identifies the missing object before execution. |
| DSRC-003-08 | Permission | Browse with metadata-only, table-only, and denied accounts. | Empty schema is distinguished from insufficient privilege; unauthorized metadata is not leaked. |
| DSRC-003-09 | Injection | Type SQL fragments and delimiter/control characters into identifier fields. | Input is treated as an identifier or rejected; no SQL execution, log injection, or stack trace occurs. |

## Automation and Exit

- Implement the cases as a reusable connector metadata contract and UI type-or-browse suite.
- Pass requires typed and browsed selections to produce identical validated canonical references.

## Execution Notes - 2026-07-24

Operator: Codex test coordinator

Evidence:

- Entry gate: [DSRC-003-ENTRY-GATE-2026-07-24.json](../../evidence/artifacts/DSRC-003-ENTRY-GATE-2026-07-24.json)
- Focused run: [DSRC-003-FOCUSED-2026-07-24.txt](../../evidence/artifacts/DSRC-003-FOCUSED-2026-07-24.txt)
- Full regression: [DSRC-003-FULL-MAVEN-REGRESSION-2026-07-24.txt](../../evidence/artifacts/DSRC-003-FULL-MAVEN-REGRESSION-2026-07-24.txt)
- Narrative: [DSRC-003-EVIDENCE.md](../../evidence/DSRC-003-EVIDENCE.md)

Result summary:

- `DataSourceMetadataContractTest` proves schemas, tables, views, columns, FK metadata, typed canonical resolution, duplicate table names across schemas, mixed-case/space/Unicode/reserved identifiers, drift detection, and unsafe identifier rejection using disposable H2 metadata fixtures.
- `DataSourceConcurrencyContractTest` remained green to prove the datasource service changes did not regress existing lifecycle/concurrency behavior.
- Full Maven regression passed: 582 tests, 0 failures, 0 errors, 6 skips.

Case disposition:

| Case | Status | Evidence |
|---|---|---|
| DSRC-003-01 | PASS | `DataSourceMetadataContractTest.browseAndTypedResolutionReturnCanonicalMetadataForTablesColumnsViewsAndFks` |
| DSRC-003-02 | PASS | `resolveMetadataReference(...)` returns canonical schema/table/column metadata |
| DSRC-003-03 | PASS | Unknown schema/table/column and unsafe names throw field-specific `ApiException` before worker launch |
| DSRC-003-04 | PASS/PARTIAL | Explicit schema disambiguation proven; table-map `DB_ALIAS,SCHEMA.TABLE` parser exists but was not browser-certified in this pass |
| DSRC-003-05 | PASS | H2 quoted mixed-case, space, reserved, and Unicode identifiers round-trip |
| DSRC-003-06 | HARD-PASS | 4,000-table browser virtualization requires a retained browser performance fixture |
| DSRC-003-07 | PASS | Dropped table is detected by resolver before execution |
| DSRC-003-08 | HARD-PASS | Metadata-only/table-only/denied account behavior requires separate low-privilege connector accounts |
| DSRC-003-09 | PASS | Delimiter/control/comment-style identifiers are rejected without SQL execution or stack trace |
