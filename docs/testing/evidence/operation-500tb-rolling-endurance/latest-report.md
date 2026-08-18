# Operation 500 TB rolling endurance

**Status:** PASS

| Window | Elapsed min | Rows/engine | Rows both | Staged bytes | PostgreSQL ms | Oracle ms | Reconcile ms | Recycle ms | Rows/s both | Peak heap | Failures | Status |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 10.14 | 5500000 | 11000000 | 408513097 | 117336 | 445524 | 5456 | 1253 | 18081 | 82182072 | 0 | PASS |

Cumulative rows per engine: 5500000; across both engines: 11000000. This is rolling-load evidence, not a resident 500 TB certification.
