# Self-Service Test Data — Tester-First Blueprint

Document owner: Product Engineering
Status: Proposed target-state design
Audience: Product Managers, QA Leads, TDM Engineers
Applies to: ForgeTDM self-service provisioning, Business Entity, Synthetic Data, DataScope, Topology, Data Mission, governance, and audit

## 0. Problem statement

A tester wants to say, in plain language:

> Create a customer test record with a Demand Deposit Account (DDA) with a balance of $100 and an active mortgage loan associated with the customer profile.

…and receive the data, plus a clear confirmation of what was created — **without** knowing databases, tables, blueprints, catalogs, drivers, traversal rules, or masking policies.

Today the platform *can* produce this data, but the tester-facing path forces them to think like a TDM engineer. This blueprint defines the missing simplicity-and-transparency layer that sits on top of the orchestration we already have (Topology, Business Entity, Synthetic, DataScope, Data Mission, provisioning, governance, audit).

Design priorities, in order: **1) tester simplicity, 2) transparency, 3) minimal required technical knowledge.**

---

## 1. Current state

### 1.1 Flow today

```text
Tester
  │  (must already know: which system, which schema/tables,
  │   which blueprint/entity, driver/root, traversal, masking)
  ▼
Self-Service ──▶ pick a published product/template ──▶ fill technical parameters
  │                                                        │
  │ (if no product exists) ──▶ ask a TDM engineer ─────────┘
  ▼
DataScope / Synthetic / Business Entity  (engineer-built assets)
  ▼
Provisioning job  ──▶  approval gate (if PROD-derived)
  ▼
Data lands in a target ──▶ tester hunts for it (which rows? which IDs? where?)
```

### 1.2 Where complexity arises (the friction map)

| Stage | What the tester is forced to do | Why it hurts |
|---|---|---|
| Discover what's possible | Browse catalogs/blueprints named in technical terms | No business vocabulary ("customer with a mortgage") |
| Express the need | Choose tables, drivers, traversal depth, row caps | Requires TDM-engineer mental model |
| Fill parameters | Map "$100 balance" to a column + generator + policy | Tester doesn't know the schema |
| Wait | Watch a job with technical status | No plain-language "what will I get" |
| Find the result | Query the target to locate the created rows/IDs | The hardest part — "where is my data?" |
| Trust it | Assume masking/governance was applied | No plain confirmation of safety |

**Net:** the platform is powerful but assumes the requester is the builder. The three missing layers are a **business-vocabulary catalog**, a **plain-language intake + confirmable plan**, and a **visibility/receipt layer** that closes the loop.

---

## 2. Gap analysis

### 2.1 Functional gaps

1. **No business-asset catalog.** There is no curated library of tester-meaningful "data products" ("Customer", "DDA account", "Active mortgage") that maps to the underlying Business Entities / Synthetic recipes / DataScope blueprints.
2. **No intent interpretation.** Plain-language requests are not parsed into a structured, parameterized plan (entities + attributes + relationships + quantity + environment).
3. **No confirmable plan preview in business terms.** Plan Preview exists technically (subset plan, synthetic plan) but is expressed in tables/rows, not "1 customer + 1 DDA ($100) + 1 active mortgage."
4. **No result capture / receipt.** After provisioning, the concrete created identifiers (customer id, account number, loan id) are not surfaced back as a structured, plain-language confirmation.
5. **No request lifecycle for testers.** No "my requests", re-run, reserve-for-a-test-case, refresh, or teardown from the tester's point of view.
6. **Attribute → policy binding is manual.** "$100 balance", "active status" must be manually mapped to columns/generators/masking; there is no attribute vocabulary.

### 2.2 UX gaps

1. **Technical vocabulary everywhere** — schemas, blueprints, drivers, traversal, Q1/Q2, policies.
2. **No plain-language entry point** — the first screen asks for a product/template, not "what do you need?".
3. **No transparency of outcome before or after** — the tester can't see, in their own words, what *will* be and *was* created.
4. **"Where is my data?" is unsolved** — the single biggest complaint; the tester is left to locate rows themselves.
5. **No trust signals** — masking applied? safe to use? approvals needed? — not shown plainly.
6. **No guardrails-as-help** — errors are technical ("missing FK", "unapproved relationship") instead of "we couldn't link the mortgage to the customer; here's what to do."

