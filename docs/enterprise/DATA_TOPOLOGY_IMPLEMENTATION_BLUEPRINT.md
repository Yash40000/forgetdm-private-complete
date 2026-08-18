# ForgeTDM Data Topology Implementation Blueprint

Document owner: Product Engineering
Status: Active implementation blueprint
Applies to: ForgeTDM enterprise data discovery, synthetic generation, subsetting, masking, and Business Entity workflows

## 0. Implementation status

The first production topology slice is implemented and available in the new UI at `/topology`.

Implemented:

- tenant-aware topology catalog with private, group, and global visibility;
- source-schema attachment with source capability and physical-schema validation;
- persisted asynchronous discovery operations with progress, restart recovery, cancellation, and failure evidence;
- schema-wide table and column capture plus bulk PK/FK capture for PostgreSQL/H2, Oracle, SQL Server, MySQL, and Db2;
- explicit JDBC metadata fallback for unknown dialects, recorded as fallback evidence rather than native certification;
- immutable operation-scoped graph snapshots so a failed refresh cannot replace the last successful topology;
- relationship review decisions (`VERIFIED`, `REJECTED`, and `DISABLED`);
- canonical graph hashes that exclude volatile capture facts such as timestamps and row estimates;
- immutable topology versions;
- object-level and underlying data-source visibility enforcement;
- a compact topology catalog and focused full-screen Sources, Discovery, Relationships, and Versions workflow;
- a user-triggered **Load example** action that creates a private five-table Customer 360 banking schema with real PK/FK metadata and runs the same production discovery path;
- RBAC coverage for topology read, create, manage, approve, publish, and delete operations.

The example is deliberately opt-in. ForgeTDM never inserts sample topology assets into a user's catalog during startup.

Not yet implemented:

- authoring new custom relationships or inferred relationships in the topology workspace;
- rename heuristics and cross-system identity inference;
- files, copybooks, and mainframe records as topology nodes;
- topology publication compilers for DataScope, Synthetic Data, Business Entity, and Mapping Designer;
- preview/diff and round-trip contracts for published downstream assets;
- approval and publish workflows beyond their reserved RBAC permissions.

These remain target-state commitments in the sections below and must not be represented as delivered functionality.

## 1. Executive summary

ForgeTDM Data Topology will discover, explain, and govern how data structures are connected across databases, files, mainframe assets, and applications.

It will turn technical metadata into reusable topology models such as:

- Customer 360
- Credit Card Servicing
- Commercial Lending
- Payments and Settlement
- Claims Processing
- Employee and Payroll

A topology model is not a copy of source data. It is a governed metadata graph containing nodes, relationships, key definitions, confidence evidence, scope rules, and publication history.

The target user outcome is:

> Select one or more systems, discover their connected data structures, review and approve the relationships, define the domain under test, generate test scenarios, and publish the result directly into DataScope, Synthetic Data, or Business Entity without rebuilding the model manually.

Data Topology will use capabilities ForgeTDM already has:

- connector metadata and schema browsing;
- database and tool-defined PK/FK relationships;
- DataScope table profiles and relationship traversal;
- Synthetic Data relational generation and reusable jobs;
- Business Entity cross-application models and identity crosswalks;
- Mapping Designer file and database metadata;
- PII classification, masking-policy recommendations, governance, versions, and audit.

The primary new value is the orchestration layer that automatically discovers connected structures and converts them into governed, executable designs.

## 2. Product position

Data Topology has a distinct responsibility:

| ForgeTDM capability | Responsibility |
| --- | --- |
| Data Sources | Connect to and inspect systems |
| PII Discovery | Classify sensitive columns |
| Data Topology | Discover and govern connected data structures |
| DataScope | Subset, map, mask, and provision existing data |
| Synthetic Data | Generate new relational data |
| Mapping Designer | Transform and move data between structures |
| Business Entity | Govern reusable cross-application business objects |
| Forge Data Store | Ground automation with governed metadata and evidence |

Data Topology must not duplicate the detailed editors in those products. It discovers and publishes reusable starting points into them.

## 3. Why this capability is needed

Enterprise schemas frequently contain:

