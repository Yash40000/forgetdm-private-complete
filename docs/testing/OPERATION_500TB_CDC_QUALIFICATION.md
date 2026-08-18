# Operation 500 TB CDC qualification

Run:

```powershell
powershell -ExecutionPolicy Bypass -File docs/testing/run-operation-500tb-cdc.ps1
```

The qualification combines exact 500 TB delta/checkpoint arithmetic with a disposable real PostgreSQL
logical-replication test. It proves bounded WAL polling, operation decoding, primary-key capture, checkpoint
advance, and restart geometry. It does not claim that this workstation physically applied a 5 TB delta.

The current release gate remains blocked for production-scale apply because `CdcService.applyIncremental`
loads the complete buffered range and `CdcIncrementalApplier` nets it in one in-memory map. That path must be
converted to durable, ordered pages before 500 TB CDC can receive an end-to-end physical pass.

PostgreSQL checkpoints are transaction-atomic. `maxChanges` is a soft batch boundary: one source transaction
larger than that value is completed before the slot advances, preventing duplicate replay. Production sources
must therefore cap transaction size or provide enough memory for the largest permitted transaction.
