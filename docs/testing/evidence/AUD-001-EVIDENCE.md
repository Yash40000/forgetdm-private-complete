# AUD-001 — Material Action Audit Coverage — Evidence

**Story:** AUD-001 (P0, All lanes)
**Spec:** `docs/testing/cases/ready/AUD-001.md`

**Current disposition override (2026-07-24):** **PARTIAL / BLOCKED**. Current evidence position is
**7 PASS / 3 PARTIAL**. Retained lifecycle evidence closes AUD-001-02. The request-level fallback
audit guard closes silent material-action gaps but does not replace rich business-object audit
identity, so AUD-001-01 remains partial. Synchronous synthetic direct generation now emits structured
start/completed/failed audit events with sanitized plan metadata. DataScope nested design mutations now have structured
domain audit coverage for table profiles, column overrides, tool PKs, tool relationships, and
traversal rules. Provision job deletion and sample export now have structured resource audit,
including protected sample-download evidence without row-value leakage. Agent action rejection and
Forge Data Store sync/manual-document/delete operations now have structured AI audit events without
document-body leakage. Virtualization operation cancellation now has structured audit coverage.
Focused governance tests for Business Entity, classic self-service, enterprise
self-service, and integrations pass for AUD-001-03, but live maker/checker evidence is still pending.
**Execution status:** **FAIL → substantially remediated.** First pass 2026-07-18: 3 PASS / 5 FAIL / 2 NOT EXECUTED.
After fixes and live re-verification: **6 PASS / 2 PARTIAL / 2 NOT EXECUTED**, with 5 defects closed.
Story remains `status:blocked` pending DEF-0010 completion, cases 02/03, the coverage matrix,
second-reviewer sign-off, and an operator-approved historical-chain re-anchor. DEF-0015 has been
re-verified and closed.

> **This story was previously marked "✅ PASSED (2026-07-17)".** That claim is withdrawn — see
> [DEF-0014](../defects/DEF-0014-aud-001-false-pass-claim.md). The cited evidence
> (`test-results/AUD-001-execution-report.md`) is a generic `mvn test` summary of 228 unrelated unit
> tests (`CopybookCodecTest`, `GeneratorsTest`, …) and exercises none of AUD-001's ten cases.

## Run metadata

| Field | Value |
|---|---|
| Environment | Live local stack — FE `http://localhost:3000`, BE `http://localhost:8088` (post-V61 build) |
| Executed | 2026-07-18 |
| Method | Real HTTP against the running backend; source corroboration for root causes |
| Trail size at execution | ~3,010 events, 95 distinct actions, 13 actors, 10 categories |

## Result summary

