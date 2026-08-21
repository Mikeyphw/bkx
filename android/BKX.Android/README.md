# BKX Android

BKX Android **1.0.0** is a standalone Material 3 application backed by the canonical Python BKX engine.

```text
Material 3 phone/tablet UI
  -> process-scoped BKX operation coordinator
  -> EmbeddedBkxBackend
  -> RootAwarePythonGateway
       |-> libsu RootService (UID 0) -> Chaquopy -> bkx.android_api -> bkx.core
       `-> Chaquopy app process      -> bkx.android_api -> bkx.core
```

Primary navigation: **Home · Backup · Archives · Activity · More**. Restore is contextual to Archives; local transfer is under More.

## Storage and root

The personal build supports Android All files access. With a compatible root-manager grant, BKX's embedded engine itself runs in a libsu RootService and can directly access private filesystem locations, including Termux:

- `/data/data/com.termux/files`
- `/data/data/com.termux/files/home`
- `/data/data/com.termux/files/usr`

Termux is never used to execute BKX. There is no RUN_COMMAND permission, wrapper process, or generic root-shell API.

## Release safety

- Long jobs survive Activity/ViewModel recreation through an application-scoped coordinator and foreground service.
- Process-death leftovers are marked interrupted rather than successful.
- Backup/verify/restore/transfer publish cooperative progress; supported long loops honor cancellation checkpoints.
- Android backup skips symlinks rather than following them.
- Restore requires an exact destination; sensitive root writes require `RESTORE ROOT`; protected Android system roots are blocked.
- Operation history is bounded.
- A direct-core/embedded parity script is part of the Gradle release seal.
- Rooted physical-device qualification is available through `scripts/qualify-device.sh`.

See `docs/AOV_25_32_AUDIT.md` and `docs/FINAL_RELEASE_VALIDATION.md`.
