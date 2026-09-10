# Saved Jobs V2 foundation

BKX Saved Jobs are reusable, revisioned operation specifications shared by the CLI, Android app, embedded Go runtime, and automation clients such as Tasker.

## Contract

A saved job contains:

- `schema_version: 2`
- a stable `job_id`
- a human-readable name/description/tags
- a monotonic `revision`
- an enabled flag
- one `OperationSpecV2`
- creation/update timestamps

`OperationSpecV2` contains a stable operation id, JSON parameters, an optional `repository_ref`, and zero or more `credential_refs`.

BKX deliberately stores references to credentials rather than credential material. Passwords, passphrases, tokens, access/refresh tokens, API keys, authorization values, private keys, and credential fields are rejected when found inside operation parameters. Platform-specific secure storage resolves `credential_refs` only at execution time.

## Persistence and revisions

The Go core owns the canonical Saved Job V2 store. By default it resolves under `~/.local/share/bkx/jobs/jobs.json`; `BKX_STATE_DIR` can select an explicit control-plane root. On Android/Termux, once the app initializes `/storage/emulated/0/BKX/.state/.bkx-state.json`, both clients resolve jobs, repositories, schedules, execution control, and run history under that shared non-secret root. Writes are lock-protected, written to a private temporary file, fsynced, and atomically renamed.

The Android UI talks to the embedded Go bridge for canonical job CRUD/run/plan operations. The older SharedPreferences job store remains only as a compatibility/migration source; it is not a second authoritative catalog. Credentials, OAuth tokens, rclone configuration, and Restic passwords remain in private/secure storage rather than the shared control plane.

Creating a job produces revision 1. Updating requires the caller's expected current revision. A stale update fails closed instead of silently overwriting a newer editor. Prior revisions remain immutable and queryable until the job is deleted.

The V1 document shape migrates to V2 without embedding secrets. Legacy HOME-scoped control-plane documents are seeded non-destructively when the canonical shared document is absent. Run history is union-migrated by `run_id`, so unique historical app and Termux runs converge while legacy files remain intact.

## Intentional scope of BR01+BR02

This foundation does not yet expose `bkx job ...`, historical run replay, restic repositories, snapshot operations, Jobs UI, or Tasker `Run Saved Job`. Those are subsequent roadmap stages. This stage establishes the single data/persistence contract they must consume rather than allowing each client to invent a different representation.

## BR03+BR04 CLI surface

Saved jobs are now first-class CLI resources. The saved document is the
canonical operation specification, never a shell command.

```sh
bkx job create phone-backup \
  --name "Phone backup" \
  --operation backup.create \
  --params-json '{"sources":["/storage/emulated/0/Documents"],"destination":"rclone://drive/BKX","archive_name":"phone","excludes":[],"includes":[],"split_size":null,"compression":{"algorithm":"gzip","level":3},"skip_symlinks":false,"verify":true,"dry_run":false}'

bkx job list
bkx job show phone-backup
bkx job plan phone-backup
bkx job run phone-backup
bkx job run phone-backup --set verify=false
bkx job clone phone-backup phone-backup-test
bkx job export phone-backup --output phone-backup.json
bkx job import phone-backup.json --job-id imported-phone
bkx job edit phone-backup --description "Nightly phone backup"
bkx job delete phone-backup
```

Historical successful or failed backup/transfer specs can be replayed without
reconstructing command-line arguments:

```sh
bkx run replay RUN_ID
bkx run replay RUN_ID --set destination=/tmp/replay
bkx job create-from-run RUN_ID phone-replay --name "Phone replay"
```

`--set key=value` creates an execution-only override. It never rewrites the
saved job or the historical run. Dot-separated keys address nested parameter
objects.

Job revisions remain optimistic and immutable in history. `job edit` accepts
`--expected-revision N` when callers need an explicit compare-and-swap guard.
Credential values are still forbidden in parameters; only `--credential-ref`
identifiers can be persisted.
