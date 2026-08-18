# ForgeTDM Scenario Fabric Blueprint

Document owner: Product Engineering
Status: Enterprise foundation implemented; advanced automation roadmap retained
Scope: Data Topology, Business Entity, Self-Service, DataScope, Synthetic Data, Mapping, Virtualization, and Forge Data Store

Implementation snapshot: 2026-07-23

- `V76__scenario_fabric.sql` persists Test Domains, immutable Blueprint versions, Missions, compiled cases, and Mission events.
- `/api/scenario-fabric` publishes discovered topologies, reads relationships from the pinned topology version, binds approved Self-Service products, versions Blueprints, compiles Missions, launches the real product engine, and retains technical verification and Ready-to-Test evidence.
- `/scenario-fabric` provides a Mission Center plus focused full-screen Test Domain, Blueprint, and Mission evidence workspaces.
- The deterministic compiler currently implements baseline, boundary, negative, state-transition, and constrained-size pairwise case generation.
- RBAC separates `scenario.read`, `scenario.manage`, and `scenario.run`; object visibility and requester ownership are enforced server-side.
- Fully evaluated target predicates, equivalence/decision-table authoring, downloadable fixture manifests, private intent grounding, and Cucumber import remain roadmap items.

## 1. Decision

ForgeTDM should not reproduce GenRocket G-Families or make its existing topology graph the tester experience.

The proposed product layer is **Forge Scenario Fabric**. It introduces four clear concepts:

1. **Test Domain** - an expert-governed, connected business scope built from topology, Business Entity, privacy, and delivery metadata.
2. **Scenario Blueprint** - a reusable definition of business preconditions, events, expected outcomes, coverage rules, and safe tester parameters.
3. **Test Data Mission** - one tester's concrete request to satisfy a story, test case, defect, or pipeline run.
4. **Ready-to-Test Pack** - the delivered data plus entity handles, expected states, verification evidence, reservation, lineage, and reset actions.

These terms are intentionally different from GenRocket terminology:

| GenRocket concept | Actual purpose | ForgeTDM concept |
| --- | --- | --- |
| G-Family | A discovered visual group of related schema domains used to create G-Cases | Test Domain |
| G-Case | Executable test-data instructions for a test case | Scenario Blueprint |
| G-Questionnaire | Safe tester changes to a prepared G-Case | Mission questionnaire |
| G-Portal | Request and delivery portal | Mission Center |
| G-Story / G-Epic | Ordered orchestration of cases and stories | Mission Journey |

ForgeTDM's advantage should be that a Test Domain is not merely a related-table group. It also contains business meaning, privacy controls, state rules, cross-system identity, valid delivery methods, and executable validation.

## 2. Research findings

### 2.1 GenRocket

GenRocket separates expert design from tester self-service:

- G-Families discovers related domains from imported schema relationships and lets permitted users generate suites of G-Cases.
- Test Data Engineers define G-Cases and G-Rules.
- Testers find or request G-Cases in G-Portal.
- G-Questionnaire lets testers vary approved values without changing the original case.
- G-Stories and G-Epics orchestrate multiple cases.
- G-Repository synchronizes executable assets into distributed environments.
- Scenarios can be launched from command line, scripts, Jenkins, REST, and test frameworks.

The useful pattern is the separation of responsibilities. The limitation is that a family begins as a technical relationship grouping and the delivered unit remains primarily an executable instruction set.

Official sources:

- https://www.genrocket.com/wp-content/uploads/2024/11/GenRocket-Solution-Overview-2312-01.pdf
- https://www.genrocket.com/wp-content/uploads/2023/05/GenRocket-Distributed-Self-Service-Platform.pdf
- https://www.genrocket.com/wp-content/uploads/2024/09/GenRocket-Life-Cycle-Management-for-Test-Data-Provisioning-2409-02.pdf
- https://www.genrocket.com/newsletter/better-data-driven-testing-with-cucumber-2/

### 2.2 K2view

K2view's strongest self-service patterns are:

