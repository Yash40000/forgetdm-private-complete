# Compliance Assurance

Answers the two questions an auditor actually asks, with evidence rather than assurance:

1. **"Prove no real customer PII has leaked into any non-production environment."**
2. **"This customer exercised their right to be forgotten — does their data exist in your test environments?"**

Existing ForgeTDM validation samples a few hundred rows per column and compares masked against source.
That is a useful smoke test, but it can only speak for the rows it sampled. This module is built to
produce *evidence*: full-column proof, coverage assurance, a governed exception register, and a dated
document you can hand to an auditor.

## The three-control model

| Control | What it does | Where |
|---|---|---|
| **Prevent** | Mask on extract; classify before provisioning; least-privilege on masking rules; every classified PII field must have a rule | Discovery + Policy + `COVERAGE` scan |
| **Detect** | Full-column pattern scan, hashed source-value comparison, cardinality/uniqueness gates | `LEAK` + `CARDINALITY` scans |
| **Prove** | Coverage report, tamper-evident masking run log, scan verdicts, exception register, chain integrity | Evidence pack |

## Checks

### 1. Coverage — `COVERAGE`

Compares every `classifications` row against the masking rules in a policy. A column that discovery
flagged as PII but that no rule masks is the single commonest real-world leak, and it is invisible to
any check that only inspects columns it already knows about.