| # | Case | Result | Evidence |
|---|---|---|---|
| 01 | CRUD coverage | **FAIL (partial)** | Create/update/delete *are* recorded (`POLICY_CREATED`, `DATASOURCE_UPDATED`, `DATASOURCE_DELETED`, `SECURITY_USER_*`) with actor/action/outcome/timestamp. But **resource identity is absent**: of 3,010 events only **2** have `resourceType`/`resourceId`/`resourceName`, and **0** have metadata/correlation context. `?resourceType=policy` returns **0**. → [DEF-0010](../defects/DEF-0010-audit-events-lack-resource-identity.md) |
| 02 | Execution lifecycle | **NOT EXECUTED** | Requires driving synthetic/provision/mapping/discovery/mainframe jobs to completion, failure, retry and cancel. Deferred — heavy writes previously saturated the backend. |
| 03 | Governance | **NOT EXECUTED** | Requires a full maker/checker approve/reject/promote cycle. Deferred. |
| 04 | Export events | **FAIL** | `GET /api/audit/export.csv` exported the whole trail and produced **no audit event** (total 3012 → 3012; no `*EXPORT*` action exists among the 95). Bulk evidence extraction is untracked. → [DEF-0012](../defects/DEF-0012-audit-export-not-audited.md) |
| 05 | Denial | **FAIL** | Access denial is audited correctly (`ACCESS_DENIED`, outcome FAILURE, severity CRITICAL — verified in RBAC-001/002). **Invalid login is never recorded**: a bad password returned 401 but `LOGIN_FAILED` count stayed 0, and no failed-auth action exists among the 95 distinct actions. → [DEF-0008](../defects/DEF-0008-failed-login-audit-rolled-back.md) |
| 06 | Search / facets / stats | **PASS** | `actor`, `action`, `outcome`, `from` filters each returned only matching rows; ordering strictly descending by seq with **no overlap** across pages (p0 3046→2995, p1 starts 2994); facets 95 actions / 10 categories / 13 actors; `stats.total` agrees with search total. |
| 07 | Export limits | **FAIL** | `export.csv` hard-caps at `PageRequest.of(0, 5000)` and emits **no truncation indicator** (no header, no marker, no total). Below the cap today (3,011 rows exported), but past 5,000 a partial export is indistinguishable from a complete one — precisely what this case forbids. → [DEF-0011](../defects/DEF-0011-audit-csv-silent-truncation.md) |
| 08 | Integrity | **FAIL** | `/api/audit/verify` → `valid:false`, `brokenAtSeq:702`, `hashedCount:11` of 3,005. The chain has **forked**: seq **702 and 703 each exist twice**, both branches sharing prevHash `Tp4bBLlfy28C…`. Verification `break`s at the first mismatch, so ~3,000 events — including every current security event — are **never verified**. → [DEF-0009](../defects/DEF-0009-audit-chain-forked-and-verify-aborts.md) |
| 09 | Leakage | **PASS (with hardening risk)** | Scanned all 3,010 events (1.48 MB): **no** passwords, API tokens, bearer headers, session cookies, SSN patterns or emails. However 14 events embed **raw JDBC URLs** (`jdbc:postgresql://localhost:5433/sourcedb`, `jdbc:oracle:thin:@…`), one with a query string. No credentials present today (`user=` 0, `//user:pass@` 0), but the URL is logged verbatim so a credential-bearing URL would be persisted in clear. → [DEF-0013](../defects/DEF-0013-audit-records-raw-jdbc-urls.md) |
| 10 | Authorization | **PASS** | `rbac_engineer` (no `audit.read`) → **403** on both `/api/audit` and `/api/audit/export.csv`. `rbac_auditor` (has `audit.read`) → **200** on both. |

## Current case disposition override (2026-07-24)

| # | Case | Current result | Evidence |
|---|---|---|---|
| 01 | CRUD coverage | **PARTIAL** | `HTTP_MATERIAL_ACTION` fallback now records successful material API requests when no domain audit event was written. Synchronous synthetic direct generation now emits structured direct-run events; DataScope nested design mutations emit structured domain events for table profiles, column overrides, tool PKs, tool relationships, and traversal rules; provisioning delete/sample export and AI Data Store changes now emit structured resource events. Strict object-level forensic identity still requires migration of the remaining legacy/partial actions. |
| 02 | Execution lifecycle | **PASS** | [AUD-001-LIFECYCLE-2026-07-18.md](AUD-001-LIFECYCLE-2026-07-18.md) proves mapping, provision, synthetic, unstructured, and mainframe complete/fail/retry/cancel events. |
| 03 | Governance | **PARTIAL** | `BusinessEntityEnterpriseServiceTest`, `SelfServiceServiceTest`, `EnterpriseSelfServiceServiceTest`, and `IntegrationWebhookServiceTest` prove structured maker/checker governance, self-service approval, package promotion, integration endpoint/runner evidence, and secret-free decision metadata; live maker/checker script remains pending. |
| 04 | Export events | **PASS** | `AUDIT_EXPORTED` live evidence retained below. |
| 05 | Denial | **PASS** | Failed login, access denial, validation failure, and dependency failure audit paths are covered by live/focused gates. |
| 06 | Search / facets / stats | **PASS** | Existing live evidence retained below. |
| 07 | Export limits | **PASS** | Existing live evidence retained below. |
| 08 | Integrity | **PARTIAL** | Active/post-fix chains verify and disposable tamper tests pass; historical live ledger still needs operator-approved re-anchor. |
| 09 | Leakage | **PASS** | Existing live leak scan plus structured governance metadata tests. |
| 10 | Authorization | **PASS** | Existing RBAC/audit-read evidence retained below. |

