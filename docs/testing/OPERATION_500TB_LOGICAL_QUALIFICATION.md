# Operation 500 TB logical qualification

Operation 500 TB is a control-plane and restartability qualification. It does not create a 500 TB local file or
claim that this workstation physically processed 500 TB.

## What the operation proves

- Exact, overflow-safe planning for 500,000,000,000,000 bytes.
- A sparse 256 GiB partition / 1 GiB restart-chunk manifest covers every byte once.
- The resulting checkpoint sequence fits the current persisted `INTEGER` chunk number.
- Unsafe chunk geometry is rejected before execution.
- A simulated interruption resumes from a durable checkpoint with no duplicate or missing chunks.
- Physical sample streaming reuses a fixed 1 MiB buffer; memory does not grow with logical volume.
- Local sample throughput is retained only as a projection, never as certification.

## Run it

```powershell
cd "D:\forgetdm - Copy"
& .\docs\testing\run-operation-500tb.ps1
```

Use `-SampleMiB 1024` for a longer local stream calibration. Evidence is retained under
`docs/testing/evidence/operation-500tb/<timestamp>/`, with `latest-report.md` and `latest-report.json` pointers.

## Claim boundary

The result is intentionally `LOGICAL_CONTROL_PLANE_PASS_PHYSICAL_CERTIFICATION_PENDING`. A support claim requires
production-equivalent source and target infrastructure and the staged physical gate: 1%, 10%, 1 TB, 5 TB, then
500 TB. That gate must include the actual row width and LOB mix, intended native loaders, masking policies,
cancellation, process and connector interruption, exact restart, reconciliation, concurrency, endurance, and
signed capacity-owner evidence.
