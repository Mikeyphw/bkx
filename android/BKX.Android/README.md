# BKX Android

BKX Android **1.0.0** is a standalone Material 3 application whose production engine is the packaged protocol-v1 Go runtime. GO14 removes Chaquopy and the embedded Python fallback from the Android APK entirely.

```text
Material 3 phone/tablet UI
  -> process-scoped BKX operation coordinator
  -> EmbeddedBkxBackend (protocol-v1 envelope adapter)
  -> RootAwareGateway
       |-> local app process -> GoNativeGateway -> app-owned libbkxgo.so
       `-> libsu RootService (UID 0) -> GoNativeGateway -> same app-owned libbkxgo.so
```

The Android artifact is intentionally **arm64-v8a-only**. Stock Go cannot produce the Android/amd64 executable used by this process transport with the same CGO-free build contract, and GO14 does not reintroduce a host-NDK/CGo dependency merely to retain an emulator fallback.

Primary navigation: **Home · Backup · Archives · Activity · More**. Restore is contextual to Archives; local transfer is under More.

## Storage and root

The personal build supports Android All files access. With a compatible root-manager grant, BKX launches the same packaged Go engine in a libsu RootService and can directly access private filesystem locations, including Termux:

- `/data/data/com.termux/files`
- `/data/data/com.termux/files/home`
- `/data/data/com.termux/files/usr`

Termux is never used to execute BKX. There is no RUN_COMMAND permission, wrapper process, generic root-shell API, or Python runtime dependency.

## Toolchain

- compileSdk / targetSdk: 36
- Build Tools: 36.0.0
- Java: 21
- Gradle: 9.7.1+
- Android NDK: **29.0.14206865** with a hard **r29+** validation floor
- packaged runtime ABI: **arm64-v8a**

## Release safety

- Long jobs survive Activity/ViewModel recreation through an application-scoped coordinator and foreground service.
- Process-death leftovers are marked interrupted rather than successful.
- Backup/verify/restore/transfer publish cooperative progress; supported long loops honor cancellation checkpoints.
- Android backup skips symlinks rather than following them.
- Restore requires an exact destination; sensitive root writes require `RESTORE ROOT`; protected Android system roots are blocked.
- Operation history is bounded.
- Python↔Go parity remains a repository migration/reference gate, but Python is not packaged into Android.
- Rooted physical-device qualification is available through `scripts/qualify-device.sh`.

See `docs/ARCHITECTURE.md`, `docs/RELEASE_CONTRACT.md`, and `docs/RELEASE_VALIDATION.md`.
