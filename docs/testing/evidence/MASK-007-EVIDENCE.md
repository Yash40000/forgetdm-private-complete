# MASK-007 Evidence - Payment-card validity and uniqueness

Date: 2026-07-24
Operator: Codex test coordinator
Story: MASK-007

## Scope

Validated payment-card generation and masking contracts using synthetic reference values only.

## Code Changes Verified

- `io.forgetdm.core.synth.Generators` now supports Visa, Mastercard, Amex, Discover, JCB, Diners Club, and UnionPay payment-card generators.
- `Generators.cardCapacity`, `Generators.cardLength`, and `Generators.isCardGenerator` expose the finite uniqueness domain for preflight validation.
- `SyntheticRangeChecks` applies capacity, target length, and numeric precision checks to every supported card generator.
- `SyntheticGenService` treats every supported card generator as uniqueness-sensitive during generation.
- UI fallback generator lists include the expanded card set where hardcoded fallback lists exist.

## Executed Evidence

Command:

```powershell
mvn "-Dtest=GeneratorsTest,MaskingEngineTest" test
```

Result:

- Tests run: 53
- Failures: 0
- Errors: 0
- Skipped: 0
- Build result: SUCCESS

Artifact:

- `docs/testing/evidence/artifacts/MASK-007-FOCUSED-2026-07-24.txt`

Regression command:

```powershell
mvn test
```

Regression result:

- Tests run: 571
- Failures: 0
- Errors: 0
- Skipped: 6
- Build result: SUCCESS

Regression artifact:

- `docs/testing/evidence/artifacts/MASK-007-FULL-MAVEN-REGRESSION-2026-07-24.txt`

Entry gate artifact:

- `docs/testing/evidence/artifacts/MASK-007-ENTRY-GATE-2026-07-24.json`

## Acceptance Mapping

| Acceptance | Evidence |
|---|---|
| Brand prefix, length, and Luhn validity | `GeneratorsTest.allCardNetworksAreLuhnValidAndCollisionFree` |
| Collision-free generated values | `GeneratorsTest.allCardNetworksAreLuhnValidAndCollisionFree`, 100,000 rows per supported generator |
| Explicit capacity and overflow refusal | `GeneratorsTest.cardCapacityMatchesEverySupportedNetworkAndRejectsOverflow` |
| Deterministic replay across workers | `GeneratorsTest.cardGenerationReplaysAcrossWorkersAndChangesWithSeed` |
| Seed changes output | `GeneratorsTest.cardGenerationReplaysAcrossWorkersAndChangesWithSeed` |
| Masking keeps Luhn and format | `MaskingEngineTest.creditCardKeepsBinAndLuhnAndSeparators`, `MaskingEngineTest.creditCardSupportsMaskingModes` |
| Masking collision resistance | `MaskingEngineTest.creditCardPreserveBinIsCollisionFreeAtScale`, `creditCardRandomBinIsCollisionFreeAtScale`, `creditCardKeepLastFourIsCollisionFreeAtScale`, `malformedCardShapedIdentifiersRemainMaskedAndCollisionFree` |

## Notes

`npm.cmd run typecheck` was attempted after the small UI fallback-list edit. It failed in generated
Next cache files under `.next/dev/types` with syntax errors that are unrelated to the payment-card
changes. The generated file had duplicated/truncated type text while a Node process was active. This
is not counted as MASK-007 evidence and should be handled as a separate frontend-build hygiene item.
