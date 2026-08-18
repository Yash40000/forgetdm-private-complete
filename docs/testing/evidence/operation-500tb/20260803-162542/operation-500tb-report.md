# Operation 500 TB qualification evidence

**Verdict:** LOGICAL CONTROL-PLANE PASS; PHYSICAL CERTIFICATION PENDING

This run validates exact 500 TB planning, sparse-manifest traversal, bounded working memory,
checkpoint capacity, and deterministic restart. It does **not** claim that 500 TB was physically
read, masked, or loaded on this workstation.

## Logical plan

| Measure | Result |
| --- | ---: |
| Requested volume | 500 TB (decimal) |
| Exact bytes | 500000000000000 |
| Equivalent TiB | 454.747 |
| 256 GiB partitions | 1,819 |
| 1 GiB restart chunks | 465,662 |
| Last chunk bytes | 308494336 |
| Estimated rows at 2,048 bytes/row | 244140625000 |
| Integer checkpoint headroom | 2,147,017,985 |

## Proofs

| Proof | Result |
| --- | --- |
| Sparse manifest coverage | 465,662 chunks; 500000000000000 bytes; PASS |
| Manifest digest | `b669232783a157ee0713885701b8b37329da000d623d53673abd3fe0cfd18d27` |
| Injected failure | After 250,000 committed chunks |
| Resume | 215,662 remaining chunks; exact final coverage; zero duplicate chunks; PASS |
| Durable checkpoint footprint | 151 bytes |
| Physical bounded-stream sample | 268,435,456 bytes using a 1,048,576-byte reusable buffer; PASS |
| Sample digest | `f17d53b0a0d7968b33c22c7f941c8691c041be00dd5889f5be4998341927be9d` |
| Local memory/digest rate | 928,013,939 bytes/s |
| 500 TB projection at that isolated rate | 149.66 hours; **projection only** |

## Claim boundary

A physical support claim remains blocked until the production-equivalent staged gate executes
1%, 10%, 1 TB, 5 TB, and finally 500 TB with real source/target engines, masking policies,
LOB mix, native loaders, cancellation, restart, reconciliation, concurrency, and endurance evidence.
