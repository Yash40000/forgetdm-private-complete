# Operation 500 TB real COW qualification

This gate tests non-H2, non-Docker virtualization primitives. The production-provider test targets an actual
Linux OpenZFS host. A local qualification path uses a dedicated WSL2 Ubuntu distribution and an isolated,
file-backed Btrfs pool to prove real filesystem copy-on-write behavior without modifying a physical volume.
Both paths write a bounded physical sample into an exact 500,000,000,000,000-byte sparse file.

It proves that the clone initially shares source blocks, a clone-only write consumes new blocks, and the source
remains unchanged. Cleanup destroys only the uniquely named qualification dataset and clone.

## Prerequisite

Configure key-based SSH to a Linux host with OpenZFS and an existing parent dataset:

```powershell
$env:FORGETDM_ZFS_HOST = "zfs-engine.example.internal"
$env:FORGETDM_ZFS_SSH_USER = "forgetdm"
$env:FORGETDM_ZFS_SSH_PORT = "22"
$env:FORGETDM_ZFS_POOL = "tank/forgetdm"
$env:FORGETDM_ZFS_USE_SUDO = "true" # only when the SSH user uses passwordless sudo
```

No Docker daemon is used by this qualification.

## Run

```powershell
cd "D:\forgetdm - Copy"
& .\docs\testing\run-operation-500tb-cow.ps1 -SampleMiB 256
```

Without `FORGETDM_ZFS_HOST`, the command intentionally records `REAL_COW_TEST_BLOCKED_NO_ZFS_ENGINE`; it never
falls back to H2, NTFS copying, or a simulated COW result.

## Local real-COW lab

The local lab requires WSL2 with Ubuntu 24.04 and `btrfs-progs`. It uses a Btrfs filesystem inside
`D:\forgetdm-cow-lab\btrfs-pool.img`; Docker and H2 are not started or used.

```powershell
cd "D:\forgetdm - Copy"
& .\docs\testing\run-operation-500tb-btrfs-cow.ps1 -SampleMiB 256 -MutationMiB 4
```

Evidence is written to `docs/testing/evidence/operation-500tb-cow/latest-btrfs-report.md` and the matching JSON
file. This proves the storage COW primitive. It does not certify the ForgeTDM ZFS provider or a database engine's
500 TB recovery path; that gate still requires a representative remote OpenZFS/database environment.

## Time interpretation

The first synchronized 500 TB baseline still has to cross the source, network, and storage path. Raw transfer is
about 5.79 days at 1 GB/s, 2.89 days at 2 GB/s, 27.78 hours at 5 GB/s, or 13.89 hours at 10 GB/s. Masking,
reconciliation, LOBs, source throttling, and operational safety add time.

After that baseline exists on ZFS, snapshots and thin clones are metadata operations and should normally take
seconds to a few minutes. Rewind is similarly fast at the storage layer, but database recovery and validation
still have to finish. A one-percent refresh is 5 TB of logical change, not another 500 TB full copy.

This qualification does not authorize a claim that 500 TB was physically populated. That final claim still needs
production-equivalent storage, source, network, masking, database recovery, and endurance evidence.
