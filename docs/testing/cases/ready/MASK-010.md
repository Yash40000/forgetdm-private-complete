# MASK-010 - Script masking sandbox and isolation

**Area:** Masking
**Priority:** P1
**Lane:** Script masking safety
**Execution status:** COMPLETE - 10/10 PASS

## Objective

Prove that ForgeTDM Lua script masking is deterministic and useful while staying sandboxed.
Scripts must not access local files, OS commands, Java runtime bridges, debug hooks, package
loaders, or shared mutable state between rows.

## Acceptance Cases

| Case | Expected result | Result |
|---|---|---|
| MASK-010-01 | Missing or unknown script names fail closed instead of silently passing data through | PASS |
| MASK-010-02 | Invalid Lua syntax is rejected during script validation | PASS |
| MASK-010-03 | `os`, `io`, `loadfile`, `require`, and `package` are unavailable in the sandbox | PASS |
| MASK-010-04 | `java`, `luajava`, `debug`, and `coroutine` are unavailable in the sandbox | PASS |
| MASK-010-05 | Script helpers can call deterministic ForgeTDM masking functions | PASS |
| MASK-010-06 | Scripts cannot recursively invoke the `SCRIPT` masking function | PASS |
| MASK-010-07 | Script globals do not leak from one row/execution into the next | PASS |
| MASK-010-08 | Mutating the exposed `forge` helper object does not affect later executions | PASS |
| MASK-010-09 | A script returning `nil` maps to SQL null without crashing | PASS |
| MASK-010-10 | Bundled sample scripts remain executable under the sandbox | PASS |

## Result

- Changed Lua execution to create a fresh sandbox per script run, eliminating mutable global
  leakage across rows.
- Expanded sandbox tests for OS/file APIs, package loading, Java bridge access, debug/coroutine
  access, helper mutation, and global-state leakage.
- Focused backend run passed 28 tests with zero failures and zero errors:
  `ScriptMaskTest`, `ScriptSamplesTest`, `MaskingFunctionCatalogAcceptanceTest`, and
  `PolicyRuleValidationTest`.
- Full backend regression passed 579 tests with zero failures and zero errors; six unrelated
  environment-gated live tests remained skipped.
- No HARD-PASS was required for the reference-engine acceptance cases.

## Evidence

- Focused backend evidence: [MASK-010-FOCUSED-2026-07-24.txt](../../evidence/artifacts/MASK-010-FOCUSED-2026-07-24.txt)
- Full backend regression: [MASK-010-FULL-MAVEN-REGRESSION-2026-07-24.txt](../../evidence/artifacts/MASK-010-FULL-MAVEN-REGRESSION-2026-07-24.txt)

## Execution Rules

- Use synthetic script inputs only.
- Do not run customer-supplied scripts without sandbox evidence and review.
- Do not retain production data, credentials, local filesystem contents, or environment values
  in evidence.
