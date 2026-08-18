# MASK-008 Evidence - Direct lookup and hash lookup behavior

Date: 2026-07-24
Operator: Codex test coordinator
Story: MASK-008

## Scope

Validated lookup masking contracts using inline synthetic definitions and a local H2-backed
`masking_lookup_values` fixture shaped like the governed PostgreSQL lookup catalog.

## Code Changes Verified

- `MaskingEngineTest` now covers direct lookup duplicate-key rejection, invalid fallback rejection,
  explicit default/null fallback behavior, hash duplicate-key rejection, reserved-key duplicate
  rejection, mixed keyed/unkeyed row rejection, and missing reserved row rejection.
- `MaskingLookupCatalogTest` now covers duplicate relational direct rows, duplicate relational hash
  keys, and missing relational replacement values.
- `ValueListService` now validates `replacement_value` before assembling relational lookup entries,
  producing a controlled API validation error for broken catalog rows.

## Executed Evidence

Focused command:

```powershell
mvn "-Dtest=MaskingEngineTest,MaskingLookupCatalogTest,PolicyRuleValidationTest,MaskingFunctionCatalogAcceptanceTest" test
```

Focused result:

- Tests run: 53
- Failures: 0
- Errors: 0
- Skipped: 0
- Build result: SUCCESS

Focused artifact:

- `docs/testing/evidence/artifacts/MASK-008-FOCUSED-2026-07-24.txt`

Regression command:

```powershell
mvn test
```

Regression result:

- Tests run: 574
- Failures: 0
- Errors: 0
- Skipped: 6
- Build result: SUCCESS

Regression artifact:

- `docs/testing/evidence/artifacts/MASK-008-FULL-MAVEN-REGRESSION-2026-07-24.txt`

## Acceptance Mapping

| Acceptance | Evidence |
|---|---|
| Exact direct lookup mapping | `MaskingEngineTest.directLookupSupportsExactCompositeAndFailClosedMappings` |
| Trim/case normalization | `MaskingEngineTest.directLookupSupportsExactCompositeAndFailClosedMappings` |
| Composite source keys | `MaskingEngineTest.directLookupSupportsExactCompositeAndFailClosedMappings` |
| Missing-key fallback behavior | `MaskingEngineTest.directLookupSupportsExactCompositeAndFailClosedMappings`, `directLookupRejectsAmbiguousKeysAndUnknownFallbacks` |
| Duplicate direct keys rejected | `MaskingEngineTest.directLookupRejectsAmbiguousKeysAndUnknownFallbacks`, `MaskingLookupCatalogTest.relationalLookupsFailClosedForAmbiguousOrBrokenRows` |
| Deterministic hash lookup | `MaskingEngineTest.hashLookupSupportsSequentialAndReservedOptimKeys`, `hashLookupSupportsMultiColumnDestination` |
| Reserved hash keys | `MaskingEngineTest.hashLookupSupportsSequentialAndReservedOptimKeys`, `MaskingFunctionCatalogAcceptanceTest` |
| Malformed hash lookup rows rejected | `MaskingEngineTest.hashLookupRejectsAmbiguousOrMalformedLookupTables`, `MaskingLookupCatalogTest.relationalLookupsFailClosedForAmbiguousOrBrokenRows` |
| Governed relational lookup catalog | `MaskingLookupCatalogTest.relationalHashLookupLoadsSequentialAndReservedRows`, `relationalDirectLookupAndReferenceCatalogAreUsable` |

## Notes

The full regression output includes two suppressed `[api]` log lines from negative exception-handling
tests. They are expected evidence that API errors are redacted and do not represent failed tests.