## Re-verification after fallback audit guard (2026-07-24)

| Gate | Result | Evidence |
|---|---|---|
| Successful material request fallback | **PASS** | `AccessControlFilterAuditFallbackTest` proves a successful unaudited `POST /api/datasources` records `HTTP_MATERIAL_ACTION` with actor, path, outcome, status, method, and correlation metadata. |
| Duplicate suppression | **PASS** | The fallback is suppressed when `AuditService` has already marked the request as audited, so explicit domain events remain authoritative. |
| Read-noise suppression | **PASS** | Ordinary `GET /api/datasources` does not create fallback audit noise; material downloads/exports do. |
| Failure separation | **PASS** | A 409 material request does not receive a success fallback; failures stay with denial/validation/dependency audit paths. |
| Focused regression | **PASS** | `mvn "-Dtest=AccessControlFilterAuditFallbackTest,AuditContextTest,AuditControllerTenancyTest,AuditHashChainTest,Rbac001RoutePermissionMatrixTest,Rbac001ControllerInventoryTest,GlobalExceptionAuditTest" test` -> 115 tests, 0 failures, 0 errors. |
| Governance focused gate | **PASS** | `mvn "-Dtest=BusinessEntityEnterpriseServiceTest" test` -> 11 tests, 0 failures, 0 errors. |
| Self-service structured approval audit | **PASS** | Classic and enterprise self-service now use `audit.record(...)` for template/product publish, request, approve/reject, and fulfillment without persisting decision notes in audit metadata. `mvn "-Dtest=SelfServiceServiceTest,EnterpriseSelfServiceServiceTest" test` -> 3 tests, 0 failures, 0 errors. |
| Integration structured audit | **PASS** | `IntegrationWebhookServiceTest` proves endpoint create/update/delete, test-delivery queue, and retry emit structured audit events with endpoint/delivery identity and without webhook URL, secret-env name, or payload leakage. |
| Enterprise self-service operational audit | **PASS** | `EnterpriseSelfServiceServiceTest` proves cancel, comment, and runner export emit structured ledger events without raw cancellation reason, comment text, runner command, or token leakage. |
| Automation/self-service focused gate | **PASS** | `mvn "-Dtest=IntegrationWebhookServiceTest,SelfServiceServiceTest,EnterpriseSelfServiceServiceTest" test` -> 8 tests, 0 failures, 0 errors. |
| DataScope nested design audit | **PASS** | `DataSetAuditCoverageTest` proves table profile, column override, tool PK, tool relationship, and traversal rule mutations emit structured audit events without leaking profile filters, literal override values, relationship notes, or condition SQL. |
| DataScope focused gate | **PASS** | `mvn "-Dtest=DataSetAuditCoverageTest,DataSetChildIsolationTest,DataSetReferenceIsolationTest,DataSetDirectiveTest" test` -> 14 tests, 0 failures, 0 errors. |
| Provision sample/export audit | **PASS** | `ProvisioningTenancyTest` proves `PROVISION_JOB_DELETED` and `PROVISION_SAMPLE_EXPORTED` carry actor/job/resource/outcome metadata and that sample audit metadata excludes returned row values such as SSNs. |
| Provision focused gate | **PASS** | `mvn "-Dtest=ProvisioningTenancyTest" test` -> 8 tests, 0 failures, 0 errors. |
| AI and Forge Data Store audit | **PASS** | `AgentServiceTest` and `ForgeIntelligenceStoreServiceTest` prove agent action rejection plus Data Store sync/manual-document/delete/exclude emit structured audit events without raw document content, searchable text, warning bodies, or sample metadata leakage. |
| AI focused gate | **PASS** | `mvn "-Dtest=ForgeIntelligenceStoreServiceTest,AgentServiceTest" test` -> 7 tests, 0 failures, 0 errors. |
| Business-entity identity link audit | **PASS** | `BusinessEntityIdentityServiceTest` proves standalone identity-link creation records `BUSINESS_ENTITY_IDENTITY_LINK_UPSERT` with link/entity/subject identity and without raw external-id leakage. |
| Business-entity identity focused gate | **PASS** | `mvn "-Dtest=BusinessEntityIdentityServiceTest" test` -> 5 tests, 0 failures, 0 errors. |
| Mainframe registry audit | **PASS** | `MainframeControllerTenancyTest` proves connection create/update/delete, copybook create/update/delete, and copybook-mask replace emit structured audit events without passwords, host/path/user values, copybook source text, field paths, or mask params in metadata. |
| Mainframe registry focused gate | **PASS** | `mvn "-Dtest=MainframeControllerTenancyTest" test` -> 9 tests, 0 failures, 0 errors. |
| Virtualization cancel audit | **PASS** | `VirtualizationAuditCoverageTest` proves successful operation cancellation records structured operation identity/outcome and that an unknown/already-finished operation does not record a false success. |
| Virtualization focused gate | **PASS** | `mvn "-Dtest=VirtualizationAuditCoverageTest" test` -> 2 tests, 0 failures, 0 errors. |
| Synthetic direct generation audit | **PASS** | `SyntheticDirectGenerationAuditTest` proves synchronous generation records `SYNTHETIC_DIRECT_GENERATION_STARTED`, terminal success/failure, direct-run identity, plan hash, row/target metadata, and excludes literal/generated row payloads. |
| Reservation and RI registry audit | **PASS** | `ReservationAuditCoverageTest` and `RiRegistryAuditCoverageTest` prove reservation find/release and RI PK/relationship create/update/delete emit structured events with safe resource metadata and no row-key, criteria SQL, purpose text, or relationship description leakage. |
| Reservation/RI focused gate | **PASS** | `mvn "-Dtest=ReservationAuditCoverageTest,RiRegistryAuditCoverageTest" test` -> 2 tests, 0 failures, 0 errors. |
| Authentication lifecycle audit | **PASS** | `AuthenticationAuditCoverageTest` proves structured login failure, login success, and logout events carry explicit auth-session identity/outcome while excluding attempted/correct passwords and session tokens. |
| Authentication focused gate | **PASS** | `mvn "-Dtest=AuthenticationAuditCoverageTest" test` -> 1 test, 0 failures, 0 errors. |
| Unstructured masking deletion audit | **PASS** | `UnstructuredMaskingTenancyTest` proves profile/job deletion carries explicit identity/outcome while excluding rule payloads and encrypted-vault storage keys. |
| Unstructured focused gate | **PASS** | `mvn "-Dtest=UnstructuredMaskingTenancyTest" test` -> 7 tests, 0 failures, 0 errors. |
| Validation report/fix audit | **PASS** | `ValidationTenancyTest` proves report creation and masking-rule remediation carry explicit report/rule identity and lineage without findings or rule-parameter leakage; the permission gate remains enforced. |
| Validation focused gate | **PASS** | `mvn "-Dtest=ValidationTenancyTest,ValidationApplyFixPermissionTest" test` -> 6 tests, 0 failures, 0 errors. |
| Masking script registry audit | **PASS** | `MaskingScriptAuditCoverageTest` proves save/delete events carry explicit script identity, operation, visibility, source length, and outcome without Lua-source leakage. |
| Masking script focused gate | **PASS** | `mvn "-Dtest=MaskingScriptAuditCoverageTest" test` -> 1 test, 0 failures, 0 errors. |
| DataScope saved-job audit | **PASS** | `DataScopeJobAuditCoverageTest` proves create/update/delete/run/schedule emit explicit saved-job identity, actor, run/approval state, and outcome without description, saved-spec, or subset-filter leakage. |
| DataScope saved-job focused gate | **PASS** | `mvn "-Dtest=DataScopeJobAuditCoverageTest,DataScopeJobScheduleIsolationTest" test` -> 2 tests, 0 failures, 0 errors. |
| Synthetic partition/saved-job audit | **PASS** | `SyntheticPartitionAndSavedJobAuditTest` proves partition cancel/retry and reusable-job launch carry explicit job/partition/table/saved-job identity, state transitions, actor, and outcome without raw partition errors, descriptions, or generator plan literals. |
| Value-list audit | **PASS** | `ValueListAuditCoverageTest` proves manual save, live-column import, and delete carry explicit list/source identity and safe counts without reference values or descriptions. |
| Synthetic/value-list focused gate | **PASS** | `mvn "-Dtest=ValueListAuditCoverageTest,SyntheticPartitionAndSavedJobAuditTest" test` -> 3 tests, 0 failures, 0 errors. |
| AUD-001 focused rollup | **PASS** | Expanded focused set including authentication, unstructured, validation, masking scripts, DataScope saved jobs, synthetic partitions/saved-job launch, and value lists -> 203 tests, 0 failures, 0 errors. |

