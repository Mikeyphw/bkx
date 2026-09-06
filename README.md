# BKX

BKX is a contract-controlled backup/archive tool whose **production CLI is Go**.
GO18 completes the active repository purge: the accepted differential evidence is frozen in immutable Go-owned fixtures, while the former Python implementation exists only inside a deterministic historical archive. Normal source, tests, packaging, Devtool, CI, release, and Android paths are Go/Kotlin only.

## Current verified capabilities

- The Go production CLI owns the frozen backup/archive/restore/transfer/tools/run grammar.
- JSON output, dry-run/plan rendering, typed exit codes, configuration defaults, and run history are Go-owned.
- Android is Go-only `arm64-v8a`, with NDK 29 retained as the release floor.
- `ui start` launches optional external `bkx-tui` or `bkx-interactive` plugins; missing plugins fail with a typed actionable error.
- Host and Android release artifacts are built deterministically and sealed by `SHA256SUMS`.
- The accepted pre-GO18 implementation is preserved only as a deterministic audit archive under `migration/archive/`; compatibility validation runs only Go-owned fixtures.

## Build / install

Build the production CLI directly:

```bash
go build -trimpath -buildvcs=false -o bkx ./cmd/bkx
./bkx --help
```

Or produce the sealed host + Android release bundle:

```bash
./scripts/build-go-release.sh ./dist/go-release
cat ./dist/go-release/SHA256SUMS
```

The retired implementation is retained only inside the deterministic audit archive in `migration/archive/`; it is not an active repository source/package/test/runtime path.

Optional UI implementations are external plugins named `bkx-tui` and
`bkx-interactive`. Their executable paths may be overridden with
`BKX_TUI_COMMAND` and `BKX_INTERACTIVE_COMMAND` respectively. Overrides are
executable paths/names, not shell command strings.

## Examples

```bash
bkx --help
bkx --json --plan backup create ./src --exclude '*.pyc'
bkx archive list backup.tar --page 0 --page-size 50
bkx restore pick backup.tar docs/readme.md --destination ./restore --yes
bkx ui start --mode tui
```

## Validation

`bash ./scripts/validate-go19.sh` runs the complete Go-only compatibility/release seal plus the GO19 final roadmap audit. The 14 JSON + 3 usage public-CLI corpus and protocol expectations are frozen under `migration/fixtures/v1/`.

## Python → Go migration

GO00–GO13 establish a compatibility-first migration lane without changing the
production backend. `migration/contracts/v1/` freezes the current Python CLI,
protocol-v1 Android bridge, core configuration/error/run-history contracts, and
canonical operation schemas. The Go tree now contains the migration scaffold plus
an in-place JSONL run-history implementation, frozen model shapes, typed errors,
configuration layering, and shared validation/path rules.

GO02/GO03 are guarded by a differential test: Python writes production-format run
history and configuration state, Go reads/mutates it, and Python reopens the result.
The Go executable still deliberately rejects normal backup/archive/restore/transfer
commands until later operation/service parity gates are complete.

Use `devtool --target bkx_go validate` for the Go lane or
`scripts/validate-go12.sh` for the complete migration gate. The Android app remains Kotlin/Compose. GO12 packages and validates the ARM64 Go runtime behind an explicit disabled-by-default cutover seal, while the production route remains embedded Python until GO13 device evidence is accepted.
See `docs/migration/python-to-go.md`.


### Go migration GO04

GO04 ports the migration-only filesystem/traversal/hash/tool-detection foundation and
adds deterministic Android ARM64 ELF/build-metadata validation. Python remains the
production backend; Go product commands still fail closed until later parity gates.


### Go migration GO05

GO05 ports read-only archive operations (`list`, `info`, `browse`, and `verify`) into
the migration-only Go backend, with Python-Go parity across ZIP, TAR, TAR.GZ, and
TAR.BZ2 fixtures plus corruption and unsafe-member checks. Normal `bkx archive ...`
commands remain Python-owned and fail closed in the Go scaffold.


### Go migration GO06

GO06 ports archive creation into the migration-only Go backend. Publication is
transactional (same-directory staging, optional verification, fsync, rename, cleanup
on failure), reuses GO04 traversal/filter/symlink safety, and preserves Python's
implicit-default compression compatibility. TAR and ZIP creation support none/gzip
directly and BZIP2/XZ through explicitly detected optional tools where required.
The normal Go `bkx archive create` product command remains fail-closed.

### Go migration GO07

GO07 ports restore preview/extraction into the migration-only Go backend. It preserves
Python selector/preview semantics, rejects traversal and unsafe link/special members at
extraction, refuses destination symlink traversal, stages all payloads before publication,
preflights merge collisions, and rolls back partially published overwrite merges on failure.
Normal Go `bkx restore ...` product commands remain fail-closed.

### Go migration GO08

