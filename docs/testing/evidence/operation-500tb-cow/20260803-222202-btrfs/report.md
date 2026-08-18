# Operation 500 TB - Real COW Qualification

**Verdict:** PASS - real Btrfs copy-on-write primitives proven  
**Run:** 20260803-222202  
**Logical namespace:** 500000000000000 bytes (500 TB decimal)  
**Physical sample written:** 256 MiB  
**Engine:** btrfs-progs v6.6.3 on WSL2 kernel 6.18.33.2-microsoft-standard-WSL2  
**Docker/H2:** not used

## Measured Operations

| Operation | Time |
|---|---:|
| Non-compressible sample write + sync | 22232 ms |
| Read-only baseline snapshot | 91 ms |
| Writable thin clone | 71 ms |
| Clone-only 4 MiB mutation + sync | 694 ms |
| Rewind from baseline | 629 ms |

## Assertions

- Exact 500 TB sparse logical file created: PASS
- Clone initially matched source sample hash: PASS
- Clone mutation left source unchanged: PASS
- Mutated clone diverged from source: PASS
- Rewind restored the known-good baseline: PASS
- Real filesystem COW used: PASS

## Boundary

This qualification proves Btrfs filesystem COW metadata behavior for an exact 500 TB logical namespace. It does not claim that 500 TB of physical data was transferred, masked, recovered by a database engine, or performance-certified. Those require representative storage, database recovery and workload tests.
