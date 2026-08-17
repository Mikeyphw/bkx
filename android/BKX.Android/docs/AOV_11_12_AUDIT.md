
# AOV-11/AOV-12 Audit

Scope: AC-050 through AC-052.

## AC-050 adversarial tests

Implemented by `Aov11AdversarialContractTest`, backed by existing phase-specific tests:

- missing Termux maps to a stable backend-not-ready failure with remediation;
- RUN_COMMAND permission denial maps to a stable permission failure;
- SAF grant loss remains an explicit file-access issue with re-selection remediation;
- malformed bridge JSON is rejected through the strict bridge parser;
- destructive restore extraction cannot use forged or stale confirmation evidence.

These are regression tests against production code paths, not documentation-only checks.

## AC-051 instrumentation/lifecycle tests

Implemented by Android instrumentation sources under `app/src/androidTest`:

- `MainActivityLifecycleInstrumentationTest` exercises `ActivityScenario.recreate()` for rotation-style lifecycle recreation.
- `OperationNotificationInstrumentationTest` verifies foreground-service notification intents carry the request id, operation, phase, progress, and cancel path needed for active-run reconnect.

Production support includes `BkxOperationForegroundService`, `AndroidNotificationPermissionChecker`, and `BkxOperationNotificationPlanner`. The service is only a user-visible Android lifecycle/notification shell; BKX operation execution remains in Termux through the fixed wrapper and structured files.

## AC-052 release seal

Implemented by:

- `.devtool.toml` release/instrumentation/package task aliases;
- Gradle task `:app:bkxAndroidReleaseSeal`, which depends on lint, unit tests, instrumentation tests, debug package creation, docs checks, signing-placeholder checks, and manifest checks;
- `tools/bkx-android-release-seal.sh` static release preflight;
- `docs/FINAL_RELEASE_VALIDATION.md` with the Devtool final validation command;
- `docs/SETUP_GUIDE.md` for Termux/RUN_COMMAND/SAF/notification setup;
- `docs/SIGNING_PLACEHOLDERS.md` and `gradle/bkx-release-signing.properties.example` for release-signing placeholders without committed secrets.

The final gate is fail-closed: instrumentation is explicitly part of the release seal, so a missing device/emulator or missing notification/FGS manifest contract prevents release qualification.

## Thin-interface guard

No Kotlin backup/archive/restore/transfer business logic was added. Android owns tests, lifecycle visibility, notification state, validation wiring, and setup documentation; BKX remains the engine of truth.


## Double-check fix in v2

The v2 audit found that v1's notification instrumentation verified the foreground-service launch intent, but not the full notification-to-activity reconnect route promised by AC-051. v2 adds a request-id preserving notification `PendingIntent`, reconnect intent parsing in `MainActivity.onCreate` and `onNewIntent`, focused active-operation state, and tests for the reconnect intent contract.

The Gradle stack remains the v7/AGP 9 built-in Kotlin stack from the applied base; v2 does not reintroduce the standalone Kotlin Android plugin.
