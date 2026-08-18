# Operation 500 TB synthetic qualification evidence

**Verdict:** SYNTHETIC LOGICAL-SCALE PASS; PHYSICAL CERTIFICATION PENDING

This run uses ForgeTDM's production synthetic partition planner and global-row seed convention.
It proves exact scale planning, deterministic replay, bounded sample streaming, dependency order,
relationship validity, uniqueness capacity, bounded receiver backpressure, and partition restart.
It does **not** claim that 500 TB was physically generated or loaded on this workstation.

## Logical banking dataset

| Measure | Result |
| --- | ---: |
| Exact planned bytes | 500000000000000 |
| Logical volume | 500 TB (decimal) |
| Tables | 5,000 |
| Ecosystem shards | 1,000 |
| Families per shard | CUSTOMER, ACCOUNT, CARD, TRANSACTION, AUDIT |
| Rows per table | 100,000,000 |
| Total logical rows | 500000000000 |
| Average modeled row width | 1,000 bytes |

## Proofs

| Proof | Result |
| --- | --- |
| Production row-range coverage | 160,000 partitions; 500000000000 rows exactly once; PASS |
| Manifest digest | `ef679571c79c85b8c9dc525757a44af74a29e8f02086a73535f0868c2b164bd6` |
| Single vs partitioned replay | 1,000,000 sampled rows; identical digest; PASS |
| Receiver seed parity | Same sampled digest for receiver-independent generation; PASS |
| Relationship checks | 3,000,000 sampled FK checks in valid parent domain; PASS |
| Parent linkage distribution | Approximate beyond the 200,000-value bounded parent index |
| Primary-key capacity | BIGINT sequence within each 100M-row table; PASS |
| PAN uniqueness capacity | 100000000000 card rows; 1000000000000 available values across 1,000 BINs; PASS |
| Injected restart | 80,000 committed, 80,000 replayed, zero duplicate partitions; PASS |
| Restart row coverage | 500000000000 rows |
| Bounded receiver queue | capacity 8; max depth 8; 9,990 blocked/retried offers; zero drops; PASS |
| Per-record retained buffer | 48 bytes |

## Claim boundary

This is an engineering qualification of the generation control plane and deterministic core.
Physical support remains pending until staged 1%, 10%, 1 TB, 5 TB, and 500 TB executions run
against production-equivalent database/file targets with the intended schema, LOB mix, CHECK/FK
constraints, generator catalog, native loader, target preparation, cancellation, retry, telemetry,
reconciliation, and endurance conditions.
