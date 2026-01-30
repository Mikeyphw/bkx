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

