# Operation 500 TB rolling endurance

**Status:** RUNNING

| Window | Elapsed min | Rows/engine | Rows both | Staged bytes | PostgreSQL ms | Oracle ms | Reconcile ms | Recycle ms | Rows/s both | Peak heap | Failures | Status |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 10.12 | 4400000 | 8800000 | 326013097 | 80624 | 503844 | 15664 | 1633 | 14488 | 67412336 | 0 | PASS |
| 2 | 20.36 | 5100000 | 10200000 | 386117648 | 109078 | 462114 | 8433 | 1120 | 16599 | 91497048 | 0 | PASS |
| 3 | 30.57 | 5100000 | 10200000 | 401400003 | 123077 | 456719 | 10229 | 2075 | 16655 | 93662312 | 0 | PASS |
| 4 | 40.85 | 3500000 | 7000000 | 276500000 | 119463 | 461449 | 17549 | 3234 | 11356 | 93699464 | 0 | PASS |
| 5 | 51.07 | 2800000 | 5600000 | 221200000 | 140191 | 423391 | 20537 | 5685 | 9125 | 93699464 | 0 | PASS |

Cumulative rows per engine: 20900000; across both engines: 41800000. This is rolling-load evidence, not a resident 500 TB certification.