## Key root causes (source-corroborated)

**DEF-0008 — failed-login audit is rolled back.** The code *does* call
`audit.log(username, "LOGIN_FAILED", …)` (`AccessControlService:151`), but `login()` is
`@Transactional` (`:138`) and the next statement throws `ApiException`. The RuntimeException rolls
back the very transaction the audit row was written in, so the event never persists. This is
invisible to code review and only surfaces live. It also advances the in-memory `lastSeq`/`lastHash`
while discarding the row — which explains the sequence gaps observed (`maxSeq` 3046 vs 3,010 total)
and leaves the next event chaining onto a hash that does not exist in the database.

**DEF-0009 — the chain can fork.** `AuditService.chainAndSave()` allocates the sequence in memory
(`++lastSeq`) guarded only by `synchronized`, which is per-JVM, and there is no unique constraint on
`seq`. Two instances (or a double start) allocate the same number, producing two rows with identical
`seq` and identical `prevHash` — exactly what is present at seq 702/703. `verifyChain()` then
`break`s at the first mismatch and reports a permanent `valid:false`, so an operator cannot
distinguish a structural fork from real tampering, and tampering *after* the break is undetectable.

## Re-verification after fixes (2026-07-18, post-V62 rebuild)

| Case | Was | Now | Evidence |
|---|---|---|---|
| 04 Export events | FAIL | **PASS** | `AUDIT_EXPORTED` recorded before delivery: `resourceType=audit`, `resourceName=audit-trail.csv`, `detail="Exported 3016 of 3016 matching events (csv)"`, metadata `{format,exported,matched,limit,truncated}`. Count 0 → 1. |
| 05 Denial | FAIL | **PASS** | Bad password → `LOGIN_FAILED` persisted (0 → 1), actor `alpha_user`, outcome `FAILURE`, severity `CRITICAL`. HTTP still 401. |
| 07 Export limits | FAIL | **PASS** | `X-Total-Count: 3016`, `X-Exported-Count: 3016`, `X-Export-Limit: 5000`, `X-Truncated: false`; `# TRUNCATED` row appended when capped. |
| 08 Integrity | FAIL | **PARTIAL** | Verified events **11 → 2,345**; walk reaches seq **3,052** (was 702); 10 breaks reported with seq + reason; 10 valid segments. Chain no longer aborts. Still `valid:false` — 7 genuine historical `LINK_BREAK`s plus 3 false `CONTENT_MISMATCH` now tracked as [DEF-0015](../defects/DEF-0015-audit-hash-timestamp-rounding.md). |
| 09 Leakage | PASS (risk) | **PASS (hardened)** | Credential-bearing URL recorded as `jdbc:postgresql://dbhost:5432/salesdb?user=***&password=***&ssl=true` (68 chars vs 104 raw) — userinfo and both credential params removed, host/db/benign param kept. |
| 01 CRUD | FAIL | **PARTIAL** | Resource identity now populated on policy/ACCESS_DENIED/export events (`resourceType=policy`, `resourceId=37`, `resourceName=…`) and the `resourceType=policy` filter returns results (was 0). The current matrix still has 78 partial mutation rows and 123 legacy 3-arg `log()` call sites → DEF-0010 stays open. |

