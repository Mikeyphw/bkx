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

## Next gate

The next migration work must consume `migration/contracts/v1/` rather than
redesigning public behavior. No Go slice becomes production-authoritative merely
because it compiles.
