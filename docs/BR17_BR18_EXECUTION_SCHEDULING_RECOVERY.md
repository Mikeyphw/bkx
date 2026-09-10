# BKX BR17+BR18 — execution control, scheduling, and recovery

BR17+BR18 makes saved-job automation safe to invoke from the CLI, Android UI, and Tasker at the same time.

## Execution-only overrides

`bkx job run JOB_ID --set KEY=VALUE` and Tasker **Run Saved Job** overrides are applied to a cloned execution spec. They never mutate the saved job revision. The resulting spec is revalidated by the Saved Job V2 contract, including inline-secret rejection.

## Shared execution control

The non-secret BKX state root now contains `execution/control.json`. Long-running operations acquire a lease carrying request, job, repository, process, boot, and Linux process-start identity. The same job or repository cannot be run concurrently by separate BKX processes. Stale leases are recovered after process death/reboot and PID reuse cannot inherit an abandoned lease.

Cancellation requests are persisted by request ID. `bkx run cancel REQUEST_ID` can therefore signal work started through another BKX process when that process is using the shared state root.

`bkx state status` reports active execution leases together with the resolved control-plane paths.

## Saved-job schedules

Schedules are stored at `schedules/schedules.json` in the same non-secret shared state root used by CLI and Android.

CLI surface:

```text
bkx schedule add SCHEDULE_ID --job JOB_ID --every MINUTES [--network none|any|unmetered] [--charging]
bkx schedule set SCHEDULE_ID --job JOB_ID --every MINUTES [--network none|any|unmetered] [--charging] [--disabled]
bkx schedule list
bkx schedule show SCHEDULE_ID
bkx schedule enable SCHEDULE_ID
bkx schedule disable SCHEDULE_ID
bkx schedule remove SCHEDULE_ID
```

The minimum recurring cadence is 15 minutes. `network=none` is the default so local jobs remain runnable offline; `any` and `unmetered` add network constraints explicitly.

On Android these definitions are reconciled into persistent `JobScheduler` jobs. Reconciliation is idempotent: an unchanged schedule keeps its existing JobScheduler entry rather than resetting its next execution time. CLI schedule mutations notify the installed BKX Android package to reconcile immediately.

## Recovery

BKX reconciles persisted schedules and pending Tasker dispatches at app startup, after `BOOT_COMPLETED`, and after package replacement. Pending Tasker requests retain their automation request identity but receive a fresh operation-stage ID after process death, preventing collision with abandoned run-history records.

Full Android/Tasker/CLI release validation remains deferred to BR19+BR20; this is an intermediate roadmap overlay and is intended to be applied with `--no-validate`.
