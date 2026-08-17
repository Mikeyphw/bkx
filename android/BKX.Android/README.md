# BKX Android Companion

Native Kotlin + Jetpack Compose companion app for BKX.

This project is intentionally a thin interface over the repaired BKX Python engine. Android code owns UI state, Android permissions, Termux bridge dispatch, and structured rendering. BKX remains the canonical implementation for backup, archive, restore, and transfer semantics.

## AOV-01/AOV-02 scope

Implemented creation requirements:

- AC-001: Android app project with fixed namespace/application id `com.mikeyphw.bkx.android`, Compose, Kotlin, min SDK 23, target/compile SDK 36, and Devtool target metadata.
- AC-002: package layout separates app/UI, core backend ports, bridge DTOs, Termux adapter, and tests.
- AC-003: Compose UDF screen state and typed UI events through `BkxAppViewModel`.
- AC-004: `BackendPort` and `FakeBackendPort` exist before real Termux execution.
- AC-005: backend readiness uses structured sealed states with remediation steps.
- AC-006: no arbitrary shell strings or general process execution APIs are used.
- AC-007: manifest declares package visibility for Termux package and RUN_COMMAND action.
- AC-008: readiness handles missing Termux, missing RUN_COMMAND permission, and unverified `allow-external-apps` setup.
- AC-009: Termux dispatch uses only the approved `bkx-android-bridge` wrapper identity.
- AC-010: bridge invocation passes request/result/progress/cancel paths with a request id.
- AC-011: final backend result parsing accepts exactly one structured JSON document.
- AC-012: bridge protocol is versioned and unsupported versions are rejected.

## Gradle stack

This overlay intentionally aligns the Android foundation with the uploaded StudyFlow Gradle plugin and dependency stack: AGP `9.3.1`, Kotlin/Compose/serialization plugins `2.4.10`, Compose BOM `2026.08.00`, min SDK `23`, Java toolchain `17`, and the matching AndroidX/coroutines/serialization test dependency versions captured in `gradle/libs.versions.toml`. Unlike the StudyFlow snapshot, BKX Android targets current Android 16 / API 36 so the project does not require current Android 16 / API 36 platforms.

## Interim validation policy

AOV-05/AOV-06 and later interim Android companion overlays are applied with `--no-validate`. Full Gradle validation is intentionally deferred until the last roadmap overlay.

## Validation SDK policy

Devtool pins the validation Android platform to API 36 for this target. The companion app also declares `compileSdk = 36` and `targetSdk = 36`, matching the current released Android platform used by the validator.


## AOV-05/AOV-06 scope

The companion now includes backup planning and archive read-only UI contracts:

- backup source/destination/filter/compression/split/verify state;
- `backup.plan` request generation before any `backup.create`;
- create gating that refuses invalid, stale, or unpreviewed plans;
- archive list/info/browse/verify request builders plus list/info/browse/verify UI state;
- paged browse state for large archives;
- verification failures rendered as failures;
- read-only archive operation allowlisting.

The Kotlin layer still does not perform backup or archive engine work; it sends normalized requests to BKX through the backend bridge.


## Current interim apply command

For AOV-05/AOV-06 and later interim Android companion overlays, validation is intentionally deferred until the final overlay:

```bash
ARTIFACT="$HOME/Downloads/bkx_android_aov05_aov06_backup_archive_readonly_v2.zip"
devtool --copy -y -r "$HOME/Code/bkx" apply-overlay "$ARTIFACT" \
  --no-validate \
  --commit "Add BKX Android backup planning and archive read-only workflows"
```

## AOV-07/AOV-08 scope

The companion now includes restore and transfer safety-gate UI contracts:

- restore archive/destination/subset/output-mode state;
- `restore.preview` request generation before any `restore.extract`;
- fresh-directory restore as the default;
- overwrite, link, special-file, and path-safety summaries rendered from BKX bridge result fields;
- explicit typed confirmation before destructive restore extraction;
- extraction gated by a matching preview so navigation/output-mode changes cannot bypass confirmation;
- push/pull/sync transfer topology state and mapping summary;
- collision, overlap, symlink/special-file policy, and verification-plan rendering;
- `transfer.plan` request generation before any `transfer.run`, while keeping the UI concept of a transfer preview;
- partial failures and verification failures rendered as typed failures.

The Kotlin layer still does not restore or transfer files; it sends normalized requests to BKX through the backend bridge and renders structured results.

## Current interim apply command

For AOV-07/AOV-08 and later interim Android companion overlays, validation is intentionally deferred until the final overlay:

```bash
ARTIFACT="$HOME/Downloads/bkx_android_aov07_aov08_restore_transfer_safety_v2.zip"
devtool --copy -y -r "$HOME/Code/bkx" apply-overlay "$ARTIFACT" \
  --no-validate \
  --commit "Add BKX Android restore and transfer safety gates"
```

## AOV-09/AOV-10 scope

The companion now includes runtime operation and result-rendering contracts:

- progress event consumption keyed by BKX request id;
- active operation persistence for rotation/process recreation reconnect;
- request-id cancellation models and fixed-wrapper cancellation dispatch;
- typed timeout, quota, process-death, permission, backend-not-ready, SAF-loss, cancellation and malformed-result failures;
- strict schema response parsing that rejects malformed, multiple, partial, unknown-field, or unsupported-version output;
- stable result rendering actions for typed BKX errors and verification failures;
- semantic parity snapshots for representative Android bridge requests versus CLI JSON expectations.

The Kotlin layer still does not execute BKX backup/archive/restore/transfer semantics. It tracks Android runtime state and renders BKX structured progress/results.

## Current interim apply command

For AOV-09/AOV-10 and remaining interim Android companion overlays, validation is intentionally deferred until the final overlay:

```bash
ARTIFACT="$HOME/Downloads/bkx_android_aov09_aov10_operations_rendering_parity_v1.zip"
devtool --copy -y -r "$HOME/Code/bkx" apply-overlay "$ARTIFACT" \
  --no-validate \
  --commit "Add BKX Android operation runtime and result rendering parity"
```


AOV-09/AOV-10 v2 closes the double-check gaps by adding progress-file polling, backend-submitted cancellation, and result-side CLI parity snapshots.


## AOV-11/AOV-12 scope

The companion now includes final instrumentation and release-seal contracts:

- adversarial tests for missing Termux, RUN_COMMAND permission denial, SAF grant loss, malformed bridge JSON, and restore confirmation bypass;
- Android instrumentation tests for activity recreation and notification/reconnect intent contracts;
- user-visible foreground-service notification shell for active operation request ids;
- release validation docs, setup guide, signing placeholders, and fail-closed Gradle/Devtool release gates.

The Kotlin layer still does not execute BKX backup/archive/restore/transfer semantics. Android owns lifecycle visibility, notification state, structured rendering, setup guidance, and release validation wiring.

## Final validation command

AOV-12 is the final roadmap overlay. Use `docs/FINAL_RELEASE_VALIDATION.md` for the full Devtool validation command with one `--task` flag per Gradle task.
