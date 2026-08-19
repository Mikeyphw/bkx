# AOV-01 + AOV-02 Promise Audit

This audit maps the combined overlay implementation to the promised AC items.

| AC | Status | Evidence |
|---|---|---|
| AC-001 | Implemented | Gradle Android app project under `android/BKX.Android`, Compose enabled, namespace/application id fixed as `com.mikeyphw.bkx.android`, SDK policy set to stable Android 16 / API 36 with StudyFlow-aligned Gradle plugins/dependencies (min SDK 23, AGP 9.3.1, Kotlin 2.4.10), Devtool target `bkx_android` added with Android platform explicitly pinned to API 36 and managed Gradle pinned to 9.7.0 for the standardized stable toolchain. |
| AC-002 | Implemented | Packages separate `app`, `feature.home`, `core.usecase`, `core.backend`, `core.bridge`, and `termux`. |
| AC-003 | Implemented | `BkxHomeUiState`, `BkxHomeEvent`, `CheckBackendReadinessUseCase`, `BkxAppViewModel`, and composables use state-down/events-up UDF. |
| AC-004 | Implemented | `BackendPort` and deterministic `FakeBackendPort` are present before full Termux execution behavior. |
| AC-005 | Implemented | `BackendReadiness` sealed states include ready, missing Termux, permission missing, allow-external-apps unverified, wrapper unavailable, unsupported protocol, and probe failed. |
| AC-006 | Implemented | Termux calls are `Intent`-based fixed wrapper invocations; tests scan for process/shell execution tokens. |
| AC-007 | Implemented | Manifest includes `<queries>` for package `com.termux` and action `com.termux.RUN_COMMAND`; tests assert both. |
| AC-008 | Implemented | `AndroidTermuxReadinessProbe` checks package visibility and RUN_COMMAND permission, then returns structured allow-external-apps verification remediation. |
| AC-009 | Implemented | `ApprovedWrapperIdentity` accepts only `/data/data/com.termux/files/usr/bin/bkx-android-bridge`; tests reject a bash path. |
| AC-010 | Implemented | `BridgeRequestFiles`, `TermuxRunCommandContract.fixedArguments()`, and `RUN_COMMAND_STDIN` carry the request id, one structured request JSON document, and request/result/progress/cancel paths without shell payloads. |
| AC-011 | Implemented | `BridgeJson.encodeRequest()`, `encodeCancel()`, and `parseFinalResult()` keep request/cancel/result as structured JSON; final result parsing rejects empty, malformed, multiple-document, and unsupported-protocol output. |
| AC-012 | Implemented | `BridgeJson.SUPPORTED_PROTOCOL_VERSION = 1`; requests/results/cancel/progress are versioned and parser rejects protocol drift. |

No backup/archive/restore/transfer business semantics were ported into Kotlin in this phase.

## Devtool apply contract

The intended one-transaction command is:

```bash
ARTIFACT="$HOME/Downloads/bkx_android_aov01_aov02_foundation_termux_bridge_v7.zip"
devtool --copy -y -r "$HOME/Code/bkx" apply-overlay "$ARTIFACT" \
  --validate \
  --task :app:testDebugUnitTest \
  --task :app:lintDebug \
  --task :app:assembleDebug \
  --commit "Create BKX Android companion foundation and Termux bridge"
```

## Gradle source update

AOV-03/AOV-04 keeps the AGP 9 built-in Kotlin layout and refreshes deterministic warning sources: AGP `9.3.1`, managed Gradle `9.7.0`, Kotlin/Compose/serialization compiler plugins `2.4.10`, Compose BOM `2026.06.01`, min SDK `23`, target/compile SDK `36`, and Java toolchain `21`.

## SDK platform pin audit

AOV-03/AOV-04 supersedes the earlier API 36 cap after Android 16 / API 36 became available in the validator environment. The Devtool target now sets `[targets.bkx_android.android].platform = "36"`; the Gradle project uses `compileSdk = 36` and `targetSdk = 36`.


## AGP 9 built-in Kotlin migration

This v7 artifact removes `alias(libs.plugins.kotlin.android)` from both the root and app Gradle plugin blocks, removes the `kotlin-android` plugin alias from `gradle/libs.versions.toml`, and relies on AGP 9.3.1 built-in Kotlin support. The standalone Compose and serialization compiler plugins remain because they are separate compiler integrations used by the app.
