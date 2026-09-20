# CCT-05 — Chroot backup domain convergence

CCT-05 makes a chroot a first-class BKX backup source without representing its rootfs as a filesystem path.

## Frozen contracts

- `BackupSourceKind.Chroot` carries an opaque chrootctl chroot name and **no staged filesystem path**.
- `BackupEngineKind.Chrootctl` is the only valid engine for a chroot source.
- The native method requires a normal BKX destination, allowing the CCT-03 coordinator to publish to local, managed Drive, or rclone destinations.
- A filesystem source cannot select the chrootctl engine.
- `BackupRequestBuilder` and `toPlanPayload` reject the chrootctl engine, so a chroot can never leak into Go's filesystem `backup.plan`/`backup.create` contract.
- `ChrootBackupDomain.exportPlan()` is the single domain conversion from a valid backup draft to `ChrootctlExportPlan`.
- `ChrootBackupDomain.preview()` converts chrootctl's preview into BKX's existing preview state while retaining the native preview proof in the CCT-03 coordinator.

CCT-06 owns presentation: readiness, chroot inventory, source selection, forced native method copy, review, and progress UI.
