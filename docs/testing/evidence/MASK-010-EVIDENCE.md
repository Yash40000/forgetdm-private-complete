# MASK-010 Evidence - Script masking sandbox and isolation

Date: 2026-07-24
Operator: Codex test coordinator
Story: MASK-010

## Scope

Validated Lua script masking safety and determinism using reference-engine tests and synthetic
inputs only. No customer script, production data, filesystem sample, OS command, or network call
was used.

## Code Changes Verified

- `MaskingEngine.script(...)` now creates a fresh Lua sandbox for each execution, preventing
  globals or helper mutations from leaking across rows.
- `ScriptMaskTest.sandboxHasNoOsIoOrFiles` proves file, OS, package, and load APIs are absent.
- `ScriptMaskTest.sandboxHasNoJavaDebugOrUnsafeRuntimeBridge` proves Java bridge, debug, and
  coroutine APIs are absent.
- `ScriptMaskTest.scriptGlobalsDoNotLeakBetweenExecutions` proves script globals are isolated.
- `ScriptMaskTest.helperMutationDoesNotLeakBetweenExecutions` proves changes to exposed helper
  tables are not shared with later executions.

## Executed Evidence

Focused command:

```powershell
mvn "-Dtest=ScriptMaskTest,ScriptSamplesTest,MaskingFunctionCatalogAcceptanceTest,PolicyRuleValidationTest" test
```

Focused result:

- Tests run: 28
- Failures: 0
- Errors: 0
- Skipped: 0
- Build result: SUCCESS

Focused artifact:

- `docs/testing/evidence/artifacts/MASK-010-FOCUSED-2026-07-24.txt`

Regression command:

```powershell
mvn test
```

Regression result:

- Tests run: 579
- Failures: 0
- Errors: 0
- Skipped: 6
- Build result: SUCCESS

Regression artifact:

- `docs/testing/evidence/artifacts/MASK-010-FULL-MAVEN-REGRESSION-2026-07-24.txt`

## Acceptance Mapping

| Acceptance | Evidence |
|---|---|
| Missing or unknown scripts fail closed | `ScriptMaskTest.missingScriptFailsClosed`, policy validation tests |
| Invalid syntax rejected | `ScriptMaskTest.syntaxValidationRejectsBadLua` |
| OS/file/package APIs unavailable | `ScriptMaskTest.sandboxHasNoOsIoOrFiles` |
| Java/debug/coroutine unavailable | `ScriptMaskTest.sandboxHasNoJavaDebugOrUnsafeRuntimeBridge` |
| Deterministic Forge helper calls | `ScriptMaskTest.scriptCanCallForgeMaskHelpers` |
| Recursive script masking blocked | `ScriptMaskTest.scriptCannotInvokeScriptMaskRecursively` |
| Script globals isolated | `ScriptMaskTest.scriptGlobalsDoNotLeakBetweenExecutions` |
| Helper mutations isolated | `ScriptMaskTest.helperMutationDoesNotLeakBetweenExecutions` |
| Nil output maps to SQL null | `ScriptMaskTest.nilReturnBecomesNull` |
| Sample scripts remain green | `ScriptSamplesTest` |

## Notes

The full regression output includes expected suppressed `[api]` log lines from negative
exception-handling tests. They are not failed tests.
