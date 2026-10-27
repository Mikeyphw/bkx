# API-03 BKX consumer — root-only opt-in native Skybind rclone

**Baseline:** `c161dc4425ebc6af13e1ca49a042b3c5cc044c4c` clean source.

The default BKX Android product always retains its packaged binary (`BKX_RCLONE_PATH`), app-private `rclone.conf` (`BKX_RCLONE_CONFIG`) and independently approved R1 read-only socket. No Android permission, manifest, binding, signing or install behavior changes. Running as a non-root Android app NEVER gains Skybind's private config via this feature.

For a **root-authorized BKX CLI** only, set `BKX_RCLONE_PROVIDER=skybind` (and do not set `BKX_RCLONE_PATH`/`BKX_RCLONE_CONFIG`). Runtime selection then requires Skybind's root-owned, non-symlink module directory, its public `rclone` launcher, `skybind-rclone` alias and `skybind-rclone-engine`, and a private, nonempty `/data/adb/skybind/config/rclone.conf`. No PATH fallback, ambient config fallback, automatic privileges, private-config copy or R1 grant bypass occurs. The runtime passes `--config /data/adb/skybind/config/rclone.conf` to the native CLI, preserving existing transfer/repository/restic behavior within the authorized root process.

**Device acceptance:** on the real Android root principal, first inspect root-only execution, root-owned paths, config privacy and the installed Skybind release/engine manifest. Probe version and configured remotes without printing secrets. With independent operator approval, perform a disposable read-only `lsjson` and optionally bounded copy/restore using a throwaway remote prefix. Verify no BKX Android principal can invoke this root provider. Preserve BKX's R1 socket tests separately. Module compatibility and real-provider migration are not claimed by source tests.

**Deferred:** BKX non-root Android managed-mount API access needs a separately authenticated, identity-scoped, audited transport. Root-only CLI integration is not a substitute. Do not silently retire R1 or BKX's standalone rclone runtime until equivalent device qualification passes.

**Improvement ledger:** API03-BKX-01 — root/private-config sharing and app principal mismatch previously had no explicit opt-in contract. Source fix: provider switch, root-owner/path checks, no unsafe fallback, tests, Devtool source gate. Residual: device SELinux, config token races, full BKX app integration and consumer acceptance. Status: SOURCE IMPLEMENTED / HOSTED & DEVICE OPEN.

## Independent R2 module-activation audit

The initial root provider accepted binaries located in a disabled or pending-removal module because the files remained accessible directly under `/data/adb/modules`. R2 requires an ordinary root-owned `module.prop` with exactly one literal `id=skybind` field, rejects `disable` and `remove` markers without attempting repair, and preserves the existing no-fallback behavior. This guards a stale/unmounted module identity; installed binary signer/provenance, actual KernelSU visibility and SELinux are still separate device gates.

## R3 — host UID fixture isolation

Termux Devtool transaction `af27fac12cd44663ae2b5dc20e753ccf` compiled the BKX binary successfully but exposed `TestAPI03SkybindProviderIsRootOnlySourceBoundAndOptIn` failing because a synthetic `t.TempDir()` is owned by the ordinary Termux UID; passing `uid=0` to the resolver cannot change the filesystem owner. The fix supplies the fixture owner exclusively to an unexported test helper; production `resolveSkybindProvider` remains hard-pinned to UID 0 ownership. Tests additionally reject production resolution of synthetic non-root-owned files, unsafe directory permissions, inactive modules and malformed identity. Separately, the Devtool full suite logged `exec: WaitDelay expired before I/O complete` in other packages; this remains an independent investigation, not a pass and not a reason to skip tests.