- provision by a business entity rather than exposing tables;
- expert-created task templates with per-attribute runtime overrides;
- role-based task discovery and execution;
- selection using business parameters;
- masked, cloned, subsetted, generated, aged, reserved, versioned, and rolled-back data;
- entity data assembled across systems and optionally retained in a per-entity MicroDB;
- a newer agentic layer that interprets stories and test cases, selects a data strategy, and orchestrates existing TDM engines.

The useful pattern is business-first selection and governed execution. The limitation for ForgeTDM to avoid is treating natural-language interpretation as proof that the resulting data actually satisfies the test.

Official sources:

- https://support.k2view.com/Academy/articles/TDM/tdm_gui/14_task_overview.html
- https://support.k2view.com/Academy/articles/TDM/tdm_architecture/01_tdm_architecture.html
- https://www.k2view.com/solutions/test-data-management-tools/
- https://www.k2view.com/blog/agentic-tdm/

### 2.3 Other mature patterns

IBM Optim uses access definitions to define a start table, related tables, relationship traversal, criteria, sampling, and reusable processing scope. Informatica exposes prepared data packs through a simplified portal and includes data-coverage analysis. Delphix makes refresh, rewind, bookmarks, retention, and sharing first-class self-service operations.

ForgeTDM should combine these strengths:

- Optim-style explicit relationship and extraction control;
- Informatica-style coverage measurement;
- Delphix-style reset, snapshot, and collaboration;
- K2view-style business entity scope;
- GenRocket-style scenario variation and runtime automation.

Official sources:

- https://www.ibm.com/docs/en/iotdm/11.7.0?topic=overview-optim-test-data-management-solution
- https://www.ibm.com/docs/en/iodg/11.3.0?topic=reference-access-definitions
- https://docs.informatica.com/data-security-group/test-data-management/10-5-10/test-data-management-self-service-portal-guide/introduction-to-the-test-data-management-self-service-portal/test-data-management-self-service-portal-overview.html
- https://help.delphix.com/dct/current/content/dct_concepts.htm

## 3. Tester problem to solve

A tester rarely wants "500 customers." The real request is closer to:

> I need two active retail customers with Visa cards. One must be within one dollar of the daily limit and the other must exceed it after a pending transaction. Both must exist consistently in core banking, card servicing, CRM, and the outbound mainframe feed. Data must be masked, reserved for four hours, and resettable after the test.

A useful request therefore needs all of the following dimensions:

| Dimension | Examples |
| --- | --- |
| Business objects | Customer, account, card, claim, policy, order |
| Preconditions | Active, KYC complete, delinquent, premium, blocked |
| Events | Payment posted, card declined, claim submitted, reversal received |
| Expected outcomes | Warning displayed, decline code 51, fee waived |
| Relationships | Customer owns 2 accounts; account has 5 posted transactions |
| Cross-system identity | Customer number, party ID, card reference, mainframe key |
| Time | As-of date, aging, expiry, overdue days, transaction sequence |
| Value classes | Valid, invalid, null, boundary, rare, negative |
| Coverage | Equivalence partitions, boundaries, decision rules, state transitions, pairwise combinations |
| Volume | One exact entity, functional set, regression set, performance scale |
| Privacy | Masked real, synthetic, tokenized, no-production-data |
| Delivery | Database, file, API payload, message, mainframe record |
| Isolation | Reservation owner, expiry, reusable or single-use |
| Recovery | Snapshot, reset, rollback, cleanup |
| Verification | Queries and assertions proving the requested state exists |

ISTQB identifies equivalence partitioning, boundary value analysis, decision tables, state transitions, and use-case testing as core black-box techniques. NIST ACTS adds constrained t-way combination generation. ForgeTDM should make these test-design techniques available as data coverage choices rather than requiring testers to manually manufacture rows.

Sources:

- https://www.istqb.org/wp-content/uploads/2024/11/ISTQB-CTFL_Syllabus_2018_v3.1.1.pdf
- https://csrc.nist.gov/projects/automated-combinatorial-testing-for-software/
- https://cucumber.io/docs/gherkin/reference/

## 4. Product model

### 4.1 Test Domain

A Test Domain is the replacement for a simple data family. It contains:

