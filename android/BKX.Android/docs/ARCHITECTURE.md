# BKX Android architecture

BKX Android is a Kotlin/Compose application over the same BKX engine used by the CLI.
The Android boundary is protocol-v1 JSON (`readiness`, `execute`, `progress`, and `cancel`) and must remain narrower than the public CLI.

## Current runtime

The current application embeds the Python BKX core with Chaquopy and can route operations through a libsu RootService when privileged filesystem access is required. The production graph must not execute arbitrary root shell commands or depend on Termux `RUN_COMMAND`.

## Stable boundaries

- UI code builds typed requests and renders typed results; it does not implement backup/archive algorithms.
- `BridgeRequestEnvelope`, result, progress, and cancellation envelopes are the runtime migration seam.
- Root access is capability-scoped and request routing stays pinned for the lifetime of an active request.
- Shared-storage, direct/root filesystem, removable-volume, and provider-staged SAF paths are explicit product paths.
- Restore policy is fail-closed for protected Android roots, traversal/link members, and root-sensitive destinations.

The runtime implementation may change (for example, to Go) without changing protocol-v1 semantics.
## GO00/GO01 migration status

The repository now contains a non-production Go scaffold under `cmd/bkx` and a
frozen compatibility bundle under `migration/contracts/v1/`. The scaffold is not
packaged into the Android app and cannot execute product operations. The current
Android runtime remains embedded CPython until later phases prove operation-level
parity against the frozen contracts. This preserves protocol-v1 and keeps the
Kotlin/Compose product layer independent from the backend-language migration.

