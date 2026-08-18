# MASK-008 - Direct lookup and hash lookup behavior

**Area:** Masking
**Priority:** P0
**Lane:** Reference engine and local lookup catalog
**Execution status:** COMPLETE - 9/9 PASS

## Objective

Prove that ForgeTDM lookup masking behaves like an enterprise TDM lookup facility:
exact mappings are deterministic and fail closed, hash lookups pick coherent replacement rows,
governed lookup tables can be referenced by policy rules, and ambiguous lookup data is rejected
before it can silently corrupt masked output.

## Acceptance Cases

| Case | Expected result | Result |
|---|---|---|
| MASK-008-01 | DIRECT_LOOKUP maps exact source keys to configured replacement values | PASS |
| MASK-008-02 | DIRECT_LOOKUP supports trim/case normalization without source pass-through | PASS |
| MASK-008-03 | DIRECT_LOOKUP supports row-context composite keys with `SOURCE=` and `JOIN=` | PASS |
| MASK-008-04 | DIRECT_LOOKUP missing keys fail closed by default and support explicit fallback modes | PASS |
| MASK-008-05 | DIRECT_LOOKUP rejects duplicate keys after trim/case normalization | PASS |
| MASK-008-06 | HASH_LOOKUP is deterministic across destination columns and salts for the same source, lookup, and seed | PASS |
| MASK-008-07 | HASH_LOOKUP supports Optim-style reserved rows for null, spaces, and zero-length input | PASS |
| MASK-008-08 | HASH_LOOKUP rejects duplicate, non-contiguous, or mixed keyed/unkeyed lookup rows | PASS |
| MASK-008-09 | Governed relational `@lookup:direct:` and `@lookup:hash:` references load from the local lookup catalog and reject broken rows | PASS |

## Result

- Added focused fail-closed tests for duplicate direct lookup keys, invalid fallback actions,
  default/null/preserve fallback behavior, duplicate hash keys, duplicate reserved keys, mixed
  keyed/unkeyed hash rows, and missing reserved hash rows.
- Hardened relational lookup catalog validation so a row with a missing `replacement_value`
  returns a controlled API validation error instead of an unexpected server failure.
- Focused backend run passed 53 tests with zero failures and zero errors:
  `MaskingEngineTest`, `MaskingLookupCatalogTest`, `PolicyRuleValidationTest`, and
  `MaskingFunctionCatalogAcceptanceTest`.
- Full backend regression passed 574 tests with zero failures and zero errors; six unrelated
  environment-gated live tests remained skipped.
- No HARD-PASS was required for the reference-engine and local catalog acceptance cases.

## Evidence

- Focused backend evidence: [MASK-008-FOCUSED-2026-07-24.txt](../../evidence/artifacts/MASK-008-FOCUSED-2026-07-24.txt)
- Full backend regression: [MASK-008-FULL-MAVEN-REGRESSION-2026-07-24.txt](../../evidence/artifacts/MASK-008-FULL-MAVEN-REGRESSION-2026-07-24.txt)

## Execution Rules

- Use synthetic lookup values only.
- Use local in-memory relational lookup fixtures only.
- Do not retain customer data, production lookup data, credentials, or policy secrets in evidence.
- Treat any ambiguous lookup definition as a blocking defect, not a warning.
