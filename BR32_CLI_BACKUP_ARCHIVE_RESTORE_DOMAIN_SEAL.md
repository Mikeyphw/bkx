# BR32 — CLI Backup, Archive and Restore Domain Seal

Overlay 12 of 16 in the BKX deduped remediation roadmap.

## Scope

BR32 closes the CLI-facing destructive-domain findings that remained after the Android/runtime control-plane seals:

- Backup plan/create parity for Bakery and Restic engines.
- Backup plan proof now uses the same source manifest, include/exclude filters, destination requirement and split-size validation as execution.
- Archive create dry-run now resolves compression the same way execution does and surfaces explicit extension/algorithm contradictions.
- Archive publication uses no-replace multipart finalization and bounds split sizes/part counts.
- Archive list/info/browse/verify now have paging/resource-budget guards, duplicate normalized member detection, and stronger created-at/checksum semantics.
- Restore dry-run now uses real archive preview data instead of selector counts.
- Restore extraction enforces capacity/source-overlap checks, fresh-dir collision resistance, durable merge journaling, symlink rechecks, bounded result paths, and cleanup warnings.

## Deferred validation

This is an intermediate overlay and should be applied with `--no-validate`. The future gate task is:

```text
:app:verifyBkxCliBackupArchiveRestoreDomainSeal
```

Recommended Go validation for the gate overlay:

```text
go test ./internal/validation ./internal/backupops ./internal/archiveio ./internal/publiccli ./cmd/bkx
```

## Notes

The sandbox compile pass used a local temporary zstd shim because network access to `github.com/klauspost/compress` is blocked here. The repository overlay itself keeps the real `github.com/klauspost/compress` dependency unchanged.
