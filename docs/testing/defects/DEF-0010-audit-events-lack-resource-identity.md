# DEF-0010 — Audit events carry no resource identity or correlation context

| Field | Value |
|---|---|
| Severity | MEDIUM |
| Status | **OPEN** |
| Found by story | AUD-001 — case AUD-001-01 |
| Component | `io.forgetdm.audit.AuditService.log()` call sites across the app |

## Summary

V54 added `resource_type` / `resource_id` / `resource_name` / `metadata` columns and a structured
`AuditService.record(...)` entry point, but essentially every call site still uses the legacy 3-arg
`audit.log(actor, action, detail)`, which leaves those columns `NULL`.

Measured live across the whole trail (3,010 events):

| Field | Populated |
|---|---|
| `resourceType` | **2** |
| `resourceId` | **2** |
| `resourceName` | **2** |
| `metadata` | **0** |

Consequence: `GET /api/audit?resourceType=policy` returns **0** — the documented filter is dead, and
the resource is only recoverable by string-matching free text in `detail`.

AUD-001-01 requires each material event to include "actor, action, **resource identity**, outcome,
timestamp, and **correlation context**". Actor/action/outcome/timestamp are present and correct;
resource identity and correlation context are effectively absent.

## Impact

Cannot answer basic forensic questions ("everything that happened to policy 34") without brittle text
matching. Blocks the AUD-001 coverage matrix.

## 2026-07-24 update

A request-level safety net now records `HTTP_MATERIAL_ACTION` for any successful material API request
that reaches a controller without writing its own domain audit event. The guard covers
`POST`/`PUT`/`PATCH`/`DELETE` plus material `GET` downloads/exports, suppresses itself when a domain
audit already exists, and avoids read-noise and failed-success confusion.

Focused verification passed:

- `AccessControlFilterAuditFallbackTest`: 5/5.
- Audit/RBAC focused gate: 115/115.
- Classic and enterprise self-service approval audit focused gate: 3/3.
- Automation/self-service focused gate after integration and runner coverage: 8/8.
- DataScope nested design focused gate: 14/14.
- Provision job delete/sample audit focused gate: 8/8.
- AI and Forge Data Store audit focused gate: 7/7.
- Business Entity identity-link audit focused gate: 5/5.
- Mainframe registry audit focused gate: 9/9.
- Virtualization cancel audit focused gate: 2/2.
- Synthetic direct generation audit focused gate: 2/2.
- Reservation and RI registry focused gate: 2/2.
- Authentication lifecycle audit focused gate: 1/1.
- Unstructured masking focused gate: 7/7.
- Validation report/fix focused gate: 6/6.
- Masking script registry focused gate: 1/1.
- DataScope saved-job lifecycle focused gate: 2/2.
- Synthetic partition/saved-job and value-list focused gate: 3/3.
- AUD-001 focused rollup after DataScope design/saved jobs, provision, AI, Business Entity identity links, mainframe registry, virtualization, synthetic direct generation/partition control/saved-job launch, value lists, reservation, RI, authentication, unstructured, validation, and masking-script expansion: 203/203.

This prevents silent material changes, but it does **not** fully close DEF-0010. The fallback can
identify actor, method, API path, status, outcome, and correlation metadata; it cannot always infer
the created/updated business object's id/name. High-value domain services still need explicit
`audit.record(...)` calls for forensic queries such as "everything that happened to policy 34".

Classic and enterprise self-service no longer rely on the fallback for request/approval/fulfillment:
template/product publish, request, approve/reject, and fulfill paths now emit structured
`audit.record(...)` events with request/product ids, decision metadata, and note lengths rather than
raw decision notes.

Integration endpoint create/update/delete, test-delivery queue, and retry now emit structured
`audit.record(...)` events with endpoint/delivery identity while excluding webhook URLs, secret-env
names, signatures, and payload JSON. Enterprise self-service cancel/comment/runner export now also
emit structured events without raw cancellation reasons, comments, runner commands, or tokens.

DataScope nested design mutations no longer rely on the fallback for table profiles, column
overrides, tool-level primary keys, tool relationships, or traversal rules. These paths now emit
structured dataset/profile/override/key/relationship events while excluding profile filters, literal
override values, condition SQL, and relationship notes from audit metadata.

