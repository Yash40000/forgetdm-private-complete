# ForgeTDM Scenario Fabric User Guide

## Purpose

Scenario Fabric turns a business testing need into a reusable, governed test-data
request. A tester selects an approved scenario, chooses the required variation,
reviews the automatically generated coverage, and launches the request through an
approved ForgeTDM delivery product.

This guide uses the live **Credit Limit Decline Boundary** scenario as the primary
example.

## Access and prerequisites

Open the Next UI at:

`http://localhost:3000/scenario-fabric`

The backend API must be available at:

`http://localhost:8088`

Required permissions:

| Activity | Permission |
|---|---|
| View Scenario Fabric | `scenario.read` |
| Publish Test Domains and manage Blueprints | `scenario.manage` |
| Compile and launch Missions | `scenario.run` |

Before a Mission can execute:

1. A discovered Data Topology must have at least one published version.
2. The topology must be published as a Test Domain.
3. The Test Domain or Blueprint must reference an approved Self-Service product.
4. The selected product must support the target environment and requested volume.

## Navigation

1. Sign in to ForgeTDM.
2. In the left navigation, find **Provision & deliver**.
3. Select **Scenario Fabric**.

The Scenario Fabric page contains:

| Area | Purpose |
|---|---|
| **Test Domains** | Manage governed topology context and delivery bindings. |
| **Blueprint Studio** | Create and version reusable business scenarios. |
| **New mission** | Compile a tester's request from a Scenario Blueprint. |
| **Approved scenarios** | Search and select reusable scenario cards. |
| **Mission center** | Open planned, running, completed, or failed Missions. |

## Concepts

### Test Domain

A Test Domain is a governed snapshot of a Data Topology. It pins the topology
version and hash so a Mission cannot silently use a different system model.

### Scenario Blueprint

A Blueprint defines:

- Required business state
- Business event
- Expected outcome
- Coverage techniques
- Tester-selectable variation domains
- Delivery product
- Technical and semantic verification rules

Updating an existing Blueprint publishes a new immutable version. Existing
Missions remain pinned to the version from which they were compiled.

### Test Data Mission

A Mission is a tester-specific request compiled from a Blueprint. It retains:

- Tester objective
- Selected variation values
- Environment and data strategy
- Requested volume
- Generated coverage cases
- Execution plan
- Approval and execution history
- Verification evidence and Ready-to-Test handoff

## Live validation content

The live **Customer 360 Validation Domain** is pinned to topology version 6 and
contains these published Blueprints:

| Scenario | Purpose | Compiled cases in the prepared Mission |
|---|---|---:|
| Active Card Purchase Approval | Validate a successful purchase and ledger hold. | 13 |
| Credit Limit Decline Boundary | Validate decline behavior around available credit. | 11 |
| Dormant Customer Reactivation | Validate controlled state transition and audit evidence. | 9 |

Loading the supplied validation scenarios is idempotent. Running the loader again
returns the same three Blueprints without creating duplicates.

## End-to-end example: Credit Limit Decline Boundary

### 1. Find the scenario

1. Open **Scenario Fabric**.
2. In **What does your test need?**, search for `credit`, `decline`, or
   `boundary`.
3. Locate **Credit Limit Decline Boundary** under **Approved scenarios**.
4. Confirm the card shows:
   - Entity type: `Card authorization`
   - Blueprint version: `v1`
   - Coverage: `BASELINE`, `BOUNDARY`, `NEGATIVE`, and `PAIRWISE`

### 2. Start the request

1. Select **Request data** on the scenario card.
2. The **New test data mission** drawer opens.
3. Confirm **Scenario Blueprint** is `Credit Limit Decline Boundary`.

### 3. Describe the Mission

Complete the Mission fields:

| Field | Example |
|---|---|
| **Mission name** | `Validate credit limit decline boundary` |
| **What must your test prove?** | `Prove the authorization boundary immediately above available credit, including the decline reason and absence of a ledger hold.` |
| **Target environment** | `QA` |
| **Data strategy** | `Auto-select best strategy` |
| **Requested entities / rows** | `100` |

Mission names must contain 8-160 characters. The test objective must contain
20-4000 characters.

### 4. Choose the business variation

Under **Scenario choices**, select:

| Choice | Example |
|---|---|
| **Purchase channel** | `POS` |
| **Card network** | `VISA` |
| **Merchant risk** | `HIGH` |

Optionally enable **Reserve this test data for my team** and choose a reservation
period between 1 and 720 hours.

