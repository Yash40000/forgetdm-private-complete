# Operation 500 TB real COW qualification

**Verdict:** `REAL_COW_TEST_BLOCKED_NO_ZFS_ENGINE`

This qualification never uses H2 or Docker. It requires an actual Linux OpenZFS host and proves snapshot, clone, shared-block isolation, and changed-block allocation. A sparse 500 TB logical file proves namespace scale; only the configured sample is physically written, so this is not a physical 500 TB throughput certification.

**Reason/claim boundary:** FORGETDM_ZFS_HOST is not configured; this Windows host has no local OpenZFS engine.

## Raw-transfer planning range

| Sustained end-to-end rate | 500 TB raw transfer |
| ---: | ---: |
| 0.5 GB/s | 11.57 days |
| 1 GB/s | 5.79 days |
| 2 GB/s | 2.89 days |
| 5 GB/s | 27.78 hours |
| 10 GB/s | 13.89 hours |
| 20 GB/s | 6.94 hours |

Production planning must add masking, reconciliation, source throttling, LOB behavior, and safety margin.
