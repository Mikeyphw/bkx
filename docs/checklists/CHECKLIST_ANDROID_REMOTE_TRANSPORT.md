# Android remote transport acceptance checklist

OV4 is accepted only when all applicable items below are true.

- [ ] Official Android ARM64 rclone runtime is pinned to v1.75.1 and never substituted with a Linux build.
- [ ] Runtime installer validates gzip, ELF64, AArch64, Android/arm64 metadata and exact version identity; optional SHA-256 pinning is supported for reproducible builds.
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

- [ ] Installer reports fetch/cache, gzip, ELF/version-marker, Go build-info, and execution-smoke stages.
- [ ] Android/Termux validation reaches and passes the executable `rclone version` smoke test.
- [ ] Runtime identity does not rely on the formatted `rclone v...` phrase being contiguous in raw ELF strings.
- [ ] A corrupt cached gzip is removed and fetched once again before failing closed.