- thousands of tables;
- missing or incomplete foreign keys;
- duplicate technical relationships;
- composite and circular keys;
- tables shared by several business domains;
- multiple applications representing the same customer differently;
- files and mainframe records that have no database constraints;
- legacy names that do not explain business meaning;
- sensitive fields that require different privacy treatment;
- schema drift between development, test, and production.

Today, a ForgeTDM user can model these structures, but must combine several screens and make many decisions manually. Data Topology will create one controlled path from metadata discovery to executable test-data assets.

## 4. Primary users

### Test Data Engineer

Discovers relationships, corrects metadata, and publishes reusable DataScope and Synthetic designs.

### QA Engineer

Selects a topology and requests a scenario pack without understanding every physical table.

### Data Architect

Reviews keys, relationships, ownership, cardinality, and cross-system boundaries.

### Data Steward

Approves business names, PII classifications, relationship evidence, and permitted use.

### Platform Administrator

Controls source access, discovery limits, publication permissions, retention, and audit.

## 5. Core concepts

### 5.1 Topology catalog

The searchable inventory of topology models visible to the current user or group.

Each catalog row shows:

- topology name and domain;
- owner and status;
- systems and schemas included;
- node and relationship counts;
- unresolved relationship suggestions;
- sensitive-data summary;
- drift status;
- published assets;
- latest approved version.

### 5.2 Topology model

A versioned graph of nodes and edges with an explicitly selected business scope.

### 5.3 Node

A physical or logical data structure:

- database table or view;
- CSV, delimited, fixed-width, JSON, XML, Parquet, or similar file structure;
- mainframe copybook record;
- API resource or message schema in a later connector lane;
- Business Entity or approved logical object.

### 5.4 Edge

A relationship between two nodes. Examples:

- database foreign key;
- ForgeTDM custom relationship;
- copybook or file key relationship;
- mapping lineage;
- Business Entity identity crosswalk;
- inferred relationship suggestion.

### 5.5 Domain under test

The approved portion of a topology needed for a test objective. It contains an anchor or root, included nodes, excluded nodes, traversal rules, and volume behavior.

### 5.6 Scenario pack

A reusable collection of test-data scenarios generated from a topology:

- baseline valid data;
- minimum and maximum cardinality;
- parent with no optional children;
- parent with many children;
- boundary values;
- negative and constraint-violation candidates;
- null and optional-value combinations;
- volume and performance profiles;
- sensitive-data policy expectations;
- cross-system identity consistency.

Scenario packs do not execute directly. They compile into reviewed Synthetic, DataScope, or Business Entity plans.

## 6. End-to-end user workflow

### Step 1: Create topology

The user selects **New topology** and provides:

- name, 8 to 120 characters;
- business domain;
- description;
- owner or group;
- intended use;
- one or more metadata sources.

The UI opens a focused full-screen workspace.

### Step 2: Select metadata sources

Supported target-state inputs:

1. Live database catalogs through ForgeTDM Data Sources.
2. DDL files.
3. JSON Schema.
4. XSD.
5. CSV or delimited headers with optional sample profiling.
6. Copybooks and mainframe record layouts.
7. Existing ForgeTDM DataScope blueprints.
8. Existing Business Entity models.
9. Existing Mapping Designer assets.

The first implementation should prioritize live JDBC metadata, DataScope, Business Entity, and copybooks because ForgeTDM already owns those metadata paths.

### Step 3: Discover structure

ForgeTDM captures a metadata snapshot and normalizes:

- catalog, schema, table, and column names;
- physical types, length, precision, scale, and nullability;
- primary, unique, and foreign keys;
- indexes and database constraints;
- table and column comments;
- estimated or sampled row counts where allowed;
- PII classifications;
- existing ForgeTDM custom keys and relationships;
- mapping lineage and cross-application identity evidence.

No production row values are persisted in the topology catalog.

### Step 4: Build relationship graph

Relationships are collected from the following sources:

| Source | Initial status | Default confidence |
| --- | --- | --- |
| Database PK/FK catalog | Verified | 100 |
| Approved ForgeTDM custom relationship | Verified | 100 |
| Business Entity member relationship | Verified | 100 |
| Mapping Designer lineage | Verified or reviewed | 90 |
| Matching unique/key metadata | Suggested | 85 |
| Matching names and compatible types | Suggested | 65 |
| Profiled value overlap, when explicitly enabled | Suggested | Calculated |

