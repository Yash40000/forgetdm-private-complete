# Operation 500 TB - Rolling Physical Qualification Report

**Verdict: PASS WITH QUALIFICATION**  
**Execution date:** August 4, 2026  
**Engines:** PostgreSQL 17 COPY and Oracle 11g SQL\*Loader direct-path recoverable  
**Evidence model:** Nine durable 10-minute checkpoints across restart-interrupted segments

## Executive summary

| KPI | Result |
| --- | ---: |
| Reconciled checkpoints | 9 / 9 |
| Rows processed per engine | 36,700,000 |
| Physical row loads across both engines | 73,400,000 |
| Generated staging data | 2.60 GiB (2,793,574,593 bytes) |
| Weighted combined throughput | 13,365 rows/s |
| Window throughput range | 7,572 - 18,081 rows/s |
| Peak JVM heap | 132.7 MiB |
| Data/test failures | 0 |
| Exact count, ID-sum and amount-sum reconciliation | PASS - every checkpoint |

## What this test proves

1. **Physical native loading works repeatedly on real engines.** Identical banking-shaped batches were loaded through PostgreSQL COPY and Oracle SQL\*Loader direct-path recoverable mode, not mocked JDBC inserts.
2. **Integrity survives every rolling cycle.** At each 10-minute checkpoint, both targets matched the expected row count, primary-key sum and financial amount sum exactly before storage was recycled.
3. **Processing is streaming and memory-bounded.** 36,700,000 cumulative rows per engine were handled while peak JVM heap remained 132.7 MiB; heap did not grow in proportion to cumulative volume.
4. **The rolling architecture can reuse bounded storage.** Every verified window was truncated only after reconciliation, allowing repeated high-volume cycles without requiring 500 TB of local disk.
5. **Evidence survives interruption.** Completed checkpoint reports remained readable after the Codex terminal lifetime ended and after the workstation restarted.
6. **The measured local baseline is stable enough for engineering decisions.** Nine checkpoints completed with zero data/test failures at 13,365 weighted combined rows/s.

## Qualification boundary

This is **rolling physical-load and integrity evidence**, not a claim that 500 TB was simultaneously stored or processed on this workstation. It qualifies the chunked rolling architecture and both native-loader paths at local scale. Production 500 TB certification still requires representative infrastructure, network, source concurrency, target sizing and a 24-72 hour uninterrupted soak. The workstation restart proves durable evidence retention and operator recovery; it does **not** by itself prove automatic transaction-level resume from an interrupted batch.

## Execution continuity

| Segment | Intended | Completed evidence | Outcome |
| --- | ---: | ---: | --- |
| Initial endurance | 90 min | 3 checkpoints / about 30 min | Terminal host ended; all completed checkpoints retained |
| Detached continuation | 60 min | 5 checkpoints / about 50 min | Workstation restarted; all completed checkpoints retained |
| Final continuation | 10 min | 1 checkpoint / about 10 min | Maven/JUnit build success |
| **Combined** | **90 min target** | **9 reconciled checkpoints** | **PASS WITH QUALIFICATION** |

## Per-checkpoint statistics

| Checkpoint | Segment | Segment elapsed min | Rows/engine | Loads both | Rows/s both | PostgreSQL s | Oracle s | Peak heap MiB | Status |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 1 | 10.10 | 4,100,000 | 8,200,000 | 13,528 | 122.2 | 444.6 | 71.8 | PASS |
| 2 | 1 | 20.19 | 3,900,000 | 7,800,000 | 12,886 | 130.0 | 418.1 | 132.7 | PASS |
| 3 | 1 | 30.32 | 2,300,000 | 4,600,000 | 7,572 | 144.0 | 418.9 | 132.7 | PASS |
| 4 | 2 | 10.12 | 4,400,000 | 8,800,000 | 14,488 | 80.6 | 503.8 | 64.3 | PASS |
| 5 | 2 | 20.36 | 5,100,000 | 10,200,000 | 16,599 | 109.1 | 462.1 | 87.3 | PASS |
| 6 | 2 | 30.57 | 5,100,000 | 10,200,000 | 16,655 | 123.1 | 456.7 | 89.3 | PASS |
| 7 | 2 | 40.85 | 3,500,000 | 7,000,000 | 11,356 | 119.5 | 461.4 | 89.4 | PASS |
| 8 | 2 | 51.07 | 2,800,000 | 5,600,000 | 9,125 | 140.2 | 423.4 | 89.4 | PASS |
| 9 | 3 | 10.14 | 5,500,000 | 11,000,000 | 18,081 | 117.3 | 445.5 | 78.4 | PASS |

## Cycle timing

| Activity | Cumulative time | Interpretation |
| --- | ---: | --- |
| Banking batch generation | 233.3 s | TSV staging production |
| PostgreSQL native COPY | 1,086.0 s | Real PostgreSQL physical load |
| Oracle SQL\*Loader | 4,034.7 s | Real Oracle direct recoverable load |
| Reconciliation | 122.6 s | Count and aggregate proof on both engines |
| Verified storage recycle | 24.0 s | Truncate after proof only |

## Smoke baseline

Before endurance execution, a separate one-minute smoke test loaded 120,000 rows per engine, reconciled exactly, used 51.8 MiB peak heap and completed with status **PASS**. Smoke results are excluded from endurance totals.

## Evidence

- Initial segment: 20260804-121246
- Restart continuation: 20260804-131328
- Final continuation: 20260804-142404
- Machine-readable merged report: OPERATION_500TB_FINAL_REPORT.json

## Recommended next gate

Run a production-like distributed qualification with native clients close to each target, representative row widths and LOBs, concurrent tables, injected loader/network failures, automatic checkpoint resume, and a 24-72 hour uninterrupted soak. Treat observed local throughput as a workstation baseline, not a 500 TB completion-time forecast.