- one or more applications and physical sources;
- approved topology nodes and relationships;
- one or more Business Entities and identity crosswalks;
- business terms and synonyms from Forge Data Store;
- PII classifications and required masking policies;
- reference-data dependencies;
- valid source strategies: subset, clone, synthetic, hybrid, snapshot;
- allowed target environments and delivery formats;
- lifecycle actions: reserve, refresh, reset, rollback, expire;
- approved Scenario Blueprints;
- quality and certification status.

Examples:

- Retail Customer and Accounts
- Card Authorization and Posting
- Mortgage Application Journey
- Insurance Claim Adjudication
- Customer Communication Preferences

### 4.2 Scenario Blueprint

A Scenario Blueprint is a governed, reusable specification:

```yaml
name: Card decline near daily limit
domain: Card Authorization and Posting
given:
  customer.status: ACTIVE
  card.status: OPEN
  card.network: [VISA, MASTERCARD]
  account.available_credit: boundary(daily_limit, offsets=[-1, 0, 1])
  pending_transactions.count: range(0, 3)
when:
  authorization.amount: 100
then:
  decision:
    rule:
      available_credit >= authorization.amount: APPROVED
      available_credit < authorization.amount: DECLINED
  expected_code:
    APPROVED: "00"
    DECLINED: "51"
coverage:
  techniques: [BOUNDARY, DECISION_TABLE, PAIRWISE]
delivery:
  systems: [CORE, CARDS, CRM, MAINFRAME_FEED]
  privacy: MASKED_OR_SYNTHETIC
  verify: true
```

The format is illustrative. The persisted model should be typed JSON, versioned, and validated against a schema.

### 4.3 Test Data Mission

A Mission is created from one of these inputs:

- select a Scenario Blueprint;
- paste a user story or test case;
- import a Cucumber feature or Examples table;
- select a Jira/ADO test-case reference when integrations are configured;
- clone a successful prior Mission;
- create from a failed production entity or bookmark.

The tester sees business choices only:

1. What behavior are you testing?
2. Which variants must be covered?
3. How many cases or what performance scale?
4. Where should the data be delivered?
5. How long should it be reserved?
6. Should the environment reset automatically?

### 4.4 Ready-to-Test Pack

Completion must not stop at "job succeeded." The delivered pack contains:

- Mission ID and immutable blueprint version;
- target environment and connection-safe access instructions;
- scenario cards with business labels;
- entity handles and cross-system identifiers;
- expected state and expected outcome for each case;
- actual verification results;
- rows and objects delivered by system;
- privacy and masking evidence;
- reservation owner and expiry;
- snapshot/bookmark;
- reset, rerun, extend, release, and rollback actions;
- lineage to source subset, synthetic seed, rules, policies, and engine runs;
- downloadable JSON, CSV, Cucumber Examples, or API fixture manifest.

## 5. Intent-to-data compiler

The compiler should be deterministic and evidence-driven. A private local language model may assist with parsing later, but it must never directly execute SQL or silently invent mappings.

### Stage 1: Interpret

Convert a story, test, or questionnaire into typed requirements:

- entities;
- attributes and conditions;
- events and temporal order;
- expected outcomes;
- quantity and coverage;
- environment and delivery;
- privacy and lifecycle.

Unknown terms are returned as explicit questions. Business terms are resolved only through approved Forge Data Store catalog entries and aliases.

### Stage 2: Resolve

Resolve requirements against:

- Test Domain;
- Business Entity members and crosswalks;
- Topology nodes, columns, and relationships;
- PII classifications and masking policies;
- reference lists and generator catalog;
- saved DataScope, Synthetic, Mapping, and Virtualization artifacts.

Every resolution records confidence, evidence, and the selected physical mapping.

### Stage 3: Plan

Choose the least risky strategy per requirement:

1. Reuse an already reserved and verified pack.
2. Rewind or clone a compatible snapshot.
3. Find and mask a production-like subset.
4. Clone and mutate an existing entity.
5. Generate synthetic data for missing, rare, negative, or volume cases.
6. Compose a hybrid pack when reference values must remain real.

The plan can combine strategies by table, entity fragment, or system.

### Stage 4: Cover

Produce a Coverage Matrix before execution:

- requirements and business rules;
- equivalence classes;
- boundary points;
- decision-table rules;
- state transitions;
- valid and invalid combinations;
- t-way combinations with forbidden-combination constraints;
- requested versus found versus generated cases.

