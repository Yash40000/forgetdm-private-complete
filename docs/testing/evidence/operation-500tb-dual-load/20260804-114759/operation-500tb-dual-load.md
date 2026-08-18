# Operation 500 TB dual-engine load

**Verdict:** REAL BOUNDED NATIVE LOAD PASS; 500 TB PHYSICAL CERTIFICATION PENDING

| Engine | Physical path | Sample rows | Result |
| --- | --- | ---: | --- |
| PostgreSQL | COPY FROM STDIN | 100,000 | PASS |
| Oracle | SQL*Loader direct recoverable | 100,000 | PASS |

Both targets reconciled row count, ID sum and amount sum. The exact 500 TB plan uses 1,862,646
restart chunks of 256 MiB per engine. This is not evidence that 500 TB was physically loaded.
