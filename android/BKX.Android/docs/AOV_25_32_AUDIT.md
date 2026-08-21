# BKX Android AOV-25 → AOV-32 final release audit

Base: AOV-21→24 product UI, applied as commit `66eed1c2`.

## AOV-25 — failure and recovery hardening

- Long embedded operations are owned by process-scoped `BkxOperationCoordinator`, not `viewModelScope`.
- Foreground data-sync service accompanies backup/create, archive verification, restore extraction, and transfer execution.
- Long foreground operations serialize their foreground-service lifecycle.
- Persisted work which survives a process death without a trustworthy final result is marked **Interrupted**, never successful.
- Operation persistence is bounded to 64 requests / 200 progress events per request.
- Root policy is read for each new operation. Active requests pin their chosen root/local route so settings changes cannot redirect cancellation/progress to another runtime.
- Embedded Python publishes sequenced progress and supports cooperative cancellation checkpoints.

## AOV-26 — restore safety seal

- Both restore modes require an explicit absolute destination.
- **New folder** refuses an already-existing target.
- **Existing folder** requires an existing directory.
- Protected Android roots (`/proc`, `/sys`, `/dev`, system partitions, `/metadata`, and sensitive Android system-state directories) are blocked in Kotlin and Python.
- App-private/root-sensitive destinations require actual UID 0 and exact `RESTORE ROOT` confirmation for execution.
- Archive traversal, link, special-file, and existing-destination symlink protections remain in `bkx.core`.

## AOV-27 — performance and progress

- Result envelopes include measured `duration_ms`.
- Backup, archive verification, restore verification/extraction, and local transfer publish cooperative progress.
- Source traversal for Android backup safely skips symlinks instead of following them. This allows Termux-home backup without recursing through `~/storage` links into shared storage.
- Runtime history and progress are bounded to prevent unlimited SharedPreferences growth.

## AOV-28 — Android UX hardening

- Material 3 phone bottom navigation and tablet/wide navigation rail remain sealed.
- Restore path semantics are shown directly in the UI.
- The misleading “Follow symbolic links” switch is removed; the product truthfully explains that links are skipped.
- Notification reconnect routes to Activity and process recreation reconnects to persisted operation state.

## AOV-29 — embedded/core parity seal

`android/BKX.Android/scripts/verify-embedded-parity.py` performs a stdlib-only parity run against canonical `bkx.core`:

- backup plan token parity;
- real backup creation;
- archive verification;
- restore preview/extraction;
- local transfer;
- result duration metrics;
- final progress state.

Gradle task: `:app:verifyBkxEmbeddedParitySeal`.

## AOV-30 — source/dependency hygiene

`:app:verifyBkxReleaseHygiene` rejects production recurrence of:

- Termux `RUN_COMMAND` execution;
- legacy Termux bridge construction or serialized `termux_run_command` backend settings;
- generic `ProcessBuilder`, `su -c`, Python `eval`/`exec` at the Android boundary;
- committed APK/pyc/keystore material outside generated build trees;
- source-tree `__pycache__` directories.

Root remains a structured libsu `RootService` transport to the canonical BKX API, never a generic root shell endpoint.

## AOV-31 — physical-device qualification

`app/src/androidTest/.../ReleaseQualificationInstrumentationTest.kt` is opt-in with `bkxDeviceQualification=true` and proves, on a real authorized device:

- All files access is active;
- the embedded backend is actually running as root;
- `/data/data/com.termux/files` is readable directly through BKX root filesystem APIs;
- switching root policy to Disabled immediately routes readiness to the normal embedded non-root runtime.

Run `scripts/qualify-device.sh` after installing the debug/test APKs and granting BKX root in the device root manager.

## AOV-32 — final seal

