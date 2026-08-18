# Operation 500 TB CDC qualification

**Verdict:** CDC LOGICAL CAPTURE PASS; REAL APPLY SCALE PENDING

| Gate | Result |
| --- | --- |
| Baseline | 500,000,000,000,000 bytes (500 TB) |
| Qualified delta | 1% = 5,000,000,000,000 bytes (5 TB) |
| Restart chunks | 74,506 x 64 MiB |
| Injected failure | after 25,000 committed chunks |
| Resume | 49,506 remaining chunks, exact coverage, zero duplicate chunks |
| Capture memory boundary | bounded poll batches: PASS |
| Apply memory boundary | whole buffered range loaded/netted in memory: FAIL |

## Meaning

The checkpoint and replay design can represent a 5 TB delta over a 500 TB baseline without
allocating that data locally. Production-scale apply is not certified until buffered changes are
paged, netted, and committed in bounded windows with durable per-window checkpoints.
