# Operation 500 TB rolling endurance

**Status:** RUNNING

| Window | Elapsed min | Rows/engine | Rows both | Staged bytes | PostgreSQL ms | Oracle ms | Reconcile ms | Recycle ms | Rows/s both | Peak heap | Failures | Status |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 10.1 | 4100000 | 8200000 | 303513097 | 122240 | 444613 | 19017 | 2999 | 13528 | 75319776 | 0 | PASS |
| 2 | 20.19 | 3900000 | 7800000 | 294617648 | 130030 | 418112 | 10679 | 2043 | 12886 | 139159232 | 0 | PASS |
| 3 | 30.32 | 2300000 | 4600000 | 175700003 | 143978 | 418934 | 15077 | 3928 | 7572 | 139159232 | 0 | PASS |

Cumulative rows per engine: 10300000; across both engines: 20600000. This is rolling-load evidence, not a resident 500 TB certification.
