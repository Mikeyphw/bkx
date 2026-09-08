# BKX Android architecture

BKX Android is a Kotlin/Compose application over the protocol-v1 Go engine. The Android boundary is JSON (`readiness`, `execute`, `progress`, and `cancel`) and remains narrower than the public CLI.

## Current runtime

GO14 is **Go-only on Android**. Gradle generates an Android/AArch64 PIE executable named `libbkxgo.so`, registers its generated JNI directory through the AGP Variant API, and packages it as an extracted app-owned native library. `GoNativeGateway` launches only `ApplicationInfo.nativeLibraryDir/libbkxgo.so` directly; no shell or arbitrary executable path is exposed.

The local application process and the libsu UID-0 process launch the same binary. Their runtime state is namespaced separately (`go-local` / `go-root`) to avoid cross-UID ownership conflicts. The Android APK contains no Chaquopy plugin/runtime, embedded Python source package, Python gateway, or Python fallback selector.

The APK is intentionally `arm64-v8a` only. NDK `29.0.14206865` is pinned and validation rejects an NDK major below 29.

## Stable boundaries

- UI code builds typed requests and renders typed results; it does not implement backup/archive algorithms.
- `BridgeRequestEnvelope`, result, progress, and cancellation envelopes remain the runtime seam.
- `EmbeddedBkxBackend` is a transport adapter despite its historical name; it accepts only the `go-native-android` runtime identity in GO14.
- Root access is capability-scoped and request routing stays pinned for the lifetime of an active request.
- Shared-storage, direct/root filesystem, removable-volume, and provider-staged SAF paths are explicit product paths.
- Restore policy is fail-closed for protected Android roots, traversal/link members, and root-sensitive destinations.
- Human Go CLI commands remain a separate migration/cutover concern and are not exposed merely because Android runs Go.

The frozen compatibility bundle remains under `migration/contracts/v1/`. Historical Python↔Go parity fixtures continue to protect protocol behavior, but Python is no longer an Android runtime dependency.

## Remote and Google Drive boundary

Rclone is a separate optional Android ARM64 executable boundary; it is not the BKX engine. `GoNativeGateway` supplies its packaged executable path and BKX-owned app-private config path to the Go engine. Remote locations remain credential-free `rclone://REMOTE/path` values across protocol-v1, run history, and UI state.

Generic remote transfer/filesystem behavior lives in `internal/rcloneops`. Remote archives are acquired through `internal/remotearchive` into private temporary staging before existing archive/restore code runs. Remote backup creation remains a normal `backup.create`: BKX builds/verifies locally, publishes parts transactionally through rclone, then re-downloads the logical archive for final verification when requested.

Google authorization is Android-owned because it can require an interactive `PendingIntent`. `GoogleDriveAuthorizationManager` requests only `drive.file` and refreshes the short-lived access token immediately before managed Drive operations. The token is projected into the reserved `bkx-google-drive` section of the owner-only rclone config and never into bridge requests or product models. This keeps Google account consent outside the Go engine while all actual backup/archive/restore semantics remain in the shared Go services.