Suggested relationships never become executable merely because an algorithm found them. A user with topology-management permission must approve them.

### Step 5: Discover topology candidates

ForgeTDM groups nodes using graph connectivity and business boundaries.

The discovery engine will:

1. Build connected components from verified edges.
2. Identify high-connectivity hub nodes.
3. propose likely roots based on PK usage, inbound/outbound relationships, naming, and Business Entity evidence.
4. Detect disconnected islands.
5. Detect cycles and composite relationships.
6. Detect bridge tables and many-to-many structures.
7. Flag oversized components that should be split by business scope.
8. Suggest domain names from metadata, glossary, existing assets, and Forge Data Store evidence.

The result is one or more candidate topology models. Users can merge, split, rename, include, exclude, or defer them.

### Step 6: Review relationship map

The full-screen relationship map provides:

- zoom, pan, fit, search, and auto-layout;
- grouping by source, schema, application, or business domain;
- node coloring by source system;
- sensitive-data indicators;
- root and driver indicators;
- edge labels with parent and child columns;
- relationship source and confidence;
- composite-key detail;
- cycle and ambiguity warnings;
- expandable node metadata;
- filters for verified, suggested, rejected, and stale edges.

Selecting an edge opens a side panel with:

- source and evidence;
- parent and child columns;
- type compatibility;
- key and uniqueness evidence;
- cardinality;
- confidence explanation;
- approve, reject, edit, or replace actions;
- impact of changing or disabling the relationship.

### Step 7: Define domain under test

The user selects an anchor node and chooses what the test requires:

- include required parents;
- include dependent children;
- define per-edge traversal;
- cap depth;
- cap rows or cardinality per node;
- exclude operational, audit, archive, or reference nodes;
- retain shared reference nodes;
- choose cross-application identity paths;
- set sensitive-data handling expectations.

The UI displays an English extraction explanation:

> Start with active customers. Include each selected customer's required branch and identity records. Include all accounts and up to five cards per account. Exclude archived statements. Resolve CRM party identity through the approved customer crosswalk.

### Step 8: Configure volume and variation

Users configure default volume at topology level and override it per node or edge:

- number of root instances;
- children per parent: fixed, range, weighted, distribution-based, or learned;
- optional relationship percentage;
- uniqueness and null percentage;
- valid, boundary, or intentionally invalid variation;
- temporal ranges;
- partitioning and worker strategy;
- deterministic seed.

### Step 9: Generate scenario pack

ForgeTDM proposes scenarios from structure and constraints.

For every proposed scenario it records:

- objective;
- included nodes and relationships;
- expected volume;
- expected constraint outcome;
- privacy requirement;
- execution destination;
- validation assertions;
- unresolved decisions.

The user can edit, disable, duplicate, or add scenarios before publication.

### Step 10: Validate

Validation must block publication when:

- an included node has no usable source metadata;
- an executable suggested relationship remains unapproved;
- a required key is missing;
- composite key columns are incomplete or incompatible;
- an included cycle has no traversal strategy;
- a selected target system is unavailable;
- PII exists without an approved policy or explicit governed unmasked exception;
- the model references stale or deleted metadata;
- scenario volumes violate configured safety limits.

Warnings are allowed only when they cannot cause incorrect data or referential-integrity failures.

### Step 11: Publish

One topology version can publish into:

#### DataScope blueprint

Creates:

- included table profiles;
- source context per table;
- driver/root selection;
- DB and tool relationships;
- Q1/Q2 and per-edge traversal;
- custom PK definitions;
- row caps and filters;
- masking-policy recommendations.

#### Synthetic design

Creates:

- tables, columns, types, and constraints;
- PK/FK relationships;
- default generators;
- cardinality and distribution settings;
- row volumes and deterministic seed;
- target output placeholders;
- scenario-specific validation rules.

#### Business Entity

Creates:

- root member and business-key proposal;
- application blueprint attachments;
- member tables;
- relationship evidence;
- identity-crosswalk candidates;
- freshness candidates;
- domain and catalog metadata.

#### Mapping Designer

Creates a starter map when topology nodes cross incompatible physical structures or files.

Publication creates new draft objects. It must never overwrite an approved downstream object silently.

## 7. Cross-system behavior

Data Topology must be broader than a single-schema relationship browser.

Cross-system edges can come from:

