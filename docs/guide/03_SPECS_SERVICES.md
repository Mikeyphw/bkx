# Specs + Services boundaries

Core rule: **interfaces build Specs; services execute Specs**.

## Specs (examples)
Keep Specs:
- immutable / frozen
- normalized in one place
- hashable (optional, helps parity tests)

### BackupSpec
Fields you’ll likely want (based on FEATURES.md):
- sources: list[path]
- excludes/includes
- destination (explicit)
- archive_name strategy
- split policy
- compression profile
- verify: bool
- dry_run/plan

### ArchiveCreateSpec
- sources, excludes
- tmp_dir
- split
- profile

### TransferSpec
- files
- destination
- verify

### RestoreSpec
- archive
- dest
- subset paths (optional)
- overwrite/force

## Services (examples)
### BackupService
- orchestrates archive → transfer → verify
- returns BackupResult with step results + recap

### ArchiveService
- create/list/info/browse/verify

### TransferService
- transfer/push/pull

### RestoreService
- extract/pick subset

## Ports
Ports must isolate optional tools:
- ArchiverPort (tar/zstd/pure fallback)
- TransferPort (rsync/rclone/fallback copy)
- HasherPort
- ToolDetectorPort
- FS abstraction (optional)

## Results
Return structured Results, never strings:
- counts
- bytes
- durations
- produced paths
- warnings

## Typed errors
Keep a small stable set, with details:
- ValidationError → exit 2
- NotFoundError → exit 3
- PermissionDenied → exit 4
- ExternalToolError → exit 5
- CoreError fallback → exit 1