The tester can choose:

- Minimum viable coverage;
- Functional;
- Regression;
- Risk focused;
- Exhaustive boundaries;
- Pairwise;
- Performance;
- Custom.

### Stage 5: Guard

Validate before execution:

- requester permission and target authorization;
- source and environment policy;
- PII policy completeness;
- maximum volume and concurrency;
- reservation conflicts;
- schema drift and artifact compatibility;
- destructive load behavior;
- approval need;
- rollback availability.

### Stage 6: Execute

Compile to existing engines:

- DataScope for filtered real subsets and masking;
- Synthetic Data for missing or controlled conditions;
- Business Entity for cross-application identity and capsule delivery;
- Mapping Designer for transformations and files;
- Mainframe for copybook-bound records;
- Virtualization for snapshot, refresh, rewind, and rollback;
- native loaders or JDBC according to readiness.

### Stage 7: Verify

Verification is mandatory for a green Mission:

- target row and entity counts;
- PK/FK and custom relationship integrity;
- cross-system identity consistency;
- PII/masking assertions;
- scenario predicates;
- expected state and outcome setup;
- file schema and record counts;
- no unexpected target changes;
- reservation and snapshot creation.

An execution may complete technically but remain **NOT READY** if scenario verification fails.

## 6. User experience

### 6.1 Mission Center

The self-service landing page should have:

- **Start a Mission**
- **Ready to Test**
- **Running**
- **Needs attention**
- **My reservations**
- search by application, behavior, story, defect, or business term.

Catalog cards should be scenario-oriented:

- Card decline and limit boundaries
- New retail customer onboarding
- Delinquent loan collections warning
- Claim with missing supporting document
- Payment reversal across ledger and statement

Cards must show outcomes and coverage, not engine names.

### 6.2 Four-step request flow

#### Step 1: Need

- Scenario Blueprint or pasted test intent
- expected behavior
- target environment

#### Step 2: Coverage

- generated scenario matrix
- valid, negative, boundary, state, and pairwise variants
- found gaps and recommended additions

#### Step 3: Delivery

- source strategy recommendation with explanation
- volume, reservation, expiry, reset, output format
- target impact and approval

#### Step 4: Review and run

- human-readable plan
- systems and business entities affected
- privacy handling
- estimated rows and duration
- verification contract
- launch or submit for approval

### 6.3 Expert Domain Studio

Topology should become the structural lane inside an expert workspace:

1. Systems
2. Data inventory
3. Relationships
4. Business meaning
5. States and rules
6. Privacy
7. Delivery
8. Scenario Blueprints
9. Publish

The default relationship view should be a compact, searchable statement table:

> Card Account belongs to Card Customer through `customer_id`.

The graph is an optional visual explanation, not the primary editor.

## 7. Automation

### 7.1 Test-framework contract

Each Mission should expose:

- REST launch and status endpoints;
- shell runner;
- pipeline manifest;
- Cucumber Examples export;
- webhook completion;
- machine-readable Ready-to-Test Pack;
- cleanup and rollback endpoint.

### 7.2 Pipeline behavior

```text
test case / story
    -> resolve Scenario Blueprint
    -> create Mission
    -> plan and policy check
    -> approval if required
    -> provision
    -> verify
    -> expose entity handles to tests
    -> execute tests
    -> bookmark failure or reset success
    -> release reservation
```

### 7.3 Adaptive reuse

ForgeTDM should avoid regenerating identical data automatically. A verified pack may be reused when:

- the blueprint version matches;
- source freshness is acceptable;
- schema and policy versions match;
- target compatibility is unchanged;
- the pack is unreserved or shareable;
- the requesting test allows reuse.

## 8. Current ForgeTDM fit

### Already available

- governed self-service products and questionnaires;
- maker-checker approval and RBAC;
- DataScope subset, relationship traversal, masking, provisioning, and saved jobs;
- relational synthetic generation, reference lists, partitions, and saved jobs;
- Business Entity cross-application members, identity, freshness, reservation, and Micro-DB capsules;
- Mapping Designer and file transformations;
- mainframe copybooks and generation;
- PII discovery and masking policies;
- virtualization snapshots, refresh, and rewind;
- Forge Data Store metadata grounding;
- run monitoring, cancellation, scheduling, audit, and automation runners.

