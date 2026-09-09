# Android remote transport acceptance checklist

OV4 is accepted only when all applicable items below are true.

- [ ] rclone is pinned to v1.75.1 and built from the official vendored source archive; the upstream 4 KiB Android testbuild and Linux binaries are rejected.
- [ ] Both official release inputs are mandatory: source tarball SHA-256 `6ada25ec...e13f` and vendor tarball SHA-256 `a43cd21f...b93b`, pinned to the signed v1.75.1 release manifest.
- [ ] The vendor archive is dependency-only (`vendor/`); it must be merged into the verified source archive before `go build -mod=vendor`.
- [ ] Runtime build uses Android/arm64 CGO plus explicit 16 KiB linker alignment, preferring the Gradle-selected NDK r29 toolchain.
- [ ] Runtime installer validates ELF64, AArch64, Android/arm64/CGO metadata, exact version identity, and every `PT_LOAD` for 16 KiB compatibility.
- [ ] Final debug/release APKs pass `verifyBkx16KbNativePageSizeContract` for every packaged native ELF.
- [ ] Android-host validation executes `rclone version` before packaging.
- [ ] `GoNativeGateway` passes only app-private config path and packaged runtime path to the Go engine.
- [ ] `rclone.conf` import is bounded, staged, owner-only, and rejects encrypted config until a password lifecycle exists.
- [ ] Parsed UI models expose only remote names and backend types.
- [ ] `rclone://REMOTE/path` validation rejects unsafe remote names and credentials are never encoded in the URI.
- [ ] Existing protocol-v1 filesystem operations browse remote paths without adding a duplicate protocol surface.
- [ ] Push, pull, and mirror topology rules are validated in the Go core before execution.
- [ ] Mirror requires explicit destination-deletion approval.
- [ ] Single-file transfer preserves basename with `copyto`.
- [ ] Progress carries file and byte totals through the existing BKX operation lifecycle.
- [ ] Cancellation terminates active rclone work.
- [ ] Verification runs after transfer when requested and mirror verification is two-way.
- [ ] rclone diagnostics redact config paths and common secret-bearing keys.
- [ ] Android transfer UI is location-first; raw paths remain behind Advanced.
- [ ] Remote browsing is available only where the selection target explicitly allows remotes.
- [ ] API-24 notification-settings fallback is lint-safe.
- [ ] Backup destination-space estimation uses `StorageManager#getAllocatableBytes` on API 26+ with `StatFs` fallback.
- [ ] Focused Go tests/vet, Android contract tests, lint, and the packaged runtime smoke test pass.

### Runtime installer diagnostics

- [ ] Installer reports verified-source fetch/cache, compiler/linker probe, source build, ELF/page-size, Go build-info, and execution-smoke stages.
- [ ] Android/Termux validation reaches and passes the executable `rclone version` smoke test.
- [ ] Runtime identity does not rely on the formatted `rclone v...` phrase being contiguous in raw ELF strings.
- [ ] A cached source archive with the wrong SHA-256 is removed and fetched again before failing closed.
- [ ] A cached runtime is reused only after the full 16 KiB + identity contract passes.

- [ ] Native Termux rclone source builds use probe-driven compiler selection: failed NDK-direct/Bash-shim lanes continue to native Termux clang with the NDK r29 sysroot, and only a lane that passes the real Go/cgo + 16 KiB probe may build rclone.