### 2.3 What already exists (reuse, don't rebuild)

- **Business Entity** — Customer-360 cross-application objects, members, identity crosswalks, execution plans → the natural backing for "Customer".
- **Synthetic Data** — relational generation, generators, constraints, saved jobs, plan preview → the backing for "create new".
- **DataScope** — subset/mask/provision of existing data → the backing for "carve from real".
- **Topology / Data Mission** — relationship intelligence and story-to-data orchestration → the backing for interpretation + assembly.
- **Provisioning + governance** — jobs, approval gates, masking, PII policy, audit hash-chain, RBAC/ownership → execution + safety.
- **AI copilot** — metadata-grounded planning (real data values are never sent to a model) → the interpretation engine.

The gaps are **assembly and presentation**, not core capability.

---

## 3. Future-state blueprint

### 3.1 End-to-end user flow

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. ASK (plain language)                                                       │
│    "Create a customer with a DDA of $100 and an active mortgage."             │
│    + environment (defaulted)   + quantity (defaulted 1)                       │
└───────────────┬─────────────────────────────────────────────────────────────┘
                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. INTERPRET  (Catalog Abstraction + Data Mission/AI planner)                 │
│    → Customer  ×1                                                             │
│    → DDA account (balance = $100, status = OPEN)  linked to Customer          │
│    → Mortgage loan (status = ACTIVE)              linked to Customer          │
│    Confidence + any missing decisions surfaced as simple questions            │
└───────────────┬─────────────────────────────────────────────────────────────┘
                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. REVIEW PLAN (plain language, editable chips — no tables shown)             │
│    "I'll create 1 customer, 1 DDA account with a $100 balance, and 1 active   │
│     mortgage, all linked. Target: SIT. Data is synthetic & masked-safe.       │
│     ~30 seconds."     [ Edit ]   [ Confirm & create ]                         │
└───────────────┬─────────────────────────────────────────────────────────────┘
                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 4. ORCHESTRATE (compiler → existing executable assets)                        │
│    Customer  → Business Entity execution plan                                 │
│    DDA/Mortgage → Synthetic relational generation (RI-linked to the customer) │
│    Masking/PII + governance applied; approval only if PROD-derived            │
└───────────────┬─────────────────────────────────────────────────────────────┘
                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 5. CONFIRM / RECEIPT (transparency — "here is exactly what you got")          │
│    Customer C-88213 (Jane Doe) · DDA A-4471 $100.00 OPEN · Mortgage L-9902    │
│    ACTIVE · Environment SIT · how to find it · masked-safe · audit ref        │
│    [ Provision more like this ]  [ Reserve for TC-1234 ]  [ Tear down ]       │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Responsibilities (who does what)

| Layer | Responsibility | Backed by |
|---|---|---|
| **Intake** | Plain-language request + minimal optional structure (env, qty, purpose) | New tester UI |
| **Catalog Abstraction** | A library of business assets ("Customer", "DDA", "Mortgage") with plain names, editable attributes, and a binding to executable assets | New `TestDataCatalog` over Business Entity + Synthetic saved jobs + DataScope + Topology domains |
| **Interpretation (Data Mission)** | Parse text → assets + attributes + links + quantity; ask only for genuinely ambiguous decisions; produce a confirmable plan | AI planner (metadata-only) + rules + Forge Data Store grounding + Topology |
| **Plan** | Render the plan in business language; let the tester edit via chips; never expose tables | New UI + a plain-language plan model |
| **Orchestration** | Compile the plan into Business Entity / Synthetic / DataScope runs; enforce RI, masking, PII policy, governance; capture created identifiers | Existing provisioning + BE execution plans + Synthetic + governance |
| **Visibility / Receipt** | Return the created objects, IDs, attributes, location, safety, and audit ref in plain language; track lifecycle (queued→ready→reserved→torn down) | New `provision receipt` + request-history store |
| **Governance & Audit** | Ownership/tenancy, approval only where required, tamper-evident audit of the whole request→result chain | Existing RBAC + maker-checker + audit hash-chain |

### 3.3 UI wireframe concepts

