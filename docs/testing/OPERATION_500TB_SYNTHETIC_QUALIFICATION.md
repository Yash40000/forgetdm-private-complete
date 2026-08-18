# Operation 500 TB synthetic qualification

This qualification answers a precise question: can ForgeTDM's synthetic control plane represent and replay an
exact 500 TB banking dataset without integer overflow, partition gaps, seed drift, unbounded row retention, broken
relationship domains, receiver overruns, or ambiguous restart state?

It does not create a 500 TB local dataset or claim physical 500 TB throughput.

## Logical workload

- 5,000 DB-target tables across 1,000 banking ecosystem shards.
- CUSTOMER, ACCOUNT, CARD, TRANSACTION, and AUDIT table families in every shard.
- 100,000,000 rows per table, matching the current DB receiver's per-table planning ceiling.
- 500,000,000,000 total rows at a modeled average 1,000 bytes per row.
- Exactly 500,000,000,000,000 bytes, or 500 decimal TB.

## What is proved

- Production `SyntheticPartitioning` covers every logical row exactly once.
- Global-row seeding gives identical output for single and partitioned traversal.
- Receiver selection does not alter the deterministic sample stream.
- Dependency waves place parents before children.
- Sampled FK values remain inside the valid retained parent-key domain.
- The existing bounded parent index preserves validity but gives approximate distribution beyond 200,000 values.
- BIGINT sequence keys and the modeled multi-BIN PAN space have enough uniqueness capacity.
- An injected partition-boundary failure resumes with exact row coverage and no duplicate partition.
- A bounded receiver queue applies backpressure and drops no batch.
- Sample generation retains a fixed record buffer rather than a row collection.
- The focused banking-readiness, constraint, datatype-safety, seed-parity, multi-target, saved-job, and
  partitioning suites remain green in the same Maven run.

## Run it

```powershell
cd "D:\forgetdm - Copy"
& .\docs\testing\run-operation-500tb-synthetic.ps1
```

Use `-SampleRows 5000000` for the longest local deterministic sample. Evidence is retained under
`docs/testing/evidence/operation-500tb-synthetic/<timestamp>/`, with `latest-report.md` and
`latest-report.json` pointers.

## Claim boundary

The expected verdict is `SYNTHETIC_LOGICAL_SCALE_PASS_PHYSICAL_CERTIFICATION_PENDING`. A physical claim requires
the staged 1%, 10%, 1 TB, 5 TB, and 500 TB gate on production-equivalent targets. That execution must use the
intended schemas, LOB mix, generator catalog, relationships, CHECK constraints, target preparation, native loaders,
cancellation, retry, restart, reconciliation, telemetry, concurrency, and endurance profile.
