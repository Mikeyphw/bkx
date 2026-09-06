# BKX Android architecture

BKX Android is a Kotlin/Compose application over the same BKX engine used by the CLI.
The Android boundary is protocol-v1 JSON (`readiness`, `execute`, `progress`, and `cancel`) and must remain narrower than the public CLI.

## Current runtime

GO12 packages a generated ARM64 Go protocol-v1 runtime alongside the existing Chaquopy engine. The Go executable is app-owned (`nativeLibraryDir/libbkxgo.so`) and is launched directly without a shell. The explicit cutover seal remains disabled by default, so production continues to use Chaquopy; privileged filesystem work still routes through the libsu RootService and Python until the root Go lane is separately proven. The production graph must not execute arbitrary root shell commands or depend on Termux `RUN_COMMAND`.

## Stable boundaries

- UI code builds typed requests and renders typed results; it does not implement backup/archive algorithms.
- `BridgeRequestEnvelope`, result, progress, and cancellation envelopes are the runtime migration seam.
- Root access is capability-scoped and request routing stays pinned for the lifetime of an active request.
- Shared-storage, direct/root filesystem, removable-volume, and provider-staged SAF paths are explicit product paths.
- Restore policy is fail-closed for protected Android roots, traversal/link members, and root-sensitive destinations.

The runtime implementation may change (for example, to Go) without changing protocol-v1 semantics.
## Go runtime migration status

The frozen compatibility bundle remains under `migration/contracts/v1/`. GO12 now packages the Go bridge runtime and gives Kotlin a protocol-v1 process transport, but `GoRuntimeCutoverPolicy.ENABLED_BY_DEFAULT=false` preserves the Python production contract. ARM64 instrumentation is the acceptance path for the packaged Go runtime before a later phase enables it for normal traffic.