**A. New request (the only screen most testers ever need)**

```text
┌───────────────────────────────  Request test data  ───────────────────────────┐
│                                                                                 │
│   What data do you need?                                                        │
│  ┌───────────────────────────────────────────────────────────────────────────┐│
│  │ Create a customer with a DDA of $100 and an active mortgage.               ││
│  └───────────────────────────────────────────────────────────────────────────┘│
│   Try:  [Customer with a DDA]  [Customer + active mortgage]  [Delinquent loan] │
│                                                                                 │
│   Environment:  ( SIT ▾ )     How many:  ( 1 ▾ )     For:  ( TC-1234, optional )│
│                                                                                 │
│                                                     [  Preview what I'll get →  ]│
└─────────────────────────────────────────────────────────────────────────────────┘
```

**B. Review plan (business language, editable — no schema)**

```text
┌────────────────────────────────  Review & confirm  ───────────────────────────┐
│  You'll get 1 record set in SIT · synthetic & masked-safe · ~30s               │
│                                                                                 │
│  ● Customer                                             [ auto-named ]          │
│  ● DDA account        balance ( $100 )   status ( OPEN )      linked ✓          │
│  ● Mortgage loan      status ( Active ▾ ) principal ( auto )  linked ✓          │
│                                                                                 │
│  ⚠ Nothing needs approval (no production data used).                            │
│                                              [ Edit request ]  [ Confirm & create ]│
└─────────────────────────────────────────────────────────────────────────────────┘
```

**C. Result / receipt (the "where is my data" answer)**

```text
┌──────────────────────────────────  Ready ✓  ──────────────────────────────────┐
│  Request REQ-2026-000481 · created by qa.jane · SIT · 27s                       │
│  Summary: 1 customer with a $100 DDA and an active mortgage.                     │
│                                                                                 │
│  Customer     C-88213   "Jane Doe"        CIF 88213                              │
│  DDA account  A-4471     balance $100.00  status OPEN     → belongs to C-88213   │
│  Mortgage     L-9902     status ACTIVE    principal $250,000 → belongs to C-88213│
│                                                                                 │
│  How to find it:  SIT Core Banking · WHERE cif = '88213'         [ copy ]        │
│  Safety: masked & synthetic — safe for test.  Audit: AUD-7f3c…   [ view ]        │
│                                                                                 │
│  [ Provision more like this ]  [ Reserve for TC-1234 ]  [ Download summary ]  [ Tear down ]│
└─────────────────────────────────────────────────────────────────────────────────┘
```

**D. My requests** — a simple list: request text, status (Queued / Creating / Ready / Reserved / Torn down), environment, when, and a re-run button. No technical columns.

---

## 4. Tester-facing contract

### 4.1 Request schema (minimal, plain-language)

The tester contract is deliberately tiny — everything else is interpreted and confirmed in the plan.

```json
{
  "request": "Create a customer with a DDA of $100 and an active mortgage",
  "environment": "SIT",
  "quantity": 1,
  "purpose": "TC-1234 regression"
}
```

- `request` (required) — free text.
- `environment` (optional) — defaults from the tester's profile.
- `quantity` (optional) — defaults to 1.
- `purpose` (optional) — free text, carried into audit and used for "reserve".

Advanced users *may* pass explicit structured constraints, but they are never required:

```json
"constraints": [
  { "asset": "DDA",      "attribute": "balance", "value": 100, "unit": "USD" },
  { "asset": "Mortgage", "attribute": "status",  "value": "ACTIVE" }
]
```

### 4.2 Interpreted plan (returned for confirmation, in business terms)

```json
{
  "requestId": "REQ-2026-000481",
  "environment": "SIT",
  "summary": "1 customer with a $100 DDA account and an active mortgage.",
  "safety": { "dataOrigin": "SYNTHETIC", "maskingApplied": true, "approvalRequired": false },
  "assets": [
    { "asset": "Customer", "quantity": 1, "attributes": {} },
    { "asset": "DDA account", "quantity": 1, "attributes": { "balance": "100.00 USD", "status": "OPEN" }, "linkedTo": "Customer" },
    { "asset": "Mortgage loan", "quantity": 1, "attributes": { "status": "ACTIVE" }, "linkedTo": "Customer" }
  ],
  "openQuestions": [],
  "estimatedSeconds": 30
}
```