- Business Entity identity crosswalks;
- approved mapping lineage;
- user-defined relationships;
- shared deterministic masking keys;
- file-to-table key declarations;
- copybook-to-database key declarations;
- approved metadata inference.

Each cross-system edge must show:

- source and target application;
- logical business key;
- physical key representation on each side;
- normalization or transformation;
- ownership;
- freshness;
- validation status.

Cross-system inferred relationships have no executable status until explicitly approved.

## 8. Relationship selection and precedence

When several relationships exist between the same nodes, the user chooses one:

1. Explicit user selection.
2. Approved ForgeTDM custom relationship.
3. Database FK.
4. Business Entity relationship.
5. Approved inferred relationship.
6. None.

The selected relationship and traversal direction are versioned.

Selecting **None** must remove the edge from execution without deleting the source metadata.

## 9. Metadata drift

Every topology version is bound to one or more metadata snapshots.

Refresh compares the latest source metadata against the approved topology and reports:

- added, removed, and renamed nodes;
- added, removed, and changed columns;
- key and relationship changes;
- type, length, precision, and nullability changes;
- PII classification changes;
- affected scenario packs;
- affected published assets.

Drift does not automatically mutate an approved topology. The user reviews a proposed version and publishes updates deliberately.

## 10. Governance and security

### Permissions

Proposed permission family:

- `topology.read`
- `topology.create`
- `topology.manage`
- `topology.approve`
- `topology.publish`
- `topology.delete`
- `topology.admin`

### Maker-checker

Approval should be configurable for:

- inferred relationship approval;
- topology version release;
- sensitive-data exception;
- scenario pack release;
- production metadata access;
- publication into governed Business Entities or shared jobs.

The creator cannot approve their own high-risk topology release unless an explicit administrator bypass policy permits it and records the bypass.

### Audit

Audit events include:

- topology created, changed, submitted, approved, rejected, retired, or deleted;
- metadata source attached or removed;
- relationship approved, edited, rejected, or disabled;
- topology split or merged;
- scenario pack generated or changed;
- drift reviewed;
- downstream asset published;
- publication failed or rolled back.

### Data minimization

The topology store persists metadata and summarized evidence only. Sample values must not be retained. Optional value-overlap analysis should run through bounded, in-memory sketches or salted fingerprints and persist only summary evidence.

## 11. Proposed persistence model

The physical implementation may refine names, but the logical model requires:

### `topology_models`

- id
- tenant and ownership fields
- name, domain, description
- status: DRAFT, IN_REVIEW, APPROVED, RETIRED
- current_version
- created_by, created_at, updated_at

### `topology_versions`

- topology_id and version number
- immutable content hash
- metadata snapshot references
- release status and approval evidence
- change summary
- created_by and created_at

### `topology_sources`

- topology version
- source type
- ForgeTDM source/object reference
- application label
- schema or structure scope
- metadata hash and captured time

### `topology_nodes`

- stable logical node key
- physical source reference
- catalog, schema, and object name
- node type
- business label
- key evidence
- PII summary
- row-count estimate
- status and metadata hash

### `topology_columns`

- node reference
- name, business label
- type, length, precision, scale
- nullable, PK, unique, generated
- PII classification
- profile summary

### `topology_edges`

- stable edge key
- parent and child nodes
- parent and child column arrays
- relationship type and source
- status: VERIFIED, SUGGESTED, APPROVED, REJECTED, DISABLED, STALE
- confidence and evidence JSON
- cardinality
- selected-for-execution flag

### `topology_scopes`

- domain-under-test definition
- anchor node
- included and excluded node keys
- traversal depth and behavior
- per-node and per-edge overrides

### `topology_scenario_packs`

- name, objective, version, status
- topology version and scope
- scenario definitions
- validation assertions
- publication references

### `topology_publications`

- topology and scenario version
- target product and object id
- publication status
- target version/hash
- actor, time, and audit reference

## 12. Proposed service architecture

### TopologyMetadataService

Captures and normalizes metadata from supported source adapters.

### TopologyGraphService

Builds nodes, edges, connected components, cycle evidence, and candidate roots.

### TopologyInferenceService

Produces relationship and domain suggestions with explainable confidence evidence.

### TopologyModelService

Owns CRUD, versioning, split/merge, lifecycle, tenancy, and validation.

