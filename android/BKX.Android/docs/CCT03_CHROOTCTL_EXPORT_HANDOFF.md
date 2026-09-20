# CCT-03 — chrootctl export orchestration and managed-artifact handoff

CCT-03 keeps archive semantics inside chrootctl and keeps BKX destination credentials inside BKX.

## Ownership boundary

1. BKX asks `chrootctl app preview` for `transfer.preview_export` without an archive path. chrootctl therefore selects its root-owned managed transfer directory.
2. BKX binds the returned preview proof to `transfer.export`. Passwords remain ephemeral and are an unbound secret field in chrootctl's preview contract.
3. A successful export must return an absolute managed artifact path and SHA-256.
4. BKX RootService performs an internal, fixed `artifacts.list` query. The requested path is copyable only when it is the exact registered, regular `chroot-export` under chrootctl's authoritative managed root and its current size matches the registry.
5. A dedicated `ParcelFileDescriptor` pipe copies only that verified artifact. The AIDL does not expose a generic privileged file-read, executable, argv, or shell facility.
6. The app writes the stream to `<external-files>/bkx/chrootctl-staging/<request>/...partial`, calculates SHA-256 while copying, fsyncs, compares the result with chrootctl's checksum, and atomically publishes the staged file.
7. BKX publishes the staged file with its existing `transfer.plan` -> `transfer.run` contract, carrying the same checksum and post-transfer verification. Local, managed Google Drive, and imported rclone destinations remain BKX-owned transports.

BKX does not copy its rclone configuration, Google Drive tokens, or other destination credentials into chrootctl. Direct chrootctl remote streaming is intentionally outside this first integration path.

## Failure and recovery

- A failed/blocked chrootctl export never reaches BKX publication.
- An interrupted operation can be reconciled using the durable operation identifier introduced in CCT-02.
- A short/growing/changed managed artifact fails the handoff and the partial BKX staging file is deleted.
- A checksum mismatch fails closed before `transfer.plan`.
- Once a verified BKX staging artifact exists, publication failure or coroutine cancellation retains it for explicit recovery.
- BKX deletes its staging copy only after `transfer.run` has reported success. If deletion itself fails, publication remains successful but the result truthfully reports the retained staging path.
- The original root-owned chrootctl artifact is intentionally retained under chrootctl's own managed-artifact TTL. CCT-03 does not bypass chrootctl lifecycle policy to delete it.

## Lifecycle scope

CCT-03 provides the orchestration/data boundary but does not yet claim that long native chrootctl exports survive every Android background/process lifecycle. Foreground/recovery product lifecycle integration is reserved for CCT-08. UI integration is reserved for CCT-05/CCT-06.


## Cancellation correction (v2)

Cancelling the Android coroutine while `chrootctl app run` is streaming is not treated as equivalent to cancelling the persistent chrootctl operation. `RootChrootctlBridgePort` now resolves the operation id learned from the stream/ledger and issues the fixed `app cancel <operationId>` bridge call inside `NonCancellable` before propagating coroutine cancellation. This prevents a native export from silently continuing after BKX has been cancelled. If cancellation occurs before chrootctl has issued an operation id, BKX still closes its stream and does not invent or replay an operation.