### 4.3 Confirmation / response payload (exactly what was provisioned)

```json
{
  "requestId": "REQ-2026-000481",
  "status": "READY",
  "summary": "Created 1 customer with a $100 DDA account and an active mortgage in SIT.",
  "environment": "SIT",
  "provisioned": [
    { "type": "Customer", "id": "C-88213", "label": "Jane Doe", "keys": { "CIF": "88213" } },
    { "type": "DDA account", "id": "A-4471", "linkedTo": "C-88213",
      "attributes": { "balance": "100.00 USD", "status": "OPEN", "openedDate": "2026-07-21" } },
    { "type": "Mortgage loan", "id": "L-9902", "linkedTo": "C-88213",
      "attributes": { "status": "ACTIVE", "principal": "250000.00 USD", "rate": "5.25%" } }
  ],
  "howToAccess": {
    "environment": "SIT Core Banking",
    "find": "WHERE cif = '88213'",
    "note": "Synthetic & masked — safe for test use."
  },
  "governance": { "dataOrigin": "SYNTHETIC", "maskingApplied": true, "approvals": "none required" },
  "audit": { "auditRef": "AUD-7f3c9a…", "createdBy": "qa.jane", "createdAt": "2026-07-21T18:20:11Z" },
  "lifecycle": { "reservable": true, "refreshable": true, "teardownable": true },
  "actions": ["provisionMoreLikeThis", "reserve", "downloadSummary", "teardown"]
}
```

Every field is expressed in the tester's vocabulary. The only "technical" item — `find` — is a copy-paste locator, not something they must construct.

---

## 5. Implementation roadmap

Sequence is delivery order, not reduced scope. Effort: S ≈ ≤2 wks, M ≈ 2–5 wks, L ≈ 6–10 wks (one squad).

### Phase 1 — Catalog abstraction (the business vocabulary)  · Effort M · Risk L
- A `TestDataCatalog` of named business assets ("Customer", "DDA account", "Mortgage loan") with plain names, an editable attribute vocabulary (balance, status, …), and a binding to an executable backing (Business Entity / Synthetic saved job / DataScope blueprint).
- Seed the catalog from existing published assets; let TDM engineers curate names/attributes once.
- *Reuse:* Self-Service v2 catalog, Business Entity, Synthetic saved jobs, Topology domains.
- *Deliverable:* testers can browse/pick assets in business terms even before NL is added.

### Phase 2 — Plain-language intake + confirmable plan  · Effort M–L · Risk M
- Request intake screen (§3.3-A) + interpretation (text → assets/attributes/links/quantity) via the AI planner grounded on the catalog + Forge Data Store; ask only for genuinely ambiguous decisions.
- Plan model + review screen (§3.3-B) in business language; **confirm-before-run** is mandatory (mitigates NLU error).
- Structured-constraint fallback so a request always resolves even if NL is uncertain.
- *Risk mitigation:* the plan is always shown and editable; nothing runs without confirmation; low-confidence terms become simple questions, not failures.

### Phase 3 — Orchestration compiler  · Effort M · Risk M
- Compile a confirmed plan into existing executable runs (Business Entity execution plan for the customer; Synthetic relational generation for the linked accounts/loan), enforcing referential integrity, masking/PII, and governance.
- Capture created identifiers as the run executes (customer id, account no, loan id).
- *Reuse:* provisioning engine, BE execution plans, Synthetic, governance gates, audit.

### Phase 4 — Visibility / receipt + lifecycle  · Effort M · Risk L
- Receipt screen (§3.3-C) + `provision receipt` payload; "my requests" list; reserve / refresh / teardown; "provision more like this".
- Plain-language status and error help.
- *Reuse:* jobs/status, reservations, audit; new receipt store keyed to the request.

### Phase 5 — Self-service polish  · Effort M · Risk L
- Reusable request templates & sharing; environment routing; quotas/SLA; automatic teardown/cleanup; metrics (time-to-data, self-serve rate).

**Required components (cross-phase):** (1) Catalog abstraction, (2) Interpretation/Data-Mission planner, (3) Orchestration compiler, (4) Visibility/receipt + lifecycle. Each maps to existing ForgeTDM building blocks; the new work is assembly + presentation + a business vocabulary.

