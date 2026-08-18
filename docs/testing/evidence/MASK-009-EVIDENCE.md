# MASK-009 Evidence - US address format, state coverage, and deterministic consistency

Date: 2026-07-24
Operator: Codex test coordinator
Story: MASK-009

## Scope

Validated US address masking and synthetic geography coverage using bundled synthetic seed lists
and reference-engine tests. No external address service, production address, or customer row was used.

## Code Changes Verified

- `MaskingEngineTest.usAddressPreservesEverySupportedState` proves every supported US state can
  be preserved by `ADDRESS_US` while generating a masked full address.
- `MaskingEngineTest.usAddressPartsComeFromTheSameMaskedAddress` proves `FULL`, `LINE1`, `CITY`,
  `STATE`, and `ZIP` outputs are aligned for the same source value.
- Existing `GeneratorsTest.usAddressSeedPoolCoversEveryStateWithMultipleRows` proves the bundled
  `cities_us.csv` pool covers all 50 states with at least five rows per state plus DC coverage.
- Existing `GeneratorsTest.rowIndexedGeoColumnsStayCoherent` proves row-indexed synthetic
  geography parts stay aligned.

## Executed Evidence

Focused command:

```powershell
mvn "-Dtest=MaskingEngineTest,GeneratorsTest,MaskingFunctionCatalogAcceptanceTest" test
```

Focused result:

- Tests run: 60
- Failures: 0
- Errors: 0
- Skipped: 0
- Build result: SUCCESS

Focused artifact:

- `docs/testing/evidence/artifacts/MASK-009-FOCUSED-2026-07-24.txt`

Regression command:

```powershell
mvn test
```

Regression result:

- Tests run: 576
- Failures: 0
- Errors: 0
- Skipped: 6
- Build result: SUCCESS

Regression artifact:

- `docs/testing/evidence/artifacts/MASK-009-FULL-MAVEN-REGRESSION-2026-07-24.txt`

## Acceptance Mapping

| Acceptance | Evidence |
|---|---|
| 50-state seed pool coverage | `GeneratorsTest.usAddressSeedPoolCoversEveryStateWithMultipleRows` |
| DC coverage | `GeneratorsTest.usAddressSeedPoolCoversEveryStateWithMultipleRows` |
| Street pool variation | `GeneratorsTest.usAddressSeedPoolCoversEveryStateWithMultipleRows` |
| Generator geography coherence | `GeneratorsTest.rowIndexedGeoColumnsStayCoherent` |
| `CITY_STATE_ZIP` coherence | `MaskingEngineTest.geoTripletIsCoherent`, `geoCanPreserveState` |
| Preserve every supported state | `MaskingEngineTest.usAddressPreservesEverySupportedState` |
| Full address format | `MaskingEngineTest.usAddressCanPreserveStateAndStayCoherent`, `usAddressPreservesEverySupportedState` |
| Part alignment | `MaskingEngineTest.usAddressPartsComeFromTheSameMaskedAddress` |

## Notes

The full regression output includes two suppressed `[api]` log lines from negative exception-handling
tests. They are expected evidence that API errors are redacted and do not represent failed tests.