**DEF-0015 re-verified and closed (2026-07-18).** After the millisecond-truncation fix: total events
3,013 → 3,058 (+45) and `verifiedCount` 2,345 → 2,390 (+45) — every post-fix event verified, with
`contentBreaks` and `linkBreaks` both unchanged. The break list is identical, so all 10 remaining
breaks predate the fix. `valid:false` now reflects genuine historical damage rather than a false
alarm; clearing it needs the operator-acknowledged re-anchor from DEF-0009, deliberately not done
here because rewriting hashes to force a green check would defeat the control.

**Follow-on defect found and closed.** Making verification walk the whole trail exposed 3
`CONTENT_MISMATCH` rows and a `tamperSuspected:true` flag on an untampered database. Root cause is a
precision bug, not tampering: the hash covers `createdAt.toEpochMilli()` while Postgres stores
microseconds and the nano→micro conversion rounds, so ~0.13% of rows re-read one millisecond later
than they were hashed. Fixed by truncating to milliseconds before hashing/persisting
([DEF-0015](../defects/DEF-0015-audit-hash-timestamp-rounding.md), verified live and closed). A false tamper
alarm is the worst outcome for this control, so this is treated as HIGH.

**Defects closed:** DEF-0008, DEF-0009, DEF-0011, DEF-0012, DEF-0013, DEF-0015.
**Still open:** DEF-0010 (partial), DEF-0014 (process).