* **CONFIRMED** classification with no rule → `FAIL` (someone reviewed it and agreed it holds PII)
* **SUGGESTED** classification with no rule → `WARN` (triage may still reject it, but "we never
  reviewed it" is not a defence)

### 2. Leak — `LEAK`

Proof of *absence*, over whole columns, by two independent methods:

**Pattern scan** — does any value in the target still validate as a real-world identifier?
Deliberately stricter than the discovery regex, so masked placeholders do not produce noise:

| Type | Accepted as "real" only when |
|---|---|
| SSN / NATIONAL_ID | Area not `000`/`666`/`900+`, group not `00`, serial not `0000`, not a repeated digit |
| CREDIT_CARD | 13–19 digits **and** Luhn-valid **and** not a repeated digit |
| IBAN / BANK_ACCOUNT | Country + length shape **and** mod-97 check digits correct |
| EMAIL | Valid shape **and** *not* on a reserved domain (`.test`, `.invalid`, `example.com`, …) |
| PHONE / FAX | Assignable NANP (area and exchange start `2-9`) and not the `555` fictional range |
| ROUTING | 9 digits and the ABA weighted checksum holds |

Because it needs no production access, this runs in environments where prod is unreachable —
and it speaks for the *whole column*, not a sample.

**Hashed source match** — when a source is supplied, every source value is reduced to
`SHA-256(context | masking-secret | normalize(value))` and held in a set; every target value is hashed
and checked against it. This catches a surviving production value even for types with no checksum
(a name, an address). Normalisation (trim, upper-case, strip `-()./`) means `123-45-6789` and
`123456789` are recognised as the same identity.

### 3. Cardinality / uniqueness — `CARDINALITY`

One `COUNT(*) / COUNT(DISTINCT col)` per column, so it is cheap on large tables.

* `distinct <= 1` → `FAIL`. Every row identical: a broken tenant filter or per-record defect
  cannot surface in *any* test, because all joins "work". This is the gate that catches the
  classic cross-customer-data-exposure escape.
* `distinct/rows < 10%` → `FAIL` (masking severely aliased the column)
* `distinct/rows < 50%` → `WARN` (lower than expected for an identifier)
* Single-column PK or unique index with duplicates → `FAIL` **UNIQUENESS**: colliding keys merge
  distinct records. Remediation is FPE — bijective, so collisions are impossible.

Columns with fewer than 50 rows are skipped as too small to judge.

## Subject erasure (right to be forgotten)

`POST /api/compliance/subject-search` reports two different things, and the distinction is the whole
answer:

1. **Is the raw identifier present?** Probes identifier columns across every registered
   non-production environment. A hit means masking did not cover that environment or column.
2. **Does a reversible crosswalk exist?** ForgeTDM has exactly two structures that can be reversible,
   and both are checked:
   * `masking_lookup_values` rows in **DIRECT** mode store `source_value -> replacement_value` and
     therefore *are* a crosswalk. (**HASH** mode retains no source value and is one-way.)
   * `be_identity_links` retains real business keys (`external_id`, `key_values_json`) to stitch an
     entity together across systems.

**The verdict:**

* Nothing found, no crosswalk → masking is irreversible, so the subject's identity does not exist in
  non-production *by construction*. There is nothing to erase, and the scan is the evidence.
* Anything found → the finding states the exact erasure scope. A crosswalk entry **is itself personal
  data** and is in scope.

The scanner is strictly read-only: it locates and reports, never deletes. Erasure stays a deliberate,
governed action. The subject's value is used to query but never stored — only its salted hash, so the
same request can be re-evidenced after remediation.

## Exception register

Approved, time-boxed permission for unmasked production data in a non-production environment.
Exceptions are a fact of life; undocumented ones are the finding.

* **Justification** ≥ 20 characters, plus compensating controls
* **Four-eyes**: `PiiExceptionService.approve` refuses when approver == requester, and the route needs
  `compliance.approve` rather than `compliance.run`
* **Expiry** (max 180 days). `expireOverdue()` runs before every scan and evidence pack, so a lapsed
  exception becomes a `FAIL` finding instead of quietly becoming permanent
* An **active** exception downgrades a matching `FAIL` to `WARN` and names the authorising exception —
  distinguishing "unmanaged leak" from "known, justified, time-boxed risk" without ever hiding it

## Evidence pack

`POST /api/compliance/evidence-pack` (or `GET …/download` for a Markdown file) compiles six sections
in the order an auditor reads them:

1. Summary table with a stated assessment
2. PII classification inventory by type, with every uncovered field named
3. Masking policy in force, including which rules are deterministic
4. Assurance scan results plus a table of still-open failures
5. Exception register with justification, approver and expiry
6. Audit-ledger integrity — what makes sections 1-5 evidence rather than assertion

**The pack contains no personal data.** Witness values appear only as truncated salted one-way hashes.

## API

```
GET    /api/compliance/posture
POST   /api/compliance/scans                    { scanType, targetId, sourceId?, policyId?, schemaName?, environment?, name? }
GET    /api/compliance/scans                    ?scanType=&targetId=&limit=
GET    /api/compliance/scans/{id}
DELETE /api/compliance/scans/{id}
POST   /api/compliance/subject-search           { subjectValue, piiType?, targetId? }
GET    /api/compliance/exceptions
POST   /api/compliance/exceptions               { dataSourceId, environment?, scope, piiType?, justification, compensatingControls?, days? }
POST   /api/compliance/exceptions/{id}/approve  { note? }
POST   /api/compliance/exceptions/{id}/reject   { reason }
POST   /api/compliance/exceptions/{id}/revoke   { reason? }
DELETE /api/compliance/exceptions/{id}
POST   /api/compliance/evidence-pack            { targetId, sourceId?, policyId?, schemaName? }
GET    /api/compliance/evidence-pack/download   ?targetId=&sourceId=&policyId=&schemaName=
```

## Permissions

| Permission | Grants | Roles |
|---|---|---|
| `compliance.read` | View posture, scans, findings, exceptions; compile an evidence pack | Architect, Data Engineer, Tester, **Auditor** |
| `compliance.run` | Run scans, subject searches; request an exception; delete scan records | Architect, Data Engineer |
| `compliance.approve` | Approve / reject / revoke an exception | Architect (+ Admin via `admin.all`) |

An **Auditor** deliberately gets `compliance.read` only: the party attesting to the evidence must not
be the party producing it.

## Safety properties

* **Read-only.** Scans only `SELECT`. Nothing is written to a scanned environment.
* **Row-capped.** 2,000,000 rows per column, streamed with a fetch size of 5,000, so a scan cannot
  become an outage. Subject probes carry a 60-second query timeout.
* **Drift-tolerant.** Every column is metadata-checked (across identifier casings) before it is
  queried, so a masking rule that outlived a dropped column produces a skip, not a crash.
* **No PII in evidence.** Findings store a truncated salted hash as a witness, never a value.
* **Parameterised.** The subject value is always bound as a parameter; identifiers are whitelisted
  against `[A-Za-z0-9_$#]+` and quoted before use.
* **Tenancy.** Scans and exceptions carry owner/group/visibility and are filtered by `OwnershipGuard`.
* **Audited.** `COMPLIANCE_SCAN_*`, `COMPLIANCE_EVIDENCE_PACK_BUILT` and `PII_EXCEPTION_*` events land
  in the hash-chained ledger.

## Components

| Piece | Path |
|---|---|
| Migration | `src/main/resources/db/migration/V79__compliance_assurance.sql` |
| Entities / repos | `io/forgetdm/compliance/{ComplianceScan,ComplianceFinding,PiiException}Entity.java` + `*Repository.java` |
| Real-PII detection | `io/forgetdm/compliance/PiiRealityCheck.java` |
| Salted hashing | `io/forgetdm/compliance/ComplianceHasher.java` |
| Coverage / leak / cardinality | `io/forgetdm/compliance/ComplianceScanner.java` |
| Subject erasure | `io/forgetdm/compliance/SubjectErasureScanner.java` |
| Exception register | `io/forgetdm/compliance/PiiExceptionService.java` |
| Orchestration | `io/forgetdm/compliance/ComplianceService.java` |
| Evidence pack | `io/forgetdm/compliance/EvidencePackService.java` |
| REST API | `io/forgetdm/compliance/ComplianceController.java` |
| Frontend | `frontend/src/features/compliance/` + `frontend/src/app/compliance/page.tsx` |
| Tests | `src/test/java/io/forgetdm/compliance/PiiRealityCheckTest.java` |

## Suggested cadence

Monthly per environment, and after every refresh:

1. `FULL` scan against the environment with its source and policy
2. Resolve `FAIL` findings, or register an exception with an expiry
3. Build the evidence pack and file it with the monthly control report

Run a `SUBJECT` search on demand whenever legal receives an erasure request.
