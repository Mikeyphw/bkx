# BKX Android

BKX Android is a standalone Android frontend for the canonical Python BKX engine.

Current milestone: **AOV-17→20 root-native storage/runtime cutover**.

## Runtime

```text
Compose / Android lifecycle
  -> BackendPort
  -> EmbeddedBkxBackend
  -> RootAwarePythonGateway
       |-> libsu RootService -> Chaquopy -> bkx.android_api -> bkx.core
       `-> Chaquopy -> bkx.android_api -> bkx.core
```

Root is preferred by default but optional. If a compatible root manager grants access, the embedded BKX engine itself runs as UID 0. If root is unavailable, BKX falls back to the normal embedded engine.

No Termux execution bridge is used. There is no RUN_COMMAND permission, `allow-external-apps` requirement, wrapper process, or arbitrary root-shell API.

## Storage

The personal build declares Android All files access for direct shared-storage operation. With root granted, BKX can also access protected filesystem paths directly, including Termux private storage:

- `/data/data/com.termux/files`
- `/data/data/com.termux/files/home`
- `/data/data/com.termux/files/home/storage`
- `/data/data/com.termux/files/usr`

The same root runtime can reach other root-readable paths such as `/data/user/0` and `/data/adb`. SAF remains a fallback for content providers rather than the primary local-file architecture.

## Canonical behavior

Android does not reimplement backup/archive/restore/transfer rules. `bkx.android_api` is a narrow structured boundary over `bkx.core`; it does not call the BKX CLI/TUI or accept arbitrary commands/Python expressions.

## Milestones

- AOV-01→12: Android companion contracts/lifecycle/safety foundation.
- AOV-13→16: embedded Chaquopy/CPython BKX engine cutover.
- AOV-17→20: root-native storage, All files access, Termux-private storage, Termux execution removal.
- AOV-21→24: full Material 3 product UI replacement.
- AOV-25→32: hardening, parity, performance, physical-device qualification, release seal.

See `docs/AOV_17_20_AUDIT.md`, `docs/SETUP_GUIDE.md`, and `docs/APPLY_WITH_DEVTOOL.md`.