### TopologyScenarioService

Generates and validates scenario packs.

### TopologyPublicationService

Compiles topology versions into DataScope, Synthetic, Business Entity, and Mapping Designer drafts.

### TopologyDriftService

Refreshes metadata and produces non-destructive diffs.

### TopologyGovernanceService

Owns approvals, release evidence, audit, and retention.

## 13. Proposed REST API

### Catalog and lifecycle

```text
GET    /api/topologies
POST   /api/topologies
GET    /api/topologies/{id}
PUT    /api/topologies/{id}
DELETE /api/topologies/{id}
POST   /api/topologies/{id}/submit
POST   /api/topologies/{id}/approve
POST   /api/topologies/{id}/reject
POST   /api/topologies/{id}/retire
```

### Discovery and sources

```text
GET    /api/topologies/source-options
POST   /api/topologies/{id}/sources
DELETE /api/topologies/{id}/sources/{sourceId}
POST   /api/topologies/{id}/discover
GET    /api/topologies/{id}/discovery-status
GET    /api/topologies/{id}/candidates
POST   /api/topologies/{id}/candidates/{candidateId}/accept
```

### Graph

```text
GET    /api/topologies/{id}/graph
GET    /api/topologies/{id}/nodes
GET    /api/topologies/{id}/edges
PUT    /api/topologies/{id}/edges/{edgeId}
POST   /api/topologies/{id}/edges/{edgeId}/approve
POST   /api/topologies/{id}/edges/{edgeId}/reject
POST   /api/topologies/{id}/edges/{edgeId}/disable
POST   /api/topologies/{id}/split
POST   /api/topologies/{id}/merge
```

### Scope and scenarios

```text
GET    /api/topologies/{id}/scopes
POST   /api/topologies/{id}/scopes
PUT    /api/topologies/{id}/scopes/{scopeId}
POST   /api/topologies/{id}/scopes/{scopeId}/explain
POST   /api/topologies/{id}/scopes/{scopeId}/scenario-packs
GET    /api/topologies/{id}/scenario-packs
PUT    /api/topologies/{id}/scenario-packs/{packId}
POST   /api/topologies/{id}/scenario-packs/{packId}/validate
```

### Drift and publication

```text
POST   /api/topologies/{id}/refresh
GET    /api/topologies/{id}/drift
POST   /api/topologies/{id}/drift/accept
POST   /api/topologies/{id}/publish/datascope
POST   /api/topologies/{id}/publish/synthetic
POST   /api/topologies/{id}/publish/business-entity
POST   /api/topologies/{id}/publish/mapping
GET    /api/topologies/{id}/publications
```

All write endpoints enforce object ownership, tenant boundaries, permissions, optimistic locking, and audit.

## 14. UI blueprint

### Main page

The main page stays compact:

- dynamic Data Topology header;
- **New topology** action;
- **Import structure** action;
- search and filters;
- topology inventory table;
- small status summaries, not dashboard-sized cards.

Inventory columns:

- topology;
- domain;
- systems;
- nodes and edges;
- relationship review;
- drift;
- version/status;
- owner;
- actions.

### Full-screen topology workspace

Persistent header:

- topology name and status;
- source count;
- unresolved suggestion count;
- drift state;
- save, validate, publish, and close.

Workflow:

1. Sources
2. Discover
3. Relationship Map
4. Domain Scope
5. Scenario Pack
6. Validate and Publish

Only the active step occupies the workspace. Supporting forms open in side panels rather than expanding the main page.

### Responsive behavior

Desktop is the primary design surface for graph editing. Smaller screens provide catalog review, approval, and status visibility; they are not required to support complex drag-and-drop graph editing.

## 15. Integration with existing ForgeTDM code

### Reuse

- `DataSetService.getRelationships()` for DB and tool-defined relationship evidence.
- custom PK, user relationship, and traversal entities from DataScope.
- connector metadata access and dialect handling.
- PII classification and policy recommendations from Discovery.
- Synthetic table profiling, generators, constraints, saved jobs, and plan summary.
- Business Entity members, application blueprints, crosswalks, and catalog.
- Mapping Designer source/file schema and lineage.
- existing ownership, RBAC, maker-checker, audit, versioning, and job infrastructure.

### Refactor boundary

