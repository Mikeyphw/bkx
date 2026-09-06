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
| Current Android backend runtime | `go-native-android` (GO14 Android APK is Go-only) |

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

## GO06 — Archive creation — COMPLETE

Delivered migration-only archive creation:

- same-directory temporary publication with cleanup on every pre-publication failure;
- GO04 source traversal, include/exclude filtering, empty-directory preservation, overlap
  rejection, and reject/skip symlink policy reuse;
- no-overwrite behavior plus split-archive fail-closed behavior matching the builtin Python archiver;
- optional pre-publication verification through the GO05 reader before fsync + rename;
- Python-compatible implicit default zstd fallback (plain TAR or ZIP deflate) while explicit
  unsupported zstd/lz4 requests remain rejected;
- TAR and ZIP creation for none/gzip, plus optional-tool BZIP2/XZ creation;
- GO05 ZIP payload reading extended for BZIP2 and ZIP-LZMA so Python and Go artifacts can
  verify each other;
- Python ↔ Go artifact semantic equivalence fixtures across member names, empty directories,
  payload hashes, filters, dry-run behavior, default compatibility, explicit rejection, and
  split rejection;
- migration-only `migration archive create` probe; normal product archive creation remains Python-owned.

## GO07 — Restore preview and safe extraction — COMPLETE

Delivered migration-only restore operations:

- restore preview with Python-compatible selector matching, file counts, byte totals, existing-target
  counts, destination paths, and confirmation warnings;
- shared archive-member validation corrected to preserve legal trailing-slash directory members
  from the frozen schema (`dir/`) while still rejecting traversal/absolute/NUL spellings;
- extraction rejects traversal, ZIP symlink encodings, TAR links, and unsupported special members;
- destination-root and existing-target symlink traversal are rejected before mutation;
- all payloads are extracted and fsynced in a same-parent staging tree before publication;
- merge publication preflights every target before mutation, refuses collisions unless overwrite is
  explicit, and keeps a rollback tree so an injected mid-publication failure restores prior files
  and removes newly-created output;
- absent destinations publish through one same-filesystem rename;
- optional pre-extraction archive verification reuses GO05;
- Python ↔ Go differential fixtures cover ZIP/TAR, full/partial selectors, dry-run, verification,
  confirmation, collisions, unsafe traversal/link behavior, and final extracted content;
- migration-only `migration restore preview|extract` probes; normal product restore remains Python-owned.

## GO08 — Backup planning/create orchestration — COMPLETE

Delivered migration-only backup orchestration:

- strict frozen `BackupSpec` JSON parsing plus Python-compatible source normalization, deterministic
  archive naming, destination resolution, and overlap rejection;
- Python-compatible execution-plan projection for archive/transfer/verification steps and source totals;
- backup creation composed from GO06 archive creation rather than a duplicate writer;
- destination-backed backups stage locally, hash their staged artifact, then publish through a
  same-directory no-overwrite transfer path with fsync and optional SHA-256 verification;
- GO02 JSONL run-history start/completion records include normalized backup sources, destination,
  archive path, spec snapshot, files, bytes, success/failure, and interrupted cancellation state;
- cooperative `archiving`, `publishing`, and `complete` progress events plus cancellation checks before
  final publication, with regression proof that cancellation leaves no destination artifact;
- split backups remain fail-closed while GO06 builtin splitting is intentionally unsupported;
- Python ↔ Go differential fixtures cover plan shape, create truth, dry-run, run-history semantics,
  logical archive members/payloads, self-consistent checksums/byte counts, malformed spec rejection,
  split rejection, and cancellation;
- migration-only `migration backup plan|create` probes; normal product backup remains Python-owned.

## GO09 — Local transfer plan/run — COMPLETE

Delivered migration-only local transfer operations:

- strict frozen `TransferSpec` JSON parsing and local-only push/pull normalization;
- Python-compatible single-file, directory, and multi-source destination mapping plus complete collision preflight;
- nested symlink/special-file rejection, destination symlink refusal, and source/destination overlap checks;
- same-directory staging, fsync, metadata preservation, overwrite policy, and Android/Termux-safe no-overwrite publication using `RENAME_NOREPLACE` with `O_EXCL` fallback;
- verification/checksum policy for common hashlib algorithms and explicit expected SHA-256 digests;
- cooperative progress/cancellation with no publication when cancellation arrives before the current file is committed;
- GO02 JSONL run-history start/completion records for transfer runs;
- Python ↔ Go differential fixtures for plan shape, push/pull, directory/multi-source mappings, dry-run, overwrite/collisions, checksum policy, run history, and remote fail-closed behavior;
- migration-only `migration transfer plan|run` probes; normal product transfer commands remain Python-owned.

## GO10 — Transport-neutral service/dispatch layer — COMPLETE

Delivered migration-only service composition:

- one typed Go dispatcher composes the migrated filesystem, archive, restore, backup, transfer, run-history, and storage operations without duplicating their domain logic;
- the service operation registry is exactly the 15 operations advertised by the frozen Android protocol-v1 contract;
- service results expose the transport-neutral Android result core: status, summary, issues, and artifacts; protocol/request envelopes, metrics, progress sequencing, and cancellation transport remain outside this layer;
- backup/transfer mutation dispatch reuses GO08/GO09 run-history, progress, cancellation, verification, and safe-publication behavior;
- archive discovery merges bounded filesystem roots with successful archive paths from GO02 run history while refusing symlink traversal;
- Python ↔ Go differential fixtures prove exact bridge-result-core parity for filesystem, storage, archive read/verify/list, backup/transfer plans, restore preview, and run-history list/show;
- end-to-end Go service tests cover backup → archive → restore, local transfer, run-history lookup, archive discovery, cancellation, unknown-operation rejection, and the exact frozen operation registry;
- migration-only `migration service operations|dispatch` probes expose evidence without creating a product alias.

The Android app still routes protocol-v1 requests to embedded Python, and normal Go product commands remain fail-closed.

## GO11 — Protocol-v1 Go bridge adapter — COMPLETE

Delivered migration-only bridge transport:

- strict protocol-v1 request parsing with the frozen request-id alphabet, object-only payloads, one-document JSON enforcement, protocol-version checks, and fail-closed unsupported operations;
- protocol-v1 result envelopes with status/summary/errors/artifacts plus runtime-owned duration metrics;
- typed BKX error mapping for validation, not-found, permission, external/optional capability, and unexpected-runtime failures;
- readiness exposes the frozen capability set plus direct/root runtime capabilities while explicitly identifying this pre-cutover runtime as `go-native-migration`;
- in-process monotonic progress sequencing, final success/cancel/failure progress documents, bounded runtime progress state, and request-id reuse semantics matching embedded Python;
- cooperative cancellation through both the `cancel` entrypoint and the non-advertised `run.cancel` handler without expanding the frozen 15-operation advertised surface;
- a migration-only NDJSON session probe exercises readiness/execute/progress/cancel against one persistent Go runtime instance;
- Python ↔ Go differential fixtures cover success envelopes, malformed/unsupported requests, typed error actions, queued cancellation, `run.cancel`, final progress, and runtime capability parity;
- normal Go product commands remain fail-closed and Android production routing remains embedded Python.

## GO12 — Android Go runtime integration boundary — COMPLETE

Delivered without enabling the production cutover by default:

- the Go bridge now has a distinct `go-native-android` readiness identity while retaining the GO11 `go-native-migration` parity identity;
- `bkx android-runtime bridge-session` is an internal concurrent NDJSON process transport with correlated replies, so progress and cancellation are serviced while execute is still running;
- Android Gradle generates an ARM64 PIE `libbkxgo.so` from `cmd/bkx`, verifies Android/ARM64 ELF metadata, and packages it as an extracted app-owned native library;
- Kotlin `GoNativeGateway` launches only `ApplicationInfo.nativeLibraryDir/libbkxgo.so` directly, never a shell or arbitrary executable, and multiplexes readiness/execute/progress/cancel over one persistent runtime process;
- `EmbeddedBkxBackend` accepts both `embedded-cpython` and `go-native-android` at protocol v1;
- `NativePreferredGateway` plus `GoRuntimeCutoverPolicy` define the explicit cutover seam, with `ENABLED_BY_DEFAULT=false` so the frozen production backend remains Python until the next device gate;
- the libsu UID-0 path deliberately remains Chaquopy-backed in GO12; root Go execution is not silently introduced;
- a real Android instrumentation test bypasses the disabled cutover flag and probes the packaged Go readiness, execute, progress, and queued cancellation entrypoints on ARM64;
- Linux validation proves the Android session can service progress/cancel concurrently with a long transfer execute request.

## GO13 — Android production Go cutover — COMPLETE

Delivered production routing and device gates:

- `GoRuntimeCutoverPolicy.ENABLED_BY_DEFAULT=true` selects the packaged Go runtime on supported ARM64 devices;
- the local route retains Chaquopy only as a controlled unsupported-ABI/runtime rollback path;
- the libsu RootService launches the same app-owned `libbkxgo.so` from its UID-0 process and contains no Chaquopy/Python execution path;
- local/root Go state directories are isolated (`go-local` / `go-root`) so UID-0 run-history files cannot break non-root runtime ownership;
- the request router is transport-neutral (`RootAwareGateway`) and keeps active request routes pinned for progress/cancellation;
- normal ARM64 instrumentation proves the production local selector cannot fall back to Python;
- explicit physical-device qualification now requires both privileged and local readiness identities to be `go-native-android`, in addition to root and Termux-private storage access;
- Android preflight/release tasks require the generated AArch64 runtime and GO13 cutover source contract before packaging.

## GO14 — Android Python/Chaquopy retirement — COMPLETE

Delivered the Android runtime cleanup after GO13 production cutover:

- removed the Chaquopy Gradle plugin/version and all build-Python probing/source packaging from the Android project;
- deleted `ChaquopyPythonGateway`, `EmbeddedPythonGateway`, `NativePreferredGateway`, `GoRuntimeCutoverPolicy`, and the selector unit test;
- production local routing now instantiates `GoNativeGateway` directly while `RootAwareGateway` continues to pin local/root routes per request;
- `EmbeddedBkxBackend` now accepts only `go-native-android`; protocol-v1 retains the historical `python_version` field with value `none` for wire compatibility;
- Android is intentionally `arm64-v8a` only. CGO-free Android/amd64 executable linking is not supported by the current Go toolchain, and GO14 refuses to reintroduce a host-NDK/CGo runtime build dependency merely to preserve the old Python fallback ABI;
- NDK remains pinned to `29.0.14206865` with a hard r29+ validator floor;
- AGP generated-JNI source wiring, root UID-0 execution, progress/cancellation, and Python↔Go repository parity gates remain sealed;
- human Go product CLI commands remain fail-closed pending their own explicit cutover phase.

## GO15 — Public Go CLI production cutover — COMPLETE

Delivered the human CLI cutover without prematurely deleting the Python oracle:

- a dedicated Go public parser/dispatcher owns all 16 frozen leaf commands and seven frozen global parameter families from `migration/contracts/v1/cli.json`;
- `--version` is resolved before the no-command help path, public help is deterministic/golden-tested, and migration/bridge/Android probes remain hidden internal routes;
- archive, backup, restore, transfer, tools, and run-history leaves call the already-migrated Go domain engines directly rather than going through the protocol bridge;
- JSON plans, results, typed errors, and exit codes are checked against the Python reference with an in-process Typer `CliRunner` parity harness;
- GO02–GO14 historical contracts/validators are forward-compatible with the GO15 ownership boundary instead of being deleted;
- `ui start` is the only temporary Python-reference bridge in GO15;
- Android remains Go-only `arm64-v8a`, with NDK `29.0.14206865`; the inapplicable ChromeOS alternate-ABI lint is explicitly disabled instead of adding a fake runtime ABI;
- the flattened GO15 validator reuses one host binary for GO02–GO13 differential verification and performs one Android ARM64 runtime build.