- Version: `1.0.0` / versionCode `10000`.
- Final Devtool preflight includes runtime, restore, performance/progress, UX, parity, hygiene, device-qualification-contract, manifest, signing, docs, and final-version gates.
- `:app:bkxAndroidReleaseSeal` depends on all final gates plus lint, unit tests, connected instrumentation, debug assembly, and release assembly.
- SDK/toolchain remains API 36 / Build Tools 36.0.0 / Java 21 / Gradle 9.7.1+ / AGP 9.2.1 / Chaquopy 17 / host-matched Python 3.13/3.14 / libsu 6.0.0.
- libsu 6.0.0 resolves through the official JitPack publishing lane; settings.gradle.kts includes a repository content filter restricted to `com.github.topjohnwu.libsu`, and the SDK/dependency contract fails closed if it is removed.

## Final architectural assertion

BKX Android is standalone. Termux is a root-accessible filesystem target, not an execution dependency. `bkx.core` remains the canonical backup/archive/restore/transfer engine. Android owns lifecycle, root authorization, storage permissions, foreground execution presentation, persistence, and Material 3 UX.


## Final completeness replay — v2 corrective seal

The complete overlay chain was reapplied from the original BKX snapshot with source-hash verification at every boundary, then the resulting 1.0.0 tree was audited against the AOV-13→32 roadmap instead of relying on milestone audit claims. The replay was reproducible, but it exposed functional omissions in the first final-seal artifact. This v2 seal corrects them.

| Roadmap promise | Replay finding | v2 correction |
|---|---|---|
| Direct path browser | Canonical `filesystem.list` existed but had no product caller | Root-aware reusable filesystem browser wired to Backup, Archives/Restore, and Transfer |
| SD/USB discovery | Discovery abstraction was not used by product UI | `StorageManager.storageVolumes` + compatibility discovery drive quick locations and archive roots |
| SAF fallback | Adapter persisted metadata only; no provider bytes reached BKX | Bounded streaming `content://` document/tree staging for backup/archive/transfer inputs |
| Archive index | `archive.list` was still the temporary run-history projection | Bounded direct `<volume>/BKX` scan merged with canonical history, with size/mtime metadata |
| Archive Link/Special filters | UI filters existed but core browse collapsed non-directory entries to File | Canonical `ArchiveEntry.entry_type` classifies tar/ZIP file/directory/symlink/special entries and Android maps distinct labels |
| Live progress | UI refreshed mainly around operation boundaries | process-scoped `StateFlow` is observed continuously; progress polling and FGS notifications refresh live |
| Product cancellation | cooperative backend cancellation existed without a normal Activity action | active operations expose **Cancel operation** routed through the coordinator |
| Privacy/progress settings | settings persisted but did not materially affect runtime | privacy shapes Activity/notification detail; progress preference controls polling cadence |
| Notification denial / FGS timeout | foreground service could stop itself when notifications were denied, and timeout could leave work unprotected | long-running foreground protection continues without notification permission; Android data-sync timeout routes through cooperative cancellation |
| Large-tree profile | promised but absent | mandatory 768-file nested-tree time/memory/symlink profile |
| Accessibility/device visuals | promised but absent | Compose click/display assertions plus five opt-in physical-device screenshots collected by `qualify-device.sh` |
| SAF entry bounds | file-only counter allowed directory-only expansion | provider staging now caps total entries as well as depth |

The v2 Devtool preflight therefore additionally requires `verifyBkxLargeTreePerformanceSeal`, `verifyBkxStorageCompletenessContract`, and `verifyBkxAccessibilityVisualContract`.


## V5 Chaquopy build-Python portability repair

Final device validation exposed that Chaquopy 17's build-time interpreter must
have the same Python major/minor as the packaged runtime. The previous hard-coded
Python 3.13 runtime failed on Termux installations whose `python3` had advanced to
3.14. V5 resolves a supported build interpreter explicitly, preferring Termux's
`/data/data/com.termux/files/usr/bin/python3`, and selects the matching Chaquopy
runtime from Python 3.13 or 3.14. Builds may override the executable with
`-Pbkx.buildPython=/path/to/python` or `BKX_BUILD_PYTHON`. The embedded-engine and
SDK dependency gates fail closed if this host/runtime match is lost.
