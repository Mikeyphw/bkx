# FEATURES.md mapping (optional)

Use this document to map your existing FEATURES.md to your new Specs/Services.

Suggested mapping:

## Archive Engine
- ArchiveCreateSpec → ArchiveService.create
- Archive browsing → ArchiveService.browse
- Verify → ArchiveService.verify

## Transfer Engine
- TransferSpec → TransferService.push/pull

## Restore Engine
- RestoreSpec → RestoreService.extract/pick

## Backup workflow
- BackupSpec → BackupService.execute

Tip: Keep port implementations modular so you can swap tar/zstd/rsync/rclone strategies.

## Remote/cloud transport
- Credential-free `rclone://REMOTE/path` → `internal/rcloneops` runtime/transfer/filesystem boundary.
- Remote archive acquisition → `internal/remotearchive` private staging.
- Google Drive authorization → Android `GoogleDriveAuthorizationManager` + BKX-managed `bkx-google-drive` rclone section.
- Google Drive backup/archive/restore reuse the existing Backup/Archive/Restore services; no parallel cloud operation protocol exists.
- Readiness capabilities (`remote.rclone`, `backup.remote`, `archive.remote`, `restore.remote`, `cloud.google_drive`) are the UI feature gates.
