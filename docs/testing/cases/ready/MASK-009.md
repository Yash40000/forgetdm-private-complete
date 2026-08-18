# MASK-009 - US address format, state coverage, and deterministic consistency

**Area:** Masking
**Priority:** P1
**Lane:** Reference engine and seed-list coverage
**Execution status:** COMPLETE - 8/8 PASS

## Objective

Prove that ForgeTDM address masking and synthetic geography generation produce coherent,
deterministic, non-source US addresses with broad state coverage. The goal is realistic test
addresses without leaking real customer addresses or breaking downstream state/ZIP validations.

## Acceptance Cases

| Case | Expected result | Result |
|---|---|---|
| MASK-009-01 | US city/state/ZIP seed pool covers all 50 states with multiple rows per state | PASS |
| MASK-009-02 | DC coverage exists as an additional supported jurisdiction | PASS |
| MASK-009-03 | Street seed pool has enough variation for useful non-source masked addresses | PASS |
| MASK-009-04 | `CITY`, `STATE`, `ZIP`, and `GEO_TRIPLET` generators stay coherent for the same row | PASS |
| MASK-009-05 | `CITY_STATE_ZIP` masking returns coherent city/state/ZIP output | PASS |
| MASK-009-06 | `PRESERVE_STATE` keeps every supported state while changing the rest of the address | PASS |
| MASK-009-07 | `ADDRESS_US` full output has street, apartment, city, state, ZIP, and USA format | PASS |
| MASK-009-08 | `ADDRESS_US` part outputs come from the same deterministic masked address | PASS |

## Result

- Added masking-level coverage proving every supported US state can be preserved by
  `ADDRESS_US` without falling back to the wrong state.
- Added deterministic part-alignment coverage so `FULL`, `LINE1`, `CITY`, `STATE`, and `ZIP`
  are proven to describe the same masked address for the same source value.
- Existing generator coverage already proved all states plus DC have multiple city/state/ZIP rows
  and that row-indexed geography columns stay coherent.
- Focused backend run passed 60 tests with zero failures and zero errors:
  `MaskingEngineTest`, `GeneratorsTest`, and `MaskingFunctionCatalogAcceptanceTest`.
- Full backend regression passed 576 tests with zero failures and zero errors; six unrelated
  environment-gated live tests remained skipped.
- No HARD-PASS was required for the reference-engine acceptance cases.

## Evidence

- Focused backend evidence: [MASK-009-FOCUSED-2026-07-24.txt](../../evidence/artifacts/MASK-009-FOCUSED-2026-07-24.txt)
- Full backend regression: [MASK-009-FULL-MAVEN-REGRESSION-2026-07-24.txt](../../evidence/artifacts/MASK-009-FULL-MAVEN-REGRESSION-2026-07-24.txt)

## Execution Rules

- Use synthetic addresses only.
- Treat generated geography as test data, not postal-certified deliverable mail addresses.
- Keep source state only when `PRESERVE_STATE` is explicitly requested.
- Do not retain customer addresses, production row samples, or credentials in evidence.
