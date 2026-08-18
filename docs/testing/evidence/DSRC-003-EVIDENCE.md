# DSRC-003 Evidence - Type-or-Browse Metadata Validation

Date: 2026-07-24
Operator: Codex test coordinator
Story: DSRC-003

## Scope

Validated typed-vs-browsed metadata resolution using disposable local H2 schemas and
synthetic metadata only. No production connection, customer schema, paid service, or
external database was used.

## Code Changes Verified

- Added `GET /api/datasources/{id}/resolve` as the canonical typed metadata resolver.
- Added `DataSourceService.resolveMetadataReference(...)` so typed schema/table/column
  values resolve to the same physical metadata returned by browse.
- Generic JDBC table browse now includes views, sorts deterministically, and rejects
  unknown schemas before returning an ambiguous empty list.
- Column/FK reads now reject unsafe identifiers and detect missing table drift before
  a long-running preview/provision worker can start.

## Executed Evidence

Entry gate:

- `docs/testing/evidence/artifacts/DSRC-003-ENTRY-GATE-2026-07-24.json`

Focused command:

```powershell
mvn "-Dtest=DataSourceMetadataContractTest,DataSourceConcurrencyContractTest" test
```

Focused result:

- Tests run: 8
- Failures: 0
- Errors: 0
- Skipped: 0
- Build result: SUCCESS

Focused artifact:

- `docs/testing/evidence/artifacts/DSRC-003-FOCUSED-2026-07-24.txt`

Regression command:

```powershell
mvn test
```

Regression result:

- Tests run: 582
- Failures: 0
- Errors: 0
- Skipped: 6
- Build result: SUCCESS

Regression artifact:

- `docs/testing/evidence/artifacts/DSRC-003-FULL-MAVEN-REGRESSION-2026-07-24.txt`

## Acceptance Mapping

| Acceptance | Result | Evidence |
|---|---|---|
| Browse schema/table/view/column/FK hierarchy | PASS | `DataSourceMetadataContractTest.browseAndTypedResolutionReturnCanonicalMetadataForTablesColumnsViewsAndFks` |
| Typed valid schema/table/column resolves canonical metadata | PASS | `resolveMetadataReference(...)` assertions for `APP_A.customers.Customer Name` |
| Unknown schema/table/column rejected before worker launch | PASS | `typedMistakesAmbiguityDriftAndUnsafeIdentifiersFailBeforeExecution` |
| Duplicate table names across schemas require explicit schema | PASS | `duplicateTableNamesAcrossSchemasRequireExplicitSchemaAndRoundTripSpecialIdentifiers` |
| Mixed-case, space, reserved, and Unicode identifiers round-trip | PASS | H2 quoted identifier fixture assertions |
| 4,000-table browser virtualization | HARD-PASS | Requires retained browser/performance fixture; not certified by backend unit evidence |
| Drift after saved design | PASS | Dropped table rejected by resolver before execution |
| Metadata-only/table-only/denied account distinction | HARD-PASS | Requires low-privilege connector accounts not present in this run |
| SQL fragment/control character rejection | PASS | Unsafe typed identifier returns field-specific `BAD_REQUEST` without stack trace |

## Notes

`HARD-PASS` items are explicit certification gaps, not functional green claims. The backend
contract now gives UI and engine flows a safe preflight resolver; a later browser pass should
prove virtualized catalog responsiveness and low-privilege account handling.
