# BR27 Saved Jobs, Scheduler, Tasker and Run History Seal

Overlay 7 of 16 in the deduped BKX remediation roadmap.

## Scope

BR27 seals the saved-job and automation control plane so scheduled, Tasker, direct automation, and history replay paths obey the same canonical runtime contracts as manually launched BKX operations.

## Fixes delivered

- Saved-job updates preserve `enabled` when clients omit it, preventing silent re-enable regressions.
- Disabling a saved job disables its schedules; deleting a saved job removes dependent schedules before the job disappears.
- `jobs.run` accepts `expected_revision` and rejects scheduled dispatches when the saved job changed before execution.
- Run replay is terminal-only and advertises explicit `replayable` metadata to Android.
- Runs are listed through bounded pages with `next_page_token` metadata.
- Run-history timestamps are now UTC RFC3339 and newer-version unknown fields are not quarantined as corrupt.
- Run-history artifacts redact private local paths and rclone locations before exposing `run_json` to Android/UI consumers.
- Schedule store validates persisted documents semantically, rejects overflow intervals, writes empty arrays instead of `null`, and directory-fsyncs atomic replacement.
- Android schedule definitions cap intervals at one year.
- Android JobScheduler IDs are allocated through a persistent collision-free map instead of raw hashes.
- Android periodic jobs include schedule signatures and saved-job revisions; stale/deleted/disabled schedules are reconciled before execution.
- Scheduled jobs keep the JobService lifecycle open until BKX automation execution completes.
- Automation disable clears queued dispatches and active receipts.
- Automation fallback JobScheduler IDs are isolated from periodic saved-job schedule IDs.
- Tasker `%bkx_success` is true only for terminal `success`, not queued/running/accepted states.
- Automation list parsing is newline-only, so commas inside valid paths or patterns are preserved.
- Automation result delivery is marked before callbacks so process death after callback send does not duplicate callback delivery.
- The custom schedule-reconcile broadcast receiver is no longer externally exported.

## Validation

This is an intermediate overlay and should be applied with `--no-validate`. The new Gradle gate task is:

```bash
:app:verifyBkxSavedJobsSchedulerTaskerRunHistorySeal
```

Sandbox checks completed:

```bash
go test ./internal/jobstore ./internal/schedulestore ./internal/runstore ./internal/executioncontrol ./internal/models
```

Full Android/Gradle validation remains deferred to a later gate overlay.