## GO16 — Go production identity and deterministic release — COMPLETE

Delivered the repository/package ownership cutover after GO15 CLI parity:

- Devtool project identity and the production `bkx` target are Go; Python is exposed only as the explicit `bkx_reference` target through GO17;
- the Python distribution has no base runtime dependencies and no `bkx` console script; its CLI dependencies live under the `reference` extra for differential testing;
- `ui start` no longer starts Python. It resolves external `bkx-tui` / `bkx-interactive` executables, supports path/name overrides through `BKX_TUI_COMMAND` / `BKX_INTERACTIVE_COMMAND`, never evaluates a shell command, and returns the typed optional-feature exit when a plugin is absent;
- host and Android ARM64 binaries use `-trimpath`, disabled VCS embedding, an empty Go build ID, and an explicit build-commit injection;
- `scripts/build-go-release.sh` emits the host CLI, `android/arm64-v8a/libbkxgo.so`, and a sorted two-entry `SHA256SUMS`, with the validator proving byte-for-byte reproducibility for a fixed commit;
- GO15 remains a callable historical regression seal but GO16 reuses the release build for the Android runtime check to avoid redundant native builds;
- CI now names the Go lane as production and the Python 3.11–3.13 lane as reference, including proof that the Python reference wheel creates no `bkx` executable.

## Next gate

GO17 may retire or archive the Python reference implementation only after the frozen differential corpus is replaced by durable Go-owned contract fixtures and release evidence. Until then, Python remains reference-only and must not regain production console, Android, or Devtool ownership.



## GO17 — immutable reference oracle

GO17 retires dynamic Python parity from normal validation. The accepted GO02–GO13 differential evidence and GO15 public CLI parity are frozen into `migration/fixtures/v1/`, including the same 14 JSON and 3 parser/usage cases. `internal/compatfixtures` executes those fixtures directly against the Go production surface. The historical Python tree is preserved only as the deterministic archive in `migration/archive/`; CI, Devtool production validation, release building, and Android release gates do not import or launch it. Android verification now checks the Go bridge/archive/restore/transfer implementation directly and derives the product version from `internal/version/version.go`.

## GO17 — Frozen reference oracle and dynamic parity retirement

GO17 accepted the final dynamic differential corpus and converted it into immutable,
Go-owned repository fixtures. `migration/fixtures/v1/reference_oracle.json` freezes the
14 JSON and 3 usage public-CLI cases plus protocol/readiness expectations that were
previously checked by executing both implementations. Go tests now execute that corpus
directly through `internal/publiccli` and the Go bridge. The accepted GO16 reference
source/tooling was captured in a deterministic audit archive under `migration/archive/`.
Normal CI, release, Devtool production validation, and Android checks no longer execute
that archive or require a Python interpreter.

## GO18 — Active Python source/package purge

GO18 removes the active reference source tree, Python tests and migration tooling,
Python project/package configuration, Python Devtool target/settings, and historical
Python-dependent validators from normal repository paths. CI runs
`scripts/validate-go18.sh`, which composes the GO17 immutable compatibility and
reproducible-release seal with a Go-owned repository-purity test.

The historical `migration/contracts/v1/` fields retain their original semantics and
must not be rewritten merely to remove historical naming. Likewise the protocol-v1
`python_version` readiness field remains a wire-compatibility field with value `none`.
The deterministic archive is evidence only: it is excluded from all active source,
build, test, package, release, Devtool, and Android ownership.