Provision job deletion and sample export no longer rely on the fallback. `PROVISION_JOB_DELETED`
records job identity and previous status, and `PROVISION_SAMPLE_EXPORTED` records job/table identity
plus row/column counts while excluding source and target sample cell values from metadata.

Agent action rejection and Forge Data Store material actions no longer rely on the fallback.
`AGENT_ACTION_REJECTED`, `FORGE_DATA_STORE_SYNCED`, `FORGE_DATA_STORE_SYNC_FAILED`,
`FORGE_DATA_STORE_DOCUMENT_CREATED`, `FORGE_DATA_STORE_DOCUMENT_DELETED`, and
`FORGE_DATA_STORE_DOCUMENT_EXCLUDED` now carry structured run/sync/document identity. Focused tests
prove document bodies, searchable text, warning bodies, and raw manual metadata are not persisted in
audit metadata.

Virtualization operation cancellation no longer relies on the fallback. `VIRT_OPERATION_CANCEL_REQUESTED`
records operation id/kind/label/status/stage count and explicit success only after `VirtOps.cancel`
accepts the operation.

Synchronous synthetic direct generation no longer relies on the fallback. `SYNTHETIC_DIRECT_GENERATION_STARTED`,
`SYNTHETIC_DIRECT_GENERATION_COMPLETED`, and `SYNTHETIC_DIRECT_GENERATION_FAILED` record actor,
direct-run identity, dataset, explicit outcome, plan hash, planned rows, receiver/target metadata, and
exclude literal/generated row payloads from audit metadata.

Reservation find/release and RI registry primary-key/relationship CRUD no longer rely on legacy
three-argument audit. `DATA_RESERVED`, `DATA_RELEASED`, `RI_PK_CREATED`, `RI_PK_UPDATED`,
`RI_PK_DELETED`, `RI_REL_CREATED`, `RI_REL_UPDATED`, and `RI_REL_DELETED` carry structured
reservation/key/relationship identity, ownership and scope metadata, explicit success, and exclude
row keys, criteria SQL, purpose text, and relationship descriptions from audit metadata.

Authentication login failure, login success, and logout no longer rely on legacy three-argument
audit. `LOGIN_FAILED`, `LOGIN_SUCCESS`, and `LOGOUT` now carry explicit auth-session identity and
outcome. Focused verification proves attempted/correct passwords and clear session tokens are not
recorded.

Unstructured profile and job deletion no longer rely on legacy three-argument audit.
`UNSTRUCTURED_PROFILE_DELETED` and `UNSTRUCTURED_JOB_DELETED` carry explicit resource identity,
status, and outcome while excluding masking-rule payloads and encrypted-vault storage keys.

Validation report creation and one-click masking-rule remediation no longer rely on legacy
three-argument audit. Dynamic `VALIDATION_{result}` and `VALIDATION_FIX_APPLIED` events carry
explicit report/rule identity, outcome, and safe lineage while excluding validation findings and
masking parameter values.

Masking-script save/delete no longer rely on legacy three-argument audit. `MASKING_SCRIPT_SAVED`
and `MASKING_SCRIPT_DELETED` carry explicit script identity, operation, visibility, and outcome
while excluding Lua source.

DataScope saved-job create/update/delete/run/schedule and scheduler failure no longer rely on legacy
three-argument audit. The events carry explicit saved-job/run identity, actor, trigger, approval and
schedule state, and outcome while excluding descriptions, saved specifications, subset filters, and
raw exception messages.

Synthetic partition cancel/retry and reusable-job launch no longer rely on legacy three-argument
audit. `SYNTHETIC_PARTITION_CANCELLED`, `SYNTHETIC_PARTITION_RETRIED`, and `SYNTHETIC_JOB_RUN`
carry explicit partition/job/saved-job identity, state transitions, safe row/attempt counts, actor,
and outcome while excluding raw partition errors, saved descriptions, plan JSON, and generator
parameters.

Value-list save/import/delete no longer rely on legacy three-argument audit. `VALUE_LIST_SAVED`,
`VALUE_LIST_IMPORTED`, and `VALUE_LIST_DELETED` carry explicit list/source identity, operation,
visibility, and safe counts while excluding reference-list values and descriptions.

## Recommended fix

Migrate material call sites to `record(...)` with resourceType/resourceId/resourceName — prioritise
the security-relevant ones (policy, data source, DataScope blueprint, reservation, security user/group,
provision/synthetic jobs, ACCESS_DENIED). Add a correlation id (request id) to `metadata`.
