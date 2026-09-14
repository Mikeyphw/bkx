# BR33 — CLI Repository, Snapshot, Transfer and Jobs Domain Seal

Overlay 13 of 16 in the BKX deduped remediation roadmap.

## Scope

BR33 closes the remaining CLI-domain findings around repository control, Restic snapshot operations, transfer/rclone execution, saved jobs, schedules, and run history:

- Repository remove now refuses active saved-job/schedule dependents unless `--force` is explicit, and missing repositories fail before dependency side effects.
- Repository init validates credential refs before acquiring execution leases and cleans up newly-created empty parent directories after failed initialization.
- Repository check/prune reject incompatible Restic maintenance options before launching Restic.
- Restic snapshot list/browse/diff are bounded and truncation-aware, with timestamp sorting and exact-ID restoration semantics preserved.
- Snapshot restore and Restic restore normalize include/exclude filters before execution.
- Snapshot forget dry-run resolves and reports the explicit target without executing destructive Restic mutation.
- Retention accepts native Restic grouping (`host`, `tags`, `none`), untagged keep policies, validated duration syntax, and dry-run set/clear preview behavior.
- Transfer adds explicit `transfer sync`, delete-ceiling support, duplicate target collision rejection, CLI execution leases, cancellation propagation, and paginated run-history reporting.
- Saved jobs now reject non-executable control-plane mutations, support safe export/import, reject secret override keys, expose revision history, and preserve clear-tags/clear-credential semantics.
- Schedules get revision fencing, side-effect-free reads, disabled-job execution guards, preserved partial updates, and enabled/disabled CAS updates.
- Run history list now uses store pagination and returns `next_page_token`.

## Deferred validation

This is an intermediate overlay and should be applied with `--no-validate`. The future gate task is:

```text
:app:verifyBkxCliRepositorySnapshotTransferJobsDomainSeal
```

Recommended Go validation for the gate overlay:

```text
go test ./internal/models ./internal/schedulestore ./internal/runstore ./internal/repositorystore ./internal/resticops ./internal/rcloneops ./internal/transferops ./internal/publiccli ./cmd/bkx
```

## Notes

The sandbox compile pass used a local temporary zstd shim because network access to `github.com/klauspost/compress` is blocked here. The repository overlay itself keeps the real `github.com/klauspost/compress` dependency unchanged.
