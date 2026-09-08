# Android rclone remote transport

BKX Android treats remote storage as a transport boundary rather than as archive storage internals. The Go engine remains responsible for transfer planning, validation, progress, cancellation, execution, and verification. Android owns runtime packaging, app-private rclone configuration, and user-facing remote selection.

## Location contract

Remote locations use the credential-free URI form `rclone://REMOTE/path`. Only remote names containing letters, numbers, dot, underscore, or dash are accepted. Credentials never appear in the URI, BKX run history, transfer plan artifacts, or UI diagnostics. Before invoking rclone, the Go adapter converts the URI to rclone's native `REMOTE:path` notation.

The existing protocol-v1 `filesystem.list`, `filesystem.stat`, `transfer.plan`, and `transfer.run` operations are reused. No parallel Android-only transfer protocol is introduced.

## Runtime packaging

Android packages the pinned official rclone Android ARM64 build for v1.75.1 as generated `arm64-v8a/libbkxrclone_exec.so`. The `.so` name is intentional: AGP extracts native libraries into `ApplicationInfo.nativeLibraryDir`, providing a stable executable path for `ProcessBuilder` without copying executables into writable app storage.

`scripts/install-rclone-android-runtime.sh` validates gzip integrity, ELF64/AArch64 identity, Go build metadata, Android/arm64 target metadata, exact rclone version identity, and performs an executable `rclone version` smoke test when the build host itself is Android. `BKX_RCLONE_ANDROID_SHA256` can additionally pin an independently obtained archive digest in controlled/reproducible build environments.

## Configuration ownership

BKX imports `rclone.conf` into `filesDir/bkx/rclone/rclone.conf` and restricts the file to owner read/write permissions. The Compose UI parses and displays only remote section names and backend types. It never returns option values such as OAuth tokens, passwords, client secrets, refresh tokens, or access keys.

Encrypted `RCLONE_ENCRYPT_V0:` configuration is rejected until BKX has an explicit secret-input lifecycle. Import is size-limited and published through a staged replacement so a failed import cannot truncate the previous working configuration.

## Transfer semantics

- **Send** (`push`) requires local sources and may target a remote.
- **Receive** (`pull`) may use remote sources and requires a local destination.
- **Mirror** (`sync`) requires exactly one directory source and an explicit deletion acknowledgement because destination-only entries may be removed.
- Single-file copy uses `rclone copyto` so the source basename is retained exactly.
- Directory copy uses `rclone copy`; multiple sources are placed under their source basenames.
- Mirror uses `rclone sync`.
- Copy verification uses `rclone check --download --one-way`; mirror verification uses a two-way `rclone check --download`.
- rclone JSON stats feed BKX protocol progress for files and bytes.
- BKX cancellation terminates the active rclone process through the command context.
- Diagnostic output is sanitized before it can become a BKX error or run-history message.

## Remote browsing

The normal BKX filesystem browser enters configured `rclone://` locations for remote-capable selectors. It uses the existing `filesystem.list` and `filesystem.stat` operations, so local and remote navigation share one UI and bridge contract. OV4 introduced this boundary for Transfer; OV5 reuses it for remote Backup destinations and Archive selection. Remote Restore does not write directly from rclone: the archive is first materialized into private staging and then passed to the existing restore safety engine.

## Capability contract

When the packaged runtime is executable, readiness exposes `remote.rclone`, `transfer.remote`, `transfer.sync`, `backup.remote`, `archive.remote`, `restore.remote`, and `cloud.google_drive`. When an app-private configuration is present, it also exposes `remote.rclone.configured`. Android gates remote actions with these capabilities rather than assuming runtime availability. The Google-specific capability means the engine can service the managed credential-free URI; actual account authorization remains Android-owned and must be refreshed before use.

## Android runtime identity validation

The Android CI artifact is built with rclone's official `-X github.com/rclone/rclone/fs.Version=<VERSION>` linker setting. The raw ELF therefore carries the bare linker value (for example `v1.75.1`); it is not valid to require the already-formatted CLI phrase `rclone v1.75.1` to appear as one contiguous raw string.

BKX validates the runtime in layers instead:

1. gzip integrity and optional operator-provided SHA-256 pin;
2. ELF64, little-endian, position-independent AArch64 identity;
3. embedded bare `v1.75.1` linker-value marker;
4. Go build metadata proving the rclone module plus `GOOS=android` and `GOARCH=arm64`;
5. on Android/Termux, execution of `rclone version`, requiring the exact `rclone v1.75.1`, `os/type: android`, and `os/arch: arm64` report.

The installer prints each validation stage so Devtool failures identify the rejected contract instead of surfacing only an opaque shell exit code.
