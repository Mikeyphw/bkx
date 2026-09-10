# BR13 + BR14 — Android snapshot browser and restore UX

BR13+BR14 makes Restic snapshots first-class Android product surfaces while retaining the shared Go repository/snapshot implementation used by the CLI.

## Product flow

`Backup > Snapshots` selects one of the named Restic repositories, lists snapshots newest-first, and opens a snapshot browser backed by `snapshots.browse`. The browser uses Restic `ls --json`; it does not mount the repository or expose repository credentials to Compose.

Selecting no paths means restore the complete snapshot. Selecting one or more files/directories narrows restore to those paths. The restore target defaults to `/storage/emulated/0/BKX/Restores/<snapshot-short-id>`.

Overwrite defaults to `never`. The user must explicitly choose `if-changed`, `if-newer`, or `always`. A separate confirmation dialog is required before a real restore. Preview uses Restic dry-run and never changes files.

## Progress and cancellation

Restic restore JSON-lines status is streamed through the existing BKX operation progress contract. Android receives percentage, restored-file counts, and restored-byte counts while the operation is active. The existing cancellation channel is reused so the UI does not create a second process-control mechanism.

## Snapshot maintenance

The screen can compare the selected snapshot with its immediately older snapshot and can forget an explicitly selected snapshot. Forget never performs prune. Repository pruning remains in `Backup > Repositories` and retains its separate dry-run/confirmation workflow.

## Cross-client contract

The Android UI calls bridge operations (`snapshots.list`, `snapshots.browse`, `snapshots.diff`, `snapshots.restore`, `snapshots.forget`) backed by the same Go repository definitions and Restic implementation as the CLI. No shell command strings or password/token values are persisted by the UI.
