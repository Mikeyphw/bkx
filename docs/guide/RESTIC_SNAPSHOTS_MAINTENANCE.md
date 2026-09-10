# Restic snapshots and maintenance

BR09+BR10 makes the Restic repository lifecycle a first-class BKX surface. It is deliberately split into non-destructive inspection, restore, snapshot forgetting, retention, and physical pruning.

## Snapshot lifecycle

```sh
bkx snapshot list --repository-ref phone-drive
bkx snapshot show --repository-ref phone-drive latest
bkx snapshot diff --repository-ref phone-drive a1b2c3d4 e5f6a7b8 --metadata
bkx snapshot restore --repository-ref phone-drive latest --target "$HOME/Restore"
```

BKX defaults restores to `overwrite=never`. Use `--overwrite always`, `if-changed`, or `if-newer` only when replacement is intended. `--dry-run` is passed through to Restic restore.

Snapshot deletion is intentionally two-stage. `bkx snapshot forget ... --confirm` removes the snapshot record but never runs prune. `latest` is rejected for forget so an explicit snapshot ID must be reviewed.

## Repository health and pruning

```sh
bkx repository check phone-drive
bkx repository check phone-drive --read-data
bkx --dry-run repository prune phone-drive
bkx repository prune phone-drive --confirm
```

A normal check validates repository metadata. `--read-data` may retrieve the entire repository and can be expensive for cloud storage. `--read-data-subset` is available for bounded verification.

Prune is never coupled to backup or retention. A real prune requires `--confirm`; a global `--dry-run` does not.

## Retention

```sh
bkx retention set phone-drive \
  --keep-last 10 \
  --keep-daily 7 \
  --keep-weekly 4 \
  --keep-monthly 12

bkx --dry-run retention apply phone-drive
bkx retention apply phone-drive --confirm
bkx repository check phone-drive
bkx repository prune phone-drive --confirm
bkx repository check phone-drive
```

Retention policies are stored in the named repository definition. BKX rejects empty policies and zero-count rules. Applying retention invokes Restic `forget` only; pruning remains an explicit repository-maintenance operation.

## Android bridge

The embedded backend exposes additive post-v1 operations for snapshot list/show/diff/restore/forget, repository check/prune, and retention set/show/clear/apply. The frozen migration-v1 advertised operation set is unchanged. Android request builders default destructive surfaces to their safe forms (`overwrite=never`, prune dry-run, retention dry-run).
