# Operation 500 TB rolling endurance

**Status:** PASS

| Window | Elapsed min | Rows/engine | Rows both | Staged bytes | PostgreSQL ms | Oracle ms | Reconcile ms | Recycle ms | Rows/s both | Peak heap | Failures | Status |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 1.05 | 120000 | 240000 | 8121329 | 9017 | 40861 | 1742 | 2360 | 3818 | 54358824 | 0 | PASS |

Cumulative rows per engine: 120000; across both engines: 240000. This is rolling-load evidence, not a resident 500 TB certification.
