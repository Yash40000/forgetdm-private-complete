# MASK-007 - Payment-card validity and uniqueness

**Area:** Masking
**Priority:** P0
**Lane:** Reference engine and synthetic generator value assertions
**Execution status:** COMPLETE - 9/9 PASS

## Objective

Prove that ForgeTDM payment-card generation and masking emit non-real, deterministic,
Luhn-valid values without collisions for the supported card networks and masking modes.

## Acceptance Cases

| Case | Expected result | Result |
|---|---|---|
| MASK-007-01 | Visa generator emits 16-digit, `4`-prefixed, Luhn-valid, collision-free values | PASS |
| MASK-007-02 | Mastercard generator emits 16-digit, `51`-`55`-prefixed, Luhn-valid, collision-free values | PASS |
| MASK-007-03 | Amex generator emits 15-digit, `34`/`37`-prefixed, Luhn-valid, collision-free values | PASS |
| MASK-007-04 | Discover, JCB, Diners Club, and UnionPay generators are supported and collision-free | PASS |
| MASK-007-05 | Generator capacity is explicit per supported network and overflow is rejected before reuse | PASS |
| MASK-007-06 | Partition/retry replay returns the same card for the same global row and seed | PASS |
| MASK-007-07 | Changing the seed changes generated card output | PASS |
| MASK-007-08 | CREDIT_CARD masking keeps Luhn validity, format controls, and no source pass-through | PASS |
| MASK-007-09 | Preserve-BIN, random-BIN, keep-last-four, and malformed-card modes are collision-free at scale | PASS |

## Result

- Added supported synthetic card networks: Discover, JCB, Diners Club, and UnionPay.
- Extended synthetic preflight validation so every card generator checks capacity, target length,
  numeric precision, and uniqueness-sensitive partition behavior.
- Focused backend run passed 53 tests with zero failures and zero errors:
  `GeneratorsTest` 11/11 and `MaskingEngineTest` 42/42.
- Full backend regression passed 571 tests with zero failures and zero errors; six unrelated
  environment-gated live tests remained skipped.
- Frontend source was not functionally changed except one fallback generator list. `npm.cmd run
  typecheck` was attempted but failed in generated `.next/dev/types` with a pre-existing Next cache
  parse error; this is retained as an unrelated frontend gate issue, not a payment-card defect.
- No HARD-PASS was required for the reference-engine acceptance cases.

## Evidence

- Focused backend evidence: [MASK-007-FOCUSED-2026-07-24.txt](../../evidence/artifacts/MASK-007-FOCUSED-2026-07-24.txt)
- Full backend regression: [MASK-007-FULL-MAVEN-REGRESSION-2026-07-24.txt](../../evidence/artifacts/MASK-007-FULL-MAVEN-REGRESSION-2026-07-24.txt)
- Entry gate: [MASK-007-ENTRY-GATE-2026-07-24.json](../../evidence/artifacts/MASK-007-ENTRY-GATE-2026-07-24.json)

## Execution Rules

- Use synthetic test-card values only.
- Do not retain real PANs, cardholder data, or credentials in evidence.
- Treat each supported card generator as a finite keyed permutation of its own PAN domain.
- A generator must reject requested rows beyond its capacity rather than risk reuse.
