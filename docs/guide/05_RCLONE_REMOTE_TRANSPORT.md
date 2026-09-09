# Android rclone remote transport

BKX Android treats remote storage as a transport boundary rather than as archive storage internals. The Go engine remains responsible for transfer planning, validation, progress, cancellation, execution, and verification. Android owns runtime packaging, app-private rclone configuration, and user-facing remote selection.

## Location contract

Remote locations use the credential-free URI form `rclone://REMOTE/path`. Only remote names containing letters, numbers, dot, underscore, or dash are accepted. Credentials never appear in the URI, BKX run history, transfer plan artifacts, or UI diagnostics. Before invoking rclone, the Go adapter converts the URI to rclone's native `REMOTE:path` notation.

The existing protocol-v1 `filesystem.list`, `filesystem.stat`, `transfer.plan`, and `transfer.run` operations are reused. No parallel Android-only transfer protocol is introduced.

## Runtime packaging

Android packages rclone v1.75.1 as generated `arm64-v8a/libbkxrclone_exec.so`. The `.so` name is intentional: AGP extracts native libraries into `ApplicationInfo.nativeLibraryDir`, providing a stable executable path for `ProcessBuilder` without copying executables into writable app storage.

BKX does **not** package rclone's upstream Android testbuild directly. The v1.75.1 testbuild is linked with 4 KiB `PT_LOAD` alignment and triggers Android's 16 KiB memory-page compatibility mode. `scripts/install-rclone-android-runtime.sh` instead downloads both official release inputs: `rclone-v1.75.1.tar.gz` (the source tree) and `rclone-v1.75.1-vendor.tar.gz` (the dependency-only `vendor/` tree). It verifies both SHA-256 values from the signed upstream release manifest, merges `vendor/` into the source tree, and builds Android/arm64 offline with `-mod=vendor`, `CGO_ENABLED=1`, `CC`/`CC_FOR_TARGET`, the `android noselfupdate` tags used by Round Sync's proven Android rclone build shape, PIE, and explicit 16 KiB linker alignment. The build prefers the Gradle-selected NDK r29 toolchain; native Termux clang is an allowed Android/aarch64 fallback when the standard x86_64 NDK host tools cannot execute on ARM64.

Before packaging, BKX verifies ELF64/AArch64/PIE identity, Go build metadata, `CGO_ENABLED=1`, exact rclone version identity, and every `PT_LOAD` segment's 16 KiB alignment/congruence. On Android hosts it also executes `rclone version`. The successful runtime is cached outside the repository so later builds avoid recompiling rclone.

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

The source build retains rclone's release linker identity through `-X github.com/rclone/rclone/fs.Version=v1.75.1`. The raw ELF therefore carries the bare linker value; BKX does not rely on the already-formatted CLI phrase being contiguous in binary strings.

BKX validates the runtime in layers instead:

1. mandatory SHA-256 verification of the official vendored source archive;
2. Android/arm64 compiler proof plus an early 16 KiB linker probe;
3. ELF64, little-endian, position-independent AArch64 identity;
4. every `PT_LOAD` requires `p_align >= 16384` and file/vaddr congruence modulo 16384;
5. embedded bare `v1.75.1` linker-value marker;
6. Go build metadata proving the rclone module, `GOOS=android`, `GOARCH=arm64`, and `CGO_ENABLED=1`;
7. on Android/Termux, execution of `rclone version`, requiring the exact `rclone v1.75.1`, `os/type: android`, and `os/arch: arm64` report;
8. final debug/release APK scanning of every packaged native ELF through `verifyBkx16KbNativePageSizeContract`.

The installer prints each validation stage so Devtool failures identify the rejected contract instead of surfacing only an opaque shell exit code.

### Termux ARM64 CGO compiler lane

On native Termux, an NDK compiler wrapper can appear executable in an interactive shell even though Go/cgo later receives `ENOENT`. BKX therefore makes compiler selection probe-driven: each direct NDK wrapper, Termux-Bash wrapper, native-Termux-clang + NDK-r29-sysroot lane, and final native Termux clang lane is accepted only after it builds an actual Android/arm64 CGO PIE whose `PT_LOAD` segments pass the 16 KiB gate. A failed lane is logged and selection continues instead of aborting on the first superficially executable compiler.
