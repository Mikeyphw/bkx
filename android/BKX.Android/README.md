# BKX Android

BKX Android is a standalone Material 3 Android application for the canonical Python BKX engine.

Current milestone: **AOV-21→24 product UI replacement**.

## Product architecture

```text
Material 3 phone/tablet UI
  -> BkxAppViewModel
  -> BackendPort
  -> EmbeddedBkxBackend
  -> RootAwarePythonGateway
       |-> libsu RootService -> Chaquopy -> bkx.android_api -> bkx.core
       `-> Chaquopy -> bkx.android_api -> bkx.core
```

The primary navigation is intentionally user-oriented:

- Home
- Backup
- Archives
- Activity
- More

Restore is contextual to an archive. Local transfer lives under More. Engine diagnostics are hidden under Advanced rather than occupying a primary destination.

## Root and storage

Root is preferred by default but optional. With a libsu-compatible root manager grant, the embedded BKX engine itself runs as UID 0 and can directly access protected paths, including Termux private storage:

- `/data/data/com.termux/files`
- `/data/data/com.termux/files/home`
- `/data/data/com.termux/files/home/storage`
- `/data/data/com.termux/files/usr`

The personal build also requests Android All files access for direct shared-storage work. SAF remains a provider fallback, not the normal local-file path.

No Termux execution bridge is used. There is no RUN_COMMAND permission, `allow-external-apps` requirement, wrapper process, or generic root-shell endpoint.

## AOV-21→24 product work

- Material 3 light/dark/dynamic-color design system.
- Phone bottom navigation and tablet/wide navigation rail.
- Home dashboard with engine/root state, active operation, and recent activity.
- Direct-path Backup workflow with review and create actions wired to embedded BKX.
- Archive index/manual path open, info, browse, verify, and contextual restore.
- Restore review and destructive overwrite confirmation without exposing plan tokens.
- Activity timeline with live work and privacy-preserving local history.
- Local transfer review/run UI.
- Persistent appearance, root policy, privacy, and progress preferences.
- Advanced engine diagnostics hidden outside the primary workflows.

## Milestones

- AOV-01→12: Android contracts/lifecycle/safety foundation.
- AOV-13→16: embedded Chaquopy/CPython BKX engine cutover.
- AOV-17→20: root-native storage, All files access, Termux-private storage, Termux execution removal.
- AOV-21→24: Material 3 product UI and real user workflow wiring.
- AOV-25→32: hardening, parity, performance, physical-device qualification, release seal.

See `docs/AOV_21_24_AUDIT.md`, `docs/SETUP_GUIDE.md`, and `docs/APPLY_WITH_DEVTOOL.md`.
