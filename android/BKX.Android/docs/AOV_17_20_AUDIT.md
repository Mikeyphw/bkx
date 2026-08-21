# AOV-17 → AOV-20 — Root-native storage and standalone runtime audit

## Result

AOV-17→20 makes the embedded BKX backend root-aware and removes Termux as an Android execution backend.

The canonical execution chain is now:

```text
Compose / ViewModel
  -> BackendPort
  -> EmbeddedBkxBackend
  -> RootAwarePythonGateway
       -> libsu RootService -> Chaquopy -> bkx.android_api -> bkx.core   (root granted)
       -> Chaquopy -> bkx.android_api -> bkx.core                       (fallback)
```

Root changes privilege only. It does not introduce a second backup/archive implementation.

## AOV-17 — root + direct storage

- libsu 6.0.0 core/service/io is pinned in the Android dependency catalog.
- `BkxRootEngineService` is a libsu `RootService` and refuses operation unless `Os.getuid() == 0`.
- Its Binder API is deliberately narrow: readiness JSON, BKX request JSON, cancellation JSON, and UID inspection only.
- No arbitrary shell, command-path, argument-array, `ProcessBuilder`, or `su -c` API is exposed.
- `MANAGE_EXTERNAL_STORAGE` is declared for the personal backup build.
- `AndroidStorageAccessManager` opens Android's All files access settings when needed.
- Root-aware first-class paths include:
  - `/data/data/com.termux/files`
  - `/data/data/com.termux/files/home`
  - `/data/data/com.termux/files/home/storage`
  - `/data/data/com.termux/files/usr`
  - `/data/adb`
  - `/data/user/0`
- `bkx.android_api` now exposes `storage.locations`, `filesystem.list`, and `filesystem.stat` over ordinary filesystem paths.
- When Python is running inside the root service it advertises `root.filesystem`, `storage.termux.private`, and `storage.app_private`.

## AOV-18 — execution routing and lifecycle

- Root mode is `Preferred` by default.
- `Preferred`: use root when granted; otherwise execute through the normal embedded Chaquopy runtime.
- `Required`: fail closed with `BackendReadiness.RootPermissionRequired` unless root is available.
- `Disabled`: never bind the root engine.
- Binder death, timeout, and stale service state invalidate the root connection.
- Mutating calls are never automatically retried after a Binder failure, preventing duplicate backup/restore/transfer execution.
- Existing foreground-operation/runtime persistence remains the Android lifecycle surface.

## AOV-19 — direct BKX feature path

The same embedded `bkx.android_api` operations introduced in AOV-13→16 now receive direct filesystem paths, including root-only paths when the root runtime is selected. Backup, archive, restore and local-transfer operations remain implemented by `bkx.core`.

The Python boundary accepts direct `path` fields while retaining the previous staged-path aliases for request-model compatibility during the UI migration.

## AOV-20 — Termux execution removal

Removed from production source:

- `AndroidTermuxReadinessProbe`
- `TermuxBridgeAdapter`
- `TermuxRunCommandContract`
- `TermuxRunCommandDispatcher`
- `TermuxRunCommandInvocationFactory`
- the Termux RUN_COMMAND manifest permission and query contract

Termux is now a **storage target only**. BKX does not need Termux installed in order to execute. If Termux is installed and root is granted, BKX can access its private files directly.

The obsolete `termux_run_command` serialized settings enum value is removed by the final release seal. Legacy execution vocabulary is not retained in the 1.0.0 production source.

## Security boundary

Root authorization comes from the device root manager through libsu. BKX deliberately does not expose a generic privileged shell API. The privileged process only accepts the structured BKX operation protocol.

## Validation gates

- `tests/test_android_root_storage_contract.py`
- `tests/android_api/test_embedded_android_api.py`
- Android root policy/unit tests
- `:app:verifyBkxRootStorageContract`
- `:app:verifyBkxEmbeddedEngineContract`

Full Android compile/lint/device validation remains deferred to the final release-seal overlay, per the overlay workflow.

## Final completeness replay correction

A replay of AOV-13→32 found that the first AOV-17→20 implementation had declared storage abstractions without fully wiring them into product behavior. The final release overlay closes those gaps while preserving this milestone's architecture:

- `StorageManager.storageVolumes` and the app external-directory fallback discover internal/removable SD/USB roots;
- the root-aware product filesystem browser executes canonical `filesystem.list` instead of requiring manual path typing;
- `archive.list` performs a bounded direct filesystem index over discovered `<volume>/BKX` roots and returns real size/mtime metadata instead of projecting only run history;
- provider-only `content://` backup/archive/transfer **inputs** stream into bounded app-private staging through `AndroidProviderStagingManager`; normal filesystem paths continue to bypass staging;
- provider tree staging enforces both depth and total-entry bounds.

Termux remains a root-accessible storage tree only; none of these corrections restore a Termux execution dependency.
