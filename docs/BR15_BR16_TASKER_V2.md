# BR15+BR16 — Tasker V2

BKX Tasker V2 makes Tasker a first-class client of the same Saved Job, repository, snapshot, run, and progress contracts used by the CLI and Android app. It extends the existing Locale-compatible BKX Action and Tasker Event/State plugins without removing the v1 actions or variables.

## Actions

The BKX Action editor keeps the existing backup, restore, verify, archive, transfer, cancel, and status operations and adds:

- **Run saved job** — executes a shared revisioned BKX job by `job_id`.
- **Create snapshot** — creates a Restic snapshot in a named repository.
- **Check repository** — executes the repository integrity check.
- **Apply retention** — dry-run by default; a real run requires explicit approval saved in the Tasker configuration.
- **Prune repository** — dry-run by default; a real prune requires explicit approval.
- **Restore snapshot** — dry-run by default, overwrite defaults to `never`, and a real restore requires explicit approval.

Tasker configuration reads the shared non-secret BKX control-plane catalog so job and repository IDs shown in Android/CLI are visible while editing. It does not read or duplicate repository password material, OAuth tokens, or rclone secrets.

## Events

Existing events remain supported. V2 adds:

- Saved job started/completed/failed/cancelled
- Snapshot created
- Repository check passed/failed
- Repository needs attention
- Retention applied
- Repository pruned

Events can be filtered by operation, request ID, remote, error code, saved-job ID, repository ID, and snapshot ID. Progress events retain the existing cadence controls so Tasker is not woken for every progress tick.

## States

Existing states remain supported. V2 adds:

- Saved job running
- Repository healthy
- Repository needs attention
- Last saved job succeeded/failed/cancelled

Repository health is updated by explicit BKX repository checks. It is not inferred solely from a successful backup.

## Identity and output variables

V2 adds these variables to both action results and event/state output where applicable:

- `%bkx_job_id`
- `%bkx_run_id`
- `%bkx_repository_id`
- `%bkx_snapshot_id`

Existing variables stay available for backward compatibility.

## Safety

Destructive repository maintenance never becomes implicit. Retention application and prune are previews by default and require an explicit saved approval for a real Tasker run. Snapshot restore defaults to preview mode and `overwrite=never`; a real restore also requires explicit approval. `forget` and `prune` remain separate operations in BKX.

## Compatibility

Existing Tasker action wire values, FIRE_SETTING integration, Event/State activities, and legacy variables are retained. V2 operations are additive; existing profiles do not need to be recreated solely because this overlay is installed.