GO08 ports backup planning/create orchestration into the migration-only Go backend. It
reuses GO06 archive creation and GO02 run history, preserves deterministic backup naming
and destination policy, stages destination-backed archives before publication, verifies
archive and local-transfer evidence, refuses overwrite races through same-directory
no-replace publication, and exposes cooperative progress/cancellation checkpoints before
final publication. Python↔Go differential fixtures cover plan projection, successful
create, dry-run, run-history truth, split fail-closed behavior, malformed frozen-spec
input, cancellation, and logical archive-content equivalence. Normal Go `bkx backup ...`
product commands remain fail-closed.



### Go migration GO09

GO09 ports first-class local transfer planning/run operations into the migration-only Go backend. It preserves Python multi-source mapping and collision rules, local push/pull direction semantics, no-overwrite publication with the Android/Termux-safe GO08 fallback, overwrite behavior, file metadata, checksum/verification policy, progress/cancellation, and GO02 run-history truth. Remote URI transfers remain fail-closed until a later transport phase. Normal Go `bkx transfer ...` product commands remain fail-closed.

### Go migration GO10

GO10 composes the migrated operation set behind one transport-neutral Go dispatcher.
The service registry is exactly the 15 operations advertised by the frozen Android
protocol-v1 contract, and Python↔Go differential fixtures prove the bridge-result
core for read, plan, and run-history surfaces. Request envelopes, protocol versioning,
progress sequence numbers, cancellation transport, and Android routing remain outside
the service layer for the next gate. Normal Go product commands and the Android app
remain Python-owned and fail closed.

### Go migration GO11

GO11 adds the migration-only protocol-v1 Go bridge adapter over GO10. It owns strict request parsing, protocol/result/error envelopes, readiness/capability reporting, final metrics, monotonic progress documents, `cancel` plus the non-advertised `run.cancel` handler, and cooperative cancellation state. A persistent NDJSON migration session probe exercises multiple bridge entrypoints against one runtime instance. Android still routes production requests to embedded Python; normal Go product commands remain fail-closed.


### Go migration GO12

GO12 integrates the sealed protocol-v1 Go adapter into the Android package without flipping production by default. Gradle generates an ARM64 `libbkxgo.so`; Kotlin launches that app-owned executable through a concurrent correlated session capable of progress and cancellation during execute. `GoRuntimeCutoverPolicy.ENABLED_BY_DEFAULT` remains false, the packaged Go runtime is the production/local runtime, and the privileged libsu route remains Python. ARM64 instrumentation can exercise Go directly before GO13 considers the actual cutover.


### Go migration GO14

GO14 retires Python from the Android APK. Chaquopy, build-Python probing, the Python gateway, and the local fallback selector are removed. Android now packages only the generated `arm64-v8a` Go protocol-v1 runtime; local and libsu UID-0 routes use the same app-owned binary. NDK `29.0.14206865` remains pinned with an r29+ floor. The repository Python CLI/reference implementation remains in place, and human Go product commands still fail closed pending a separate CLI cutover.

### Go migration GO15

GO15 cuts the frozen 23-node/16-leaf human CLI over to Go while keeping the repository Python package as the production packaging/reference lane for one final phase. Global flags, typed exit codes, JSON plans/results/errors, config defaults, run history, tools, archive/backup/restore/transfer commands, and `--version` precedence are owned by the Go public dispatcher. Migration, bridge, contract, and Android-runtime probes remain hidden/internal. `ui start` is the sole temporary Python-reference exception until GO16. The GO15 validator is flattened: one host binary runs GO02–GO13 differential regressions plus public CLI parity, and one Android ARM64 runtime build seals the intentional `arm64-v8a`/NDK 29 policy. The inapplicable ChromeOS alternate-ABI lint is suppressed explicitly rather than fabricating an unsupported ABI.


### Go migration GO17

GO17 converts the accepted dynamic differential evidence into immutable Go-owned fixtures. The same 14 JSON + 3 usage public-CLI cases and protocol/readiness expectations are enforced without importing or executing the retired implementation. A deterministic audit archive preserves the accepted GO16 reference source/tooling for historical evidence only, and normal CI/release/Android validation becomes pure Go/Kotlin.

### Go migration GO18

GO18 removes active Python source, tests, packaging metadata, migration scripts, Python Devtool ownership, and Python-specific production validation paths. `validate-go18.sh` composes the GO17 compatibility/release seal with a repository-purity gate. The immutable GO00 contract and GO17 oracle remain versioned evidence; the archived reference bundle is explicitly excluded from runtime and build ownership.

### Go migration GO19

GO19 is the final roadmap reconciliation seal. It checks the frozen CLI/protocol counts, run-history compatibility evidence, Android root/local Go routing, arm64-v8a + NDK29 policy, repository purity, and deterministic release output. See `docs/migration/FINAL_AUDIT.md`.
