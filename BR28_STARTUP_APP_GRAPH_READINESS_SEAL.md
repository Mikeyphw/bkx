# BR28 — Startup/App Graph and Readiness Seal

Overlay **8 of 16** in the BKX A01-C17 remediation roadmap.

## Scope

BR28 repairs U01: startup/app graph side-effect barriers. It closes the startup defects that were not part of the BR21 runtime-state-machine repair:

- A01-02 startup recovery lacked a real barrier.
- A01-03 normal-process cold start caused side effects before authorized ingress.
- A01-04 boot/package recovery could duplicate Application startup reconciliation.
- A01-08/A06-04 startup catalog/readiness could depend on route/root choices.
- A01-11/A13-24 main-thread startup parsed external documents and touched JobScheduler.
- A01-17 feature catalogs were eagerly hydrated before the user opened those surfaces.
- A01-18 Android Settings/root/storage capability changes were not revalidated on resume.
- A01-19 concurrent readiness refreshes were last-writer-wins.
- A01-22/A01-23 graph initialization/failure/test lifetime was implicit.
- A12-17/A17-12 startup connection/control-plane document handling lacked a shared bounded truth source.

## Implementation

- `BkxAndroidApplication` is now side-effect-light. It installs `BkxApplicationRuntime` only and no longer performs operation recovery, automation recovery, or JobScheduler reconciliation in `Application.onCreate()`.
- `BkxApplicationRuntime` owns explicit runtime phases (`Created`, `Initializing`, `Ready`, `Degraded`, `ShuttingDown`) and provides a suspendable `awaitReadyForExecution()` barrier.
- Authorized ingress surfaces now pass through the barrier:
  - launcher/interactive UI;
  - Activity resume capability revalidation;
  - Tasker/direct automation service;
  - scheduled-job service;
  - boot/package recovery receiver;
  - foreground notification cancellation.
- `BkxOperationCoordinator` now exposes `initializeAndRecoverInterruptedOperations()` so startup recovery can be awaited instead of launched fire-and-forget.
- `BkxControlPlaneRootResolver` centralizes shared/private control-plane root selection and marker validation, with byte/item limits for startup-read documents.
- `GoNativeGateway` and `BkxSavedJobScheduler` use the same control-plane resolver instead of separate fallback logic.
- `BkxSavedJobScheduler` reads jobs/schedules with bounded decoding and schema-version checks.
- `BkxAppViewModel` startup is Home-critical/cache-first only. Jobs, schedules, repositories, archives, remotes and transfer catalog surfaces hydrate when their route is opened.
- Backend readiness refreshes are generation-bound so stale probes cannot overwrite newer results.
- `DefaultBackendGraph.resetForTests()` provides a process-scope teardown seam for lifecycle tests.

## Gate

Future gate task added:

```bash
:app:verifyBkxStartupAppGraphReadinessSeal
```

The overlay remains intermediate; use `--no-validate` on apply and run gate validation later when sensible.
