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

This overlay intentionally aligns the Android foundation with the uploaded StudyFlow Gradle plugin and dependency stack: AGP `9.3.0`, Kotlin/Compose/serialization plugins `2.4.10`, Compose BOM `2026.06.00`, min SDK `23`, Java toolchain `17`, and the matching AndroidX/coroutines/serialization test dependency versions captured in `gradle/libs.versions.toml`. Unlike the StudyFlow snapshot, BKX Android caps compile/target SDK at stable Android 16 / API 36 so the project does not require unreleased or preview SDK 37 platforms.

## Validation

Preferred Devtool apply + validation from the actual BKX repository root (`~/Code/bkx`):

```bash
ARTIFACT="$HOME/Downloads/bkx_android_aov01_aov02_foundation_termux_bridge_v5.zip"
devtool --copy -y -r "$HOME/Code/bkx" apply-overlay "$ARTIFACT" \
  --validate \
  --task :app:testDebugUnitTest \
  --task :app:lintDebug \
  --task :app:assembleDebug \
  --commit "Create BKX Android companion foundation and Termux bridge"
```

Each Gradle task name is passed with its own `--task` flag on the apply command itself. The overlay manifest also enables a single commit with the same commit message after validation succeeds.

## Validation SDK policy

Devtool pins the validation Android platform to API 36 for this target. The companion app also declares `compileSdk = 36` and `targetSdk = 36`, avoiding preview/unavailable SDK package auto-selection during chroot validation.
