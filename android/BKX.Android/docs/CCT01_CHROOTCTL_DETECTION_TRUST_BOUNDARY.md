# CCT-01 — chrootctl detection and trust boundary

Roadmap position: **overlay 1 of 9**. Devtool target: **`bkx_android`**.

This overlay intentionally does **not** add chroot export/import execution yet. It freezes the trust/readiness boundary that later CCT overlays use.

## Reused BKX foundations

- Existing libsu `RootBkxServiceClient` / `BkxRootEngineService` remains the only privileged Android transport.
- Root preference (`Preferred`, `Required`, `Disabled`) remains authoritative.
- The AIDL surface stays JSON-only and does not accept arbitrary shell commands or executable paths.
- One process-scoped root client is shared by normal BKX privileged routing and the new chrootctl readiness port.

## Adapted chrootctl foundations

BKX adapts the installed-module/runtime checks from chrootctl's Android `RuntimeResolver`:

- active module: `/data/adb/modules/chrootctl-magisk`;
- `modules_update` trees are diagnostic-only and are never execution candidates; active and pending module paths are exposed separately;
- `module.prop` identity and minimum module versionCode 15;
- compatibility schema 3 and bridge protocol 1;
- sealed module package inventory, including the current `uninstall.sh` contract;
- sealed Go-native arm64 runtime inventory and ELF identity;
- root ownership, canonical path, no symlink, and no group/other-write trust checks;
- fixed `chrootctl app capabilities` probe with bounded capture and stable malformed-response handling;
- `arbitraryShell=false` must be proven;
- required chroot inventory/export/import/operation capabilities must be present.

BKX is an **external** chrootctl bridge consumer. It therefore does not require the compatibility manifest's `androidApp.applicationId` to equal the chrootctl companion app. Instead, module/runtime/package identity and the live protocol capability probe define compatibility.

## Deferred to CCT-02+

- typed request/query/operation bridge client;
- NDJSON operation events and cancellation;
- chroot inventory UI;
- export/import artifact handoff;
- Backup source/domain and Jobs integration.


## Overlay-target policy

CCT-01 changes only the Android companion and therefore targets `bkx_android`. Future CCT work that needs Go/backend and Android changes is split into separate Devtool overlays per target; a single overlay will not mix both target trees.
