# Android rclone remote transport

BKX Android treats remote storage as a transport boundary rather than as archive storage internals. The Go engine remains responsible for transfer planning, validation, progress, cancellation, execution, and verification. Android owns runtime packaging, app-private rclone configuration, and user-facing remote selection.

## Location contract

Remote locations use the credential-free URI form `rclone://REMOTE/path`. Only remote names containing letters, numbers, dot, underscore, or dash are accepted. Credentials never appear in the URI, BKX run history, transfer plan artifacts, or UI diagnostics. Before invoking rclone, the Go adapter converts the URI to rclone's native `REMOTE:path` notation.

The existing protocol-v1 `filesystem.list`, `filesystem.stat`, `transfer.plan`, and `transfer.run` operations are reused. No parallel Android-only transfer protocol is introduced.

## Runtime packaging

Android packages the **latest stable bclone GitHub release** as generated `arm64-v8a/libbkxrclone_exec.so`. The `.so` name is intentional: AGP extracts native libraries into `ApplicationInfo.nativeLibraryDir`, providing a stable executable path for `ProcessBuilder` without copying executables into writable app storage.

BKX does **not** package bclone's Linux ARM64 release asset or rclone's upstream Android testbuild. `scripts/install-rclone-android-runtime.sh` resolves `https://github.com/BenjiThatFoxGuy/bclone/releases/latest` at task execution time, resolves that release tag to its exact Git commit, fetches that exact tag, verifies the checkout still resolves to the same commit and preserves the `github.com/rclone/rclone` Go module identity, then builds Android/arm64 from source. Dependencies are resolved through the release's `go.mod`/`go.sum` and checked with `go mod verify`; the final build uses `-mod=readonly`, `CGO_ENABLED=1`, Android/arm64, PIE, the `android noselfupdate` tags, and explicit 16 KiB linker alignment. The build prefers the Gradle-selected NDK r29 toolchain; native Termux clang is an allowed Android/aarch64 fallback when the standard x86_64 NDK host tools cannot execute on ARM64.

Because `latest` is mutable external state, Gradle deliberately runs the release resolver on every runtime task invocation. The installer caches successful binaries by **resolved tag + exact commit**, so an unchanged latest release is reused without recompilation while a newly published release automatically gets a distinct cache entry and rebuild.

Before packaging, BKX verifies ELF64/AArch64/PIE identity, Go build metadata, `CGO_ENABLED=1`, the exact resolved Git revision with `vcs.modified=false`, exact rclone-compatible version identity, and every `PT_LOAD` segment's 16 KiB alignment/congruence. On Android hosts it also executes `rclone version`. A generated `bkx-rclone-runtime.properties` asset records provider, latest-release resolution policy, repository, tag, version, commit, source tree, Android API/architecture and page size for artifact/support diagnostics.

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

The source build sets rclone's linker-visible version from the dynamically resolved bclone release tag. BKX does not treat a mutable `latest` pointer as provenance: each invocation resolves the tag and exact commit first, and all subsequent fetch/build/cache validation uses that immutable commit for the duration of the build.

BKX validates the runtime in layers instead:

1. GitHub `releases/latest` resolves to a stable `vX.Y.Z` release tag;
2. that tag resolves to an exact Git commit and the fetched checkout must still match it;
3. the checkout must preserve the `github.com/rclone/rclone` module identity and remain unmodified;
4. Android/arm64 compiler proof plus an early 16 KiB linker probe;
5. ELF64, little-endian, position-independent AArch64 identity;
6. every `PT_LOAD` requires `p_align >= 16384` and file/vaddr congruence modulo 16384;
7. Go build metadata proves the rclone module, `GOOS=android`, `GOARCH=arm64`, `CGO_ENABLED=1`, the resolved `vcs.revision`, and `vcs.modified=false`;
8. on Android/Termux, execution of `rclone version` must report the dynamically resolved release version, `os/type: android`, and `os/arch: arm64`;
9. the generated provenance asset records the exact tag/commit/tree that produced the packaged runtime;
10. final debug/release APK scanning verifies every packaged native ELF through `verifyBkx16KbNativePageSizeContract`.

The installer prints each validation stage so Devtool failures identify the rejected contract instead of surfacing only an opaque shell exit code.

### Termux ARM64 CGO compiler lane

On native Termux, an NDK compiler wrapper can appear executable in an interactive shell even though Go/cgo later receives `ENOENT`. BKX therefore makes compiler selection probe-driven: each direct NDK wrapper, Termux-Bash wrapper, native-Termux-clang + NDK-r29-sysroot lane, and final native Termux clang lane is accepted only after it builds an actual Android/arm64 CGO PIE whose `PT_LOAD` segments pass the 16 KiB gate. A failed lane is logged and selection continues instead of aborting on the first superficially executable compiler.