### 5. Compile the Mission

1. Select **Compile mission**.
2. ForgeTDM validates all required choices.
3. The compiler pins:
   - Test Domain and topology version
   - Blueprint version
   - Selected delivery product
   - Environment, strategy, and requested volume
4. ForgeTDM creates the deterministic coverage cases.
5. The full-screen Mission workspace opens.

For this example, the compiler creates 11 cases:

- 1 baseline case
- 3 boundary cases
- 1 negative case
- 6 pairwise cases

### 6. Review before launch

Review these sections in the Mission workspace:

#### Test objective

Confirms the tester's requested behavior, strategy, environment, row count, and
compiled case count.

#### Execution story

Shows the business-readable execution sequence:

1. Resolve business intent and pinned versions.
2. Compose test coverage.
3. Select the delivery strategy.
4. Apply privacy and integrity guardrails.
5. Deliver through the approved product.
6. Verify readiness.

#### Coverage contract

Expand each case to inspect:

- Case type
- Input state
- Expected result

For the decline scenario, expected results include:

- `authorization.status = DECLINED`
- `authorization.reason = CREDIT_LIMIT_EXCEEDED`
- `ledger.holdStatus = NOT_CREATED`

Do not launch when the Mission shows `NEEDS BINDING`. Open **Test Domains** and
attach an approved delivery product first.

### 7. Launch

1. Select **Launch mission**.
2. ForgeTDM creates the governed Self-Service request.
3. The next state depends on the product:
   - No approval required: execution starts directly.
   - Approval required: Mission becomes `WAITING APPROVAL`.

For an approval-controlled request:

1. An authorized reviewer approves it in **Self-Service**.
2. Reopen the Mission.
3. Select **Launch approved request**.

### 8. Monitor execution

1. Open the Mission from **Mission center**.
2. Select **Refresh status** while execution is active.
3. Review:
   - Mission readiness percentage
   - Latest activity event
   - Underlying Self-Service request reference
   - Verification results when execution finishes

The **Active**, **Ready**, and **All** filters control which Missions are displayed.

### 9. Validate the evidence

After the delivery engine reaches a terminal status, ForgeTDM evaluates:

| Check | Meaning |
|---|---|
| `ENGINE_COMPLETED` | The underlying delivery job completed. |
| `NO_REJECTS` | The delivery engine reported no rejected rows. |
| `TOPOLOGY_COMPATIBLE` | The pinned topology still matches the current governed version. |
| `COVERAGE_RETAINED` | The compiled coverage contract remains attached to the Mission. |
| Semantic predicates | Business-result evidence supplied by the execution adapter matches expected values. |

Possible successful states:

- `READY`: all required checks passed.
- `READY_WITH_WARNINGS`: technical delivery completed, but one or more evidence
  checks need review.

### 10. Use the Ready-to-Test pack

When available, the **Ready-to-Test pack** contains:

- Delivery run reference
- Rows delivered
- Readiness timestamp
- Scenario case handles
- Pinned domain, topology, and Blueprint lineage
- Verification outcome

The tester can use the scenario handles to identify which prepared data satisfies
each generated case.

## Opening the three prepared Missions

The three example Missions already appear in **Mission center** with status
`PLANNED`:

1. `Validate active card purchase approval`
2. `Validate credit limit decline boundary`
3. `Validate dormant customer reactivation`

Select a Mission row to open its full-screen execution and evidence workspace.
They have not been launched automatically.

## Test Domain administration

### Review the live Test Domain

1. Select **Test Domains** in the Scenario Fabric header.
2. Select **Customer 360 Validation Domain** from **Published domains**.
3. Review:
   - Topology version and hash
   - Systems and source count
   - Business object count
   - Relationship count
   - Execution binding

### Load supplied validation scenarios

1. Open **Customer 360 Validation Domain**.
2. Select **Load validation scenarios**.
3. ForgeTDM confirms that three reusable banking scenarios are available.

This action can be repeated safely; it does not duplicate a Blueprint with the
same name in the same Test Domain.

### Attach a delivery product

1. In the selected Test Domain, find **Execution binding**.
2. Choose an **Approved delivery product**.
3. Attach the product.

New Missions use the Blueprint's explicit product when one is configured.
Otherwise, they use the Test Domain's execution binding.

### Publish another Test Domain

1. Select **Test Domains**.
2. Select **Publish topology**.
3. Choose a completed discovered topology.
4. Enter a Test Domain name between 8 and 120 characters.
5. Complete business domain, visibility, and purpose.
6. Select **Publish and create starter Blueprint**.

