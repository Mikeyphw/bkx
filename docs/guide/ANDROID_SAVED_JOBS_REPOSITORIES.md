# Android saved jobs and repositories

BR11+BR12 makes the Android Backup surface a client of the same durable saved-job and named-repository stores used by the on-device BKX CLI.

## Backup surface

The Backup destination has three sub-surfaces:

- **Backup now** keeps the existing one-shot Bakery workflow.
- **Jobs** lists, creates, edits, clones, plans, runs, and deletes V2 saved jobs through the embedded Go bridge.
- **Repositories** manages local Restic, rclone, and BKX-managed Google Drive repositories through the embedded Go bridge.

Android does not use its SharedPreferences compatibility store as the authoritative Jobs/Repositories product database. Bridge artifacts carry canonical V2 job and repository documents back to the UI.

## Shared on-device control plane

Android app-private HOME and the Termux HOME cannot directly see one another. When shared-storage access is available, the embedded runtime therefore receives:

```text
BKX_STATE_DIR=/storage/emulated/0/BKX/.state
```

The app creates `.bkx-state.json` in that directory. When the real Termux HOME sees that marker, the CLI resolves its saved-job and repository stores to the same shared root automatically. Temporary/test HOMEs do not auto-adopt the device store.

`bkx state status` reports the active root, resolution source, canonical jobs/repositories files, and legacy HOME-scoped locations. `BKX_STATE_DIR` remains an explicit override for desktop/custom layouts.

When a canonical shared document does not exist yet, BKX atomically seeds it from the prior HOME-scoped document and leaves the legacy source intact. If both documents already exist, BKX does not silently overwrite either side; explicit import/export reconciliation is required.

The shared directory is deliberately a **non-secret control plane**. It contains saved job definitions, named repository definitions, revision metadata, and the marker only. Restic passwords, Google OAuth tokens, rclone credentials/config, and other secret material remain in their existing private/runtime credential paths.

If Android shared-storage access is not granted, the embedded runtime remains on its app-private state root. Once access is granted, a subsequent runtime session switches to the shared root; the store seeding rule above preserves an existing private document when the shared destination is still empty.

## Secrets

Saved jobs and repository definitions store credential reference IDs only. The job editor rejects inline secret-shaped JSON keys through `OperationSpecV2`, and the repository editor accepts only a Restic password reference ID. Managed Google Drive OAuth material remains in the existing managed authorization/runtime path.

## Destructive maintenance

Repository removal deletes only the BKX definition. Retention application and Restic prune require a confirmation dialog. Prune also has a dry-run preview action and remains separate from retention/forget operations.

## Cross-client contract

With shared-storage access granted, a job created in Android is persisted in the same filesystem document consumed by `bkx job list` and `bkx job run` in Termux. A repository created in Android is likewise persisted in the same named-repository document consumed by CLI commands. Both sides use file locking and atomic replacement in the shared store.

Tasker will bind to these same IDs in BR15-BR17 rather than copying operation configuration into Tasker profiles.