### Missing product layer

- Test Domain data model and publication workflow;
- typed Scenario Blueprint and business-state rules;
- story/test-case intent parser;
- evidence-backed term and field resolver;
- coverage matrix and constrained combination generator;
- multi-engine plan compiler;
- scenario-level verifier;
- Ready-to-Test Pack;
- tester-facing entity handles and expected outcomes;
- reusable pack compatibility matching;
- topology-to-domain and domain-to-engine compilation.

## 9. Proposed persistence model

Suggested tables:

- `test_domains`
- `test_domain_versions`
- `test_domain_assets`
- `test_domain_terms`
- `test_domain_states`
- `scenario_blueprints`
- `scenario_blueprint_versions`
- `scenario_requirements`
- `scenario_coverage_rules`
- `test_data_missions`
- `mission_requirements`
- `mission_plan_steps`
- `mission_scenario_cases`
- `mission_deliveries`
- `mission_verifications`
- `ready_test_packs`
- `ready_test_pack_entities`
- `ready_test_pack_artifacts`

Every mutable definition uses immutable versions. Every execution references exact domain, blueprint, policy, mapping, and engine artifact versions.

## 10. Implementation sequence

### Phase 1: Make Test Domains real

- Implemented: publish a discovered topology version and immutable hash as a Test Domain.
- Implemented: attach governed artifacts, including the approved Self-Service execution product.
- Implemented: human-readable relationship statements and topology compatibility evidence.
- Next: richer artifact-specific certification checks for Business Entity and privacy bindings.

### Phase 2: Scenario Blueprints

- Implemented: typed preconditions, events, outcomes, coverage configuration, and safe questionnaire parameters.
- Implemented: immutable Blueprint snapshots and server-side validation.
- Implemented: focused expert authoring workspace.
- Next: curated banking example library and cardinality-specific editor controls.

### Phase 3: Mission Center

- Implemented: tester Mission request with business objective, environment, strategy, volume, safe choices, and reservation.
- Implemented: compile a selected Blueprint into an existing governed Self-Service product.
- Implemented: human-readable execution plan, maker-checker handoff, and immutable activity evidence.

### Phase 4: Coverage Composer

- Implemented: baseline, boundary, negative, state-transition, and pairwise generation.
- Implemented: deterministic de-duplication, bounded case volume, and explicit warnings/blockers.
- Next: equivalence classes, decision tables, invalid-combination constraints, and visual gap analysis.
- Next: case-specific compilation into Synthetic parameters rather than Mission-level product parameters only.

### Phase 5: Ready-to-Test Pack

- Implemented: engine completion, rejects, topology compatibility, requested volume, and coverage-retention checks.
- Implemented: scenario handles, expected outcomes, lineage, reservation metadata, and reset availability.
- Honest boundary: a Mission is `READY_WITH_WARNINGS` until its delivery engine returns target-predicate evidence.
- Next: physical entity handles from every engine and downloadable fixture manifests.

### Phase 6: Private intent automation

- deterministic parser for structured Given/When/Then and questionnaire input;
- Cucumber import;
- Forge Data Store term grounding;
- optional local-model assistance behind the same typed contract;
- no autonomous execution without plan validation.

## 11. Success criteria

The capability is successful when a tester can:

1. paste or select a business scenario without knowing a table name;
2. see which positive, negative, boundary, and state variants will be covered;
3. understand whether ForgeTDM will subset, clone, synthesize, or combine data and why;
4. receive complete cross-system data with privacy and integrity preserved;
5. obtain exact entity handles and expected outcomes;
6. prove the data satisfies the requested test conditions;
7. reserve, reset, rerun, or roll back the pack;
8. launch the same Mission from UI, API, shell, or CI/CD;
9. trace every delivered value to approved rules and engine evidence;
10. reuse the Mission without changing its governed Blueprint.

The product promise should be:

> Describe the behavior you need to test. ForgeTDM will compose, protect, deliver, and prove the exact data state required to test it.