The source topology must already contain a published version and at least one
business object.

## Blueprint Studio

Open **Blueprint Studio** to review or author reusable scenarios.

### Create a Blueprint

1. Select **New Blueprint**.
2. Choose the Test Domain.
3. Enter a Blueprint name and business object or capability.
4. Describe the tester-facing purpose.
5. Define **Required state**, one `field=value` condition per line.
6. Define the **Business event**.
7. Define **Expected outcomes**, one `field=value` assertion per line.
8. Select coverage techniques.
9. Define variation domains, for example:

   ```text
   channel=WEB|MOBILE|POS
   cardNetwork=VISA|MASTERCARD
   merchantRisk=LOW|HIGH
   ```

10. Select an approved Self-Service product or use the Test Domain default.
11. Optionally define semantic predicates.
12. Select **Create Blueprint**.

### Version a Blueprint

1. Select an existing Blueprint from **Blueprint library**.
2. Edit the contract.
3. Select **Publish new version**.

The existing record is preserved as an immutable version. Previously compiled
Missions continue to reference their original Blueprint version.

## Mission status reference

| Status | Meaning | User action |
|---|---|---|
| `PLANNED` | Coverage and execution plan compiled successfully. | Review and launch. |
| `NEEDS_BINDING` | No approved delivery product is attached. | Bind a product in Test Domains. |
| `WAITING_APPROVAL` | Self-Service maker-checker approval is required. | Authorized reviewer approves the request. |
| `APPROVED` | Request passed approval but execution has not started. | Select **Launch approved request**. |
| `RUNNING` | Delivery engine is executing. | Use **Refresh status**. |
| `READY` | Delivery and required verification passed. | Use the Ready-to-Test pack. |
| `READY_WITH_WARNINGS` | Data was delivered, but evidence needs review. | Inspect failed or unavailable checks. |
| `FAILED` | The delivery engine or required verification failed. | Review activity and underlying run logs. |
| `CANCELED` | The underlying request or execution was cancelled. | Compile or launch a replacement Mission if needed. |

## Important execution boundary

Scenario Fabric compiles, versions, governs, and retains the tester's business
choices. Physical data creation is performed by the attached Self-Service
product.

The current live examples are attached to **GW Billing Regression Data Pack** so
the complete planning, governance, approval, launch, and status lifecycle can be
validated. That saved Synthetic product does not yet translate arbitrary
card-specific choices such as `cardNetwork` or `merchantRisk` into modified
generator rules.

For production-quality card scenario execution, attach a card-aware delivery
product or implement a product parameter adapter that maps each Blueprint choice
to explicit generator, subset, reservation, or virtual-data controls. Do not treat
a successful generic product run as proof that card-specific semantic predicates
were physically produced.

## Troubleshooting

### No scenarios are visible

- Clear the scenario search box.
- Select **Test Domains** and confirm a domain is published.
- Use **Load validation scenarios** for the supplied examples.
- Confirm the user has `scenario.read`.

### Compile mission is disabled

- Enter a Mission name of at least eight characters.
- Enter a test objective of at least 20 characters.
- Select an environment.
- Enter a requested count of at least one.
- Complete every required Scenario choice.

### Mission shows NEEDS BINDING

- Open **Test Domains**.
- Select the Mission's Test Domain.
- Attach an approved Self-Service product under **Execution binding**.
- Recompile the Mission so the executable binding is pinned into its plan.

### Mission waits for approval

- Open **Self-Service**.
- Have an authorized reviewer approve the generated request.
- Return to Scenario Fabric and select **Launch approved request**.

### Refresh status is disabled

The Mission has not created a Self-Service request yet. Select **Launch mission**
first.

### READY_WITH_WARNINGS appears

Open **Verification** and identify whether the warning is caused by rejects,
topology drift, coverage evidence, or unavailable semantic predicate evidence.
The underlying delivery may have succeeded even though the full business proof is
incomplete.

## Recommended validation order

1. Open each prepared Mission and inspect its generated cases.
2. Confirm each Mission is pinned to topology version 6 and Blueprint version 1.
3. Validate the no-duplicate behavior of **Load validation scenarios**.
4. Launch only a non-production, disposable-environment Mission.
5. Confirm the Self-Service request and execution reference appear.
6. Refresh until the execution reaches a terminal state.
7. Inspect verification checks and the Ready-to-Test pack.
8. Repeat with an approval-controlled product to validate maker-checker behavior.
