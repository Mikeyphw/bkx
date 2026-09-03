# BKX Python → Go migration ledger

BKX is migrating the backend implementation from Python to Go without rewriting
the Android product. The Kotlin/Compose application and the protocol-v1 JSON seam
remain stable while implementation slices are replaced behind them.

## Non-negotiable cutover rules

1. The Python backend remains production-authoritative until the corresponding Go
   slice passes differential or frozen-contract parity tests.
2. Android remains Kotlin/Compose. A backend-language change does not authorize UI
   feature redesign.
3. Protocol-v1 request/result/progress/cancellation semantics remain stable during
   the migration.
4. The frozen CLI, exit-code, configuration, run-history, and canonical spec-schema
   contracts may change only through an explicit versioned contract update.
5. Arbitrary privileged shell execution is not added to the Android bridge. Root
   behavior remains capability-scoped.
6. Python runtime/build dependencies are removed only after their production and
   validation responsibilities have accepted Go/Kotlin replacements.

## GO00 — Freeze compatibility contracts — COMPLETE

The committed `migration/contracts/v1/` baseline is generated from the current
Python implementation and checked for byte-for-byte drift. It freezes:

| Surface | Frozen baseline |
| --- | ---: |
| CLI command nodes | 23 |
| CLI leaf commands | 16 |
| Android bridge protocol | 1 |
| Bridge capabilities | 18 |
| Bridge handlers | 16 |
| Advertised bridge operations | 15 |
| Canonical operation schemas | 4 |
| Product/Python backend version | 1.0.0 |
| Current Android backend runtime | `embedded-cpython` |

The bridge handler set contains backup planning/creation, archive
list/info/browse/verify, restore preview/extract, transfer plan/run, run
list/show/cancel, storage locations, and filesystem list/stat. The advertised
operation list intentionally excludes bridge plumbing capabilities which are not
normal executable operations.

Regenerate only after an intentional compatibility change:

```sh
PYTHONPATH=src python3 tools/go_migration/export_contracts.py
```

Check without modifying the repository:

```sh
PYTHONPATH=src python3 tools/go_migration/export_contracts.py --check
```

`manifest.json` contains SHA-256 and byte-size evidence for every frozen payload.
Go tests load and validate the same files, so drift must be accepted by both
languages rather than silently changing one side.

## GO01 — Go skeleton + Android ARM64 build spike — COMPLETE

GO01 adds:

- Go module `bkx` with `cmd/bkx`;
- internal version and frozen-contract decoders;
- Go unit tests and contract hash/invariant validation;
- a dedicated Devtool `bkx_go` target;
- native Linux build proof;
- `GOOS=android GOARCH=arm64 CGO_ENABLED=0` build proof;
- a fail-closed scaffold which rejects product commands until parity exists;
- hosted CI gates for Go test/vet, contract validation, and Android ARM64 compile.

The scaffold accepts only migration introspection (`--version` and
`contract check`). `backup`, `archive`, `restore`, `transfer`, `tools`, `run`, and
`ui` remain owned by the Python product.

The Android ARM64 build spike proves executable delivery only. It does **not** prove
filesystem, archive, root-service, transfer, cancellation, storage, or real-device
behavior. Those require later operation-specific implementation and parity/device
gates.

## GO02 — Run-history persistence compatibility — COMPLETE

BKX persistence is the production JSONL run-history store, not SQLite. GO02 ports
that persisted surface in place rather than introducing a Go-only database or
parallel state format.

Delivered:

- the same twelve snake_case persisted fields and canonical one-object-per-line
  JSON representation;
- the frozen operation/status sets;
- `.lock` advisory locking and `.corrupt` quarantine conventions;
- malformed/duplicate-record quarantine without discarding valid records;
- atomic same-directory rewrite, file sync, rename, and best-effort directory sync;
- retention, start/completion, listing/count/get/clear, and interrupted-run recovery;
- Python → Go → Python differential round-trip verification.

The Go migration probe may mutate a temporary production-format history file, but
normal product run-history commands still remain Python-owned.

## GO03 — Models, validation, config — COMPLETE

Delivered migration foundations:

- Go structs with frozen snake_case JSON names for the four canonical operation
  specs and run-history records;
- typed BKX error kinds and stable exit-code mapping 0–5;
- defaults/file/environment/CLI configuration precedence and per-field provenance;
- deterministic config search order and relative file-path resolution;
- compression-profile, pattern-set, split-size, archive-name/member, containment,
  overlap, transfer-location, and deterministic-name validation helpers;
- shape/policy validation for backup, archive-create, restore, and transfer specs;
- stronger Go decoding of the GO00 core contract so config and run-history drift is
  rejected before later ports build on it;
- a differential config probe comparing Python and Go effective values/provenance.

`cmd/bkx` remains fail-closed for production operations. These foundations are
accepted migration building blocks, not a backend cutover.

## GO04 — Filesystem, traversal, hashing, tool detection — COMPLETE

Delivered migration-only low-level primitives:

- filesystem `stat`/paged `list` semantics aligned with the Android protocol-v1 handlers;
- deterministic source traversal with include/exclude matching, archive member names,
  overlap checks, special-file rejection, and reject/skip symlink policies;
- pure-Go MD5/SHA-256/SHA-512 file hashing, verification, and deterministic directory
  checksums matching the Python source-manifest algorithm;
- bounded executable/tool detection with PATH-sensitive cache invalidation, version
  parsing, capabilities, and configured preference behavior;
- Python ↔ Go differential fixtures covering filesystem metadata/list ordering,
  traversal metadata, all built-in hash algorithms, directory checksums, and tool probes;
- deterministic Android build verification using Go build metadata plus ELF
  `e_machine=183` (`EM_AARCH64`) rather than `file(1)` wording heuristics.

These primitives remain reachable only through `migration ...` probes in the Go
scaffold. Production CLI/Android operation ownership remains Python.

## GO05 — Read-only archive operations — COMPLETE

Delivered migration-only archive operations:

- archive list with complete-archive totals, pagination, and Python-style fnmatch filtering;
- archive info with format, file-size, uncompressed-size, entry-count, timestamp, and ratio parity;
- archive browse with query/type/size filters, stable sorting, offset/limit, and result totals;
- archive verify with requested MD5/SHA-256/SHA-512 whole-archive hashes, payload/CRC reads,
  unsafe member rejection, TAR link rejection, and structural-failure classification;
- ZIP/TAR/TAR.GZ/TAR.BZ2 differential fixtures, with optional external `xz` streaming for
  TAR.XZ when the tool is available;
- corrupted ZIP and traversal-member parity fixtures;
- migration-only CLI probes under `migration archive ...`.

Normal `bkx archive ...` product commands remain Python-owned and the Go scaffold still
fails closed. Archive creation and restore/extraction are intentionally deferred because
they mutate user data and require stronger transactional/device gates.

## Next gate

GO06 should port archive creation with transactional publication, compression-policy
parity, shared GO04 traversal, optional verification, and no production cutover until
Python-Go artifact equivalence and failure rollback are accepted.