Shared relationship metadata should move behind a reusable metadata graph interface rather than allowing Topology to call UI-oriented DataScope methods indefinitely.

Proposed interface:

```java
public interface MetadataGraphProvider {
    MetadataSnapshot capture(MetadataScope scope);
    List<GraphNode> nodes(MetadataSnapshot snapshot);
    List<GraphEdge> edges(MetadataSnapshot snapshot);
}
```

Provider implementations can cover JDBC catalogs, DataScope custom metadata, Business Entity, Mapping Designer, copybooks, and future APIs.

## 16. Delivery sequence

The sequence is implementation order, not reduced target scope.

### Release 1: Core topology

- persistence, tenancy, permissions, audit, and versions;
- live JDBC metadata snapshots;
- DB FK and DataScope custom relationship ingestion;
- connected-component discovery;
- topology catalog and full-screen map;
- relationship approval;
- domain-under-test scope;
- publish to DataScope.

### Release 2: Synthetic scenario packs

- volume and cardinality configuration;
- constraint-aware scenario generation;
- validation assertions;
- publish to Synthetic Data;
- saved scenario packs;
- topology-to-job lineage.

### Release 3: Cross-system and files

- Business Entity and identity-crosswalk edges;
- Mapping Designer lineage;
- copybook and file nodes;
- DDL, JSON Schema, and XSD imports;
- cross-system topology maps;
- publish to Business Entity and Mapping Designer.

### Release 4: Relationship intelligence and drift

- explainable inferred relationships;
- optional privacy-preserving value-overlap evidence;
- domain/root suggestions;
- schema drift;
- impact analysis;
- review and promotion workflows.

## 17. Acceptance criteria

### Discovery

1. A user can select one or more permitted metadata sources.
2. A 4,000-table schema can be discovered asynchronously without blocking the UI.
3. Discovery progress reports the current source, schema, object, and percentage.
4. Cancelling discovery leaves no partially approved topology.
5. No source row values are stored in topology tables.

### Graph correctness

1. Every imported DB FK includes exact parent/child columns.
2. Composite keys preserve column order.
3. Tool-defined relationships are distinguishable from DB relationships.
4. Cycles and disconnected nodes are visible.
5. Duplicate relationship evidence is grouped without losing provenance.
6. Suggested relationships cannot be published until approved.

### User control

1. Users can split, merge, include, exclude, and rename candidates.
2. Users can select one relationship when alternatives exist.
3. Users can select **None** without deleting metadata.
4. Users can define an anchor and per-edge traversal.
5. The system explains the resulting extraction flow in plain language.

### Scenario packs

1. A topology can generate baseline, boundary, cardinality, and negative scenarios.
2. Every scenario records expected constraints and row volumes.
3. The user can edit or disable generated scenarios.
4. Unsafe or contradictory scenarios fail validation before publication.

### Publication

1. Publishing to DataScope creates correct profiles, keys, relationships, and traversal rules.
2. Publishing to Synthetic creates correct tables, PK/FK relationships, and cardinality defaults.
3. Publishing to Business Entity creates a draft root/member model without bypassing review.
4. Publication never silently overwrites an approved downstream asset.
5. Every publication records source and target version hashes.

### Governance

1. Tenant and object ownership are enforced on every endpoint.
2. Approval separation is enforced where configured.
3. Every relationship decision and publication is audited.
4. Approved versions are immutable.
5. Delete warns about downstream dependencies and preserves required audit evidence.

### Drift

1. Refresh identifies table, column, key, relationship, type, and PII changes.
2. Drift shows affected scenarios and published assets.
3. Refresh never silently changes an approved version.

## 18. Testing strategy

### Unit tests

- graph grouping and connected components;
- cycle and bridge detection;
- composite relationship normalization;
- confidence calculations;
- candidate split/merge;
- scope validation;
- scenario generation;
- publication compilers;
- drift diffing;
- permission and lifecycle rules.

### Integration tests

- PostgreSQL, Oracle, SQL Server, DB2, and MySQL metadata adapters;
- large synthetic schemas;
- missing FK and custom relationship cases;
- quoted and mixed-case identifiers;
- cross-schema and cross-source models;
- composite and circular relationships;
- PII and policy propagation;
- publish/reload round trips.

### UI tests