**Top risks & mitigations**
- *NLU misreads intent* → confirm-before-run + editable plan + structured fallback (M→L).
- *Attribute→policy binding drift* → the catalog owns the vocabulary; engineers curate once; drift surfaces in the plan (L).
- *"Where is my data" still unclear* → the receipt's `howToAccess` + stable IDs are acceptance-gated (L).
- *Governance bypass* → self-service requests inherit the same approval/masking/audit as any provisioning; approval only when PROD-derived (M).

---

## 6. Acceptance criteria & example stories

### 6.1 Acceptance criteria

**Simplicity**
1. A tester can submit a request in one free-text field with no schema, table, blueprint, or policy knowledge.
2. Nothing on the intake or plan screen names a table, driver, traversal, or masking policy.
3. Sensible defaults (environment, quantity) require zero input for the common case.

**Transparency**
4. Before running, the tester sees a plain-language plan of exactly what will be created and can edit it.
5. After running, the tester receives a receipt listing every created object, its business attributes, its links, its location, and a safety statement.
6. The receipt answers "where is my data" with stable IDs + a copy-paste locator.

**Trust & governance**
7. The receipt states data origin (synthetic/subset), whether masking was applied, and whether any approval was needed.
8. The full request → plan → run → result chain is audited (tamper-evident) and attributed to the tester.
9. Requests respect tenancy/ownership; PROD-derived requests still hit the existing approval gate.

**Correctness**
10. Linked assets are referentially consistent (the DDA and mortgage belong to the created customer).
11. Requested attributes are honored ($100 balance; ACTIVE mortgage) or the plan explains why not before running.
12. A failed run yields a plain-language reason and a safe, partial-free state (no orphaned half-records).

### 6.2 Worked example — "DDA $100 + active mortgage"

**Request (what the tester types)**
> Create a customer with a DDA of $100 and an active mortgage.

**Interpretation → plan (shown for confirmation)**
- Customer ×1
- DDA account · balance **$100** · status **OPEN** · linked to the customer
- Mortgage loan · status **ACTIVE** · principal auto · linked to the customer
- Environment **SIT** · synthetic & masked-safe · no approval needed · ~30s
→ Tester clicks **Confirm & create**.

**Provisioning (automatic, hidden from the tester)**
1. Compile plan → Business Entity execution plan (Customer) + Synthetic relational generation (DDA, Mortgage) linked by customer key.
2. Generate the customer; generate a DDA with balance 100.00/OPEN; generate a mortgage with status ACTIVE; enforce referential integrity + masking/PII.
3. Capture IDs; write audit; assemble receipt.

**Confirmation (what the tester gets back)**
> ✅ Ready — REQ-2026-000481 (SIT, 27s)
> Created **1 customer with a $100 DDA and an active mortgage.**
> • Customer **C-88213** "Jane Doe" (CIF 88213)
> • DDA account **A-4471** — balance **$100.00**, status **OPEN** → belongs to C-88213
> • Mortgage **L-9902** — status **ACTIVE**, principal $250,000 → belongs to C-88213
> Find it: **SIT Core Banking, WHERE cif = '88213'**. Masked & synthetic — safe for test.
> Audit: AUD-7f3c9a. [Provision more like this] [Reserve for TC-1234] [Tear down]

The tester never saw a table, blueprint, or policy — and knows exactly what they got and where it is.

---

## 7. Success measures

- **≥ 90%** of common test-data requests completed with **no TDM-engineer involvement**.
- **Time-to-data ≤ 1 minute** for the DDA+mortgage class of request.
- **100%** of requests produce a receipt with stable IDs and a locator ("where is my data" solved).
- **Zero** ungoverned/unaudited self-service provisions.
- **Measurable drop** in "help me build test data" tickets to the TDM team.

## 8. Final target state

The tester begins with a sentence, confirms a plan in their own words, and receives a receipt that says exactly what was created and where to find it — safely, and without ever thinking like a TDM engineer. The heavy machinery (Topology, Business Entity, Synthetic, DataScope, Data Mission, governance, audit) does the work behind a simple, transparent, plain-language surface.
