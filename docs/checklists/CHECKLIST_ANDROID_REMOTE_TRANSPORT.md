# Android remote transport acceptance checklist

OV4 is accepted only when all applicable items below are true.

- [ ] The runtime source tracks bclone's latest stable GitHub release; no fixed bclone/rclone version is embedded in the build policy.
- [ ] `releases/latest` is resolved at execution time to a stable tag and then to an exact Git commit; the fetched source must match that commit before compilation.
- [ ] The bclone checkout preserves the `github.com/rclone/rclone` Go module identity, remains unmodified, and dependencies pass `go mod verify`; builds use `-mod=readonly`.
- [ ] Linux ARM64 release binaries and upstream rclone Android testbuilds remain forbidden packaging inputs.
- [ ] Runtime cache identity includes the resolved bclone tag + commit, while the Gradle task always re-runs the latest-release resolver.
- [ ] The APK packages `bkx-rclone-runtime.properties` with the exact provider/tag/version/commit/tree used for the runtime build.
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
- [ ] A moved/re-resolved release tag or mismatched fetched commit fails closed before compilation.
- [ ] A cached runtime is reused only when its provenance matches the currently resolved latest tag + commit and the full 16 KiB + identity contract passes.

- [ ] Native Termux rclone source builds use probe-driven compiler selection: failed NDK-direct/Bash-shim lanes continue to native Termux clang with the NDK r29 sysroot, and only a lane that passes the real Go/cgo + 16 KiB probe may build rclone.