- create topology;
- browse multiple sources;
- run, cancel, and resume discovery;
- approve/reject relationship;
- graph search and filtering;
- domain scope editing;
- scenario review;
- validation and publication;
- drift review;
- responsive catalog and approval views.

### Scale tests

Minimum initial scale targets:

- 10,000 nodes;
- 50,000 edges;
- 100 connected candidate domains;
- graph API pagination and filtered loading;
- discovery restart after failure;
- deterministic graph hash for unchanged metadata.

The visual map should load only the selected domain or filtered subgraph, not render all 10,000 nodes at once.

## 19. Example outcome: Customer 360

### Inputs

- DB2 Core Banking: customer, deposit, loan, and branch tables.
- Oracle Cards: card customer, card account, authorization, payment, and dispute tables.
- PostgreSQL CRM: party, address, phone, email, and consent tables.
- Mainframe copybook: customer transaction history.

### Discovered model

```text
Customer 360 Topology
  Root business concept: Customer
  Systems: 4
  Nodes: 31
  Verified relationships: 27
  Suggested relationships: 6
  Approved cross-system identities: 3
  Sensitive columns: 42
```

### Approved scope

- 10 active customers;
- all required identity and branch parents;
- 1 to 4 deposit accounts per customer;
- 0 to 2 loan accounts;
- 0 to 5 cards;
- up to 100 recent transactions per account;
- CRM contact and consent records;
- matching copybook transaction records;
- deterministic privacy policies across shared identities.

### Generated scenario pack

1. Active customer baseline.
2. Customer with no optional card.
3. Customer with maximum configured accounts.
4. Delinquent loan with valid customer and payment history.
5. Card dispute with authorization and posting chain.
6. Customer with multiple addresses and one preferred address.
7. Cross-application identity consistency.
8. Boundary transaction amount and date.
9. Invalid candidate missing a required parent, expected to be rejected.
10. Performance scenario for 100,000 customers.

### Published assets

- Customer 360 DataScope blueprint;
- Customer 360 synthetic saved job;
- Customer 360 Business Entity draft;
- mapping starter for mainframe transaction records;
- immutable topology and scenario lineage.

## 20. Expected business and engineering outcomes

### User outcomes

- Users start from a business domain instead of manually hunting through schemas.
- Relationships are visible and explainable before data moves.
- Test scenarios are reusable rather than recreated for every run.
- Cross-application models become practical for non-specialists.
- DataScope and Synthetic setup time is substantially reduced.

### Quality outcomes

- Fewer orphaned rows and incorrect relationship assumptions.
- Earlier detection of missing, ambiguous, and stale metadata.
- Consistent PK/FK and cardinality behavior between synthetic and subset workflows.
- Better negative, edge, and volume test coverage.
- Traceable relationship decisions suitable for banking governance.

### Platform outcomes

- One normalized metadata graph reused across ForgeTDM.
- Less duplicated relationship logic between DataScope, Synthetic, Business Entity, and Mapping Designer.
- A stronger Forge Data Store grounding source for Story-to-Data automation.
- Clear lineage from discovered metadata to executed jobs.

## 21. Success measures

The first production-ready release should demonstrate:

- at least 80 percent reduction in manual relationship setup for a representative schema;
- topology discovery and first usable map within 10 minutes for 4,000 tables;
- 100 percent provenance for every executable relationship;
- zero unapproved inferred relationships entering a run;
- successful topology publication into DataScope and Synthetic without manual relationship recreation;
- deterministic repeat discovery when source metadata is unchanged;
- complete audit linkage from metadata snapshot to topology version, scenario pack, published asset, and run.

## 22. Final target state

ForgeTDM Data Topology becomes the relationship-intelligence layer of the platform.

The user no longer begins by choosing isolated tables. The user begins with a connected domain, understands why each structure belongs, chooses the exact test scope, and publishes a safe executable design.

The completed capability should answer five questions before any data operation starts:

1. What data structures belong to this business domain?
2. How are they connected, and what evidence proves each connection?
3. Which structures and relationships are required for this test?
4. What data variations and volumes must be created or provisioned?
5. Which governed ForgeTDM assets and runs were produced from this model?

That outcome is broader than a schema-family feature: it joins relationship discovery, test design, privacy, cross-system identity, publication, drift, and governance into one ForgeTDM-native workflow.
