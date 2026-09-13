# BR24 — Backup, Archive and Restore Correctness Seal

This overlay repairs the direct backup/archive/restore workflows after the runtime, bridge, storage and root-safety base overlays.

## Scope

- Backup create requests now require the `backup.plan` request id and deterministic plan token that match the current executable plan.
- Restore extract requests now require the `restore.preview` request id and deterministic preview token before writing files.
- Confirmed destructive restores now send accepted confirmation evidence and clear `confirm_overwrite`; `confirm_overwrite=true` is treated as confirmation still pending.
- Restore overwrite/root-sensitive writes require explicit confirmation evidence tied to the preview token.
- Archive browse/info/verify results carry the selected archive identity in artifact metadata so stale read-only results can be rejected by consumers.
- Archive browse page tokens are numeric offsets only, matching the Go dispatcher parser.
- BR23 syntax cleanup is folded forward in Go files so gate validation has a parseable baseline.

## Deferred validation

This is an intermediate overlay and should be applied with `--no-validate`. The new gate task is:

```
:app:verifyBkxBackupArchiveRestoreCorrectnessSeal
```

Recommended future gate bundle:

```
:app:verifyBkxBackupArchiveRestoreCorrectnessSeal
:app:testDebugUnitTest
:app:lintDebug
```