## Verdict

AUD-001 does **not** pass. Its exit criteria require "complete coverage, valid hash chain, and zero
secret/clear-PII leakage". Export, failed-auth auditing, retained lifecycle coverage, DataScope
nested design audit, protected provision sample export audit, AI Data Store audit, virtualization cancel audit, reservation/RI registry audit, and focused governance/self-service/integration gates now pass, and resource
identity is present on the hardened paths. Coverage remains incomplete across other legacy actions,
and the historical chain remains invalid by design until an operator-approved re-anchor. Leakage is
clean.

## Exit checklist

- [x] DEF-0008, DEF-0009 (HIGH) fixed and re-verified.
- [ ] DEF-0010 completed; DEF-0011 and DEF-0012 are closed.
- [x] DEF-0013 (LOW) hardening.
- [x] DEF-0015 false tamper alarm fixed and verified live.
- [x] AUD-001-02 retained lifecycle evidence executed (job lifecycle).
- [ ] AUD-001-03 live governance evidence executed; focused governance gates pass.
- [x] Coverage matrix built mapping every material controller action to its expected event.
- [ ] Second reviewer sign-off; story stays `status:blocked`.

## V62 first-boot verification (2026-07-18)

The controlled first boot was observed against the local PostgreSQL metadata database before any
later migration was allowed to establish success. Full sanitized evidence is recorded in
[`test-results/AUD-001-V62-regression-2026-07-18.md`](../../../test-results/AUD-001-V62-regression-2026-07-18.md).

- Flyway recorded version 62 (`audit sequence integrity`) with `success=true`.
- The audit row count remained 3,012; no row was deleted.
- Original rows kept seq 702/703; duplicate row IDs 704/705 moved to 3050/3051.
- Duplicate sequence groups and null sequence rows both became zero.
- `uq_audit_events_seq` and `audit_event_seq` were present; the next allocation was 3052.
- After normal activity reached 3,081 rows / seq 3120, duplicate sequence groups remained zero.
- `AuditHashChainTest` plus `FlywayMigrationVersionTest`: 7 tests passed, 0 failed.
