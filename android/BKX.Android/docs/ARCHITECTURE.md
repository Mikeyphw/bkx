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
