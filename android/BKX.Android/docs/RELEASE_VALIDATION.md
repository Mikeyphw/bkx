# BKX Android release validation

The normal release validation is driven by `.devtool.toml`; explicit runs should pass every Gradle task through its own `--task` flag.

```bash
devtool -r ~/Code/bkx --target bkx_android validate \
  --task :app:installBkxRcloneAndroidRuntime \
  --task :app:verifyBkxAndroidSdk36DependencyLane \
  --task :app:verifyBkxEmbeddedEngineContract \
  --task :app:verifyBkxRootStorageContract \
  --task :app:verifyBkxProductUiContract \
  --task :app:verifyBkxRcloneRemoteTransportContract \
  --task :app:verifyBkxGoogleDriveProductContract \
  --task :app:verifyBkxAutomationProductContract \
  --task :app:verifyBkxCloudProductReleaseSeal \
  --task :app:verifyBkxRuntimeHardeningContract \
  --task :app:verifyBkxRestoreSafetySeal \
  --task :app:verifyBkxPerformanceAndProgressContract \
  --task :app:verifyBkxLargeTreePerformanceSeal \
  --task :app:verifyBkxStorageCompletenessContract \
  --task :app:verifyBkxAccessibilityVisualContract \
  --task :app:verifyBkxUxHardeningContract \
  --task :app:verifyBkxEmbeddedParitySeal \
  --task :app:verifyBkxReleaseHygiene \
  --task :app:verifyBkxDeviceQualificationContract \
  --task :app:verifyBkxGoProductionCutoverContract \
  --task :app:verifyBkxGoOnlyAndroidRuntimeContract \
  --task :app:verifyBkxFinalReleaseContract \
  --task :app:verifyBkxAndroidManifestReleaseContract \
  --task :app:verifyBkxAndroidSigningPlaceholders \
  --task :app:verifyBkxPersonalSigningContract \
  --task :app:verifyBkxAndroidReleaseDocs \
  --task :app:verifyBkxReleasePackagingSigningNativeRuntimeSeal \
  --task :app:verifyBkxReleaseMergedManifestContract \
  --task :app:testDebugUnitTest \
  --task :app:lintDebug \
  --task :app:lintRelease \
  --task :app:assembleDebug \
  --task :app:assembleDebugAndroidTest \
  --task :app:assembleRelease
```

The repository-side cumulative cloud/product replay is:

```bash
scripts/verify-ov56-roadmap-seal.sh
scripts/validate.sh
```

`verifyBkxCloudProductReleaseSeal` is the Android-side OV1–OV6 evidence gate. It requires the structured rclone contract, first-class Google Drive product contract, restore/progress safety gates, roadmap matrix, acceptance checklists, and current verifier scripts.

## BR35 release packaging/signing/native runtime seal

`verifyBkxReleasePackagingSigningNativeRuntimeSeal` is the deployability gate for BR35. It verifies that release APKs are minified/resource-shrunk, release lint is part of the validation lane, the merged release manifest is inspected after Android manifest merging, native 16 KiB validation runs in the packaging phase rather than cheap preflight, and the generated Go runtime is stamped with a real Git commit through `BKX_BUILD_COMMIT`.

The physical-device lane can now qualify the exact release APK that will be installed:

```bash
cd ~/Code/bkx/android/BKX.Android
./scripts/qualify-device.sh \
  --release-apk app/build/outputs/apk/release/app-release.apk \
  --android-test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

This records the installed package path, `dumpsys package` evidence, APK SHA-256, and screenshots under `artifacts/qualification-screenshots`.

## Google Drive release setup

Google Drive authorization uses `com.google.android.gms:play-services-auth:21.6.0` and only the `drive.file` scope. A build intended to use Drive must have the Drive API enabled and an Android OAuth client matching `com.mikeyphw.bkx.android` plus the SHA-1 of the permanent BKX signing certificate. Run `bash tools/setup-personal-signing.sh` once; the real keystore and password properties remain under `~/.config/bkx/` (or another external path selected by environment variables), never in the source tree. When configured, both debug and release APKs use that same certificate.

Before distributing/installing a permanent build, run:

```bash
gradle :app:verifyBkxPersonalSigningConfigured :app:assemblePersonalRelease
```

Use `gradle :app:assemblePersonalDebug` when a debuggable APK is desired without changing Android/Google OAuth identity.

The manifest must retain `android.permission.INTERNET`; the packaged rclone process executes under the BKX app UID and relies on that permission. Validation must also prove that access-token values never enter UI models, bridge payloads, run history, or diagnostics.

## Tasker / external automation release gate

`verifyBkxAutomationProductContract` seals the Locale/Tasker edit/fire surface, direct explicit-Intent API, separate Tasker/direct capabilities, internal foreground + system-protected JobService dispatch, persistent idempotency receipts, `%bkx_*` asynchronous completion variables, Google Drive silent refresh, and reuse of the existing backup/restore/archive/transfer builders and coordinator. The manifest must expose only the edit Activity and fire/direct receiver; the execution service remains non-exported and the JobService is protected by `android.permission.BIND_JOB_SERVICE`.

Manual smoke for a release build: create a Tasker **BKX Automation** backup action with a blank Request ID, run it twice and confirm two distinct request IDs; repeat with a fixed Request ID and confirm the second invocation returns the existing terminal result. Exercise final variables, Status, Cancel, a destructive-restore confirmation failure, managed Google Drive refresh, token regeneration, and rejection of an old Tasker action after regeneration. Direct API smoke must use a package/component-explicit broadcast and a callback package/action pair. See `docs/AUTOMATION_TASKER.md`.

## Opt-in physical-device qualification

Root authorization is a user-controlled action, so the root-required qualification lane is explicit:

```bash
cd ~/Code/bkx/android/BKX.Android
./scripts/qualify-device.sh
```

It verifies All files access, a real UID-0 libsu RootService, direct Termux-private access, the packaged local Go runtime, navigation semantics, and product screenshots.

Google Drive consent and real cloud I/O are also device/account controlled. For a Drive-enabled release, manually connect a test account, create a small verified backup, browse/verify it from Archives, restore it to a safe temporary directory, disconnect, and confirm imported non-Google rclone remotes remain intact. Do not place credentials or account identifiers in screenshots/evidence.

## Manual operational smoke

Exercise local and Google Drive backup, archive browse/verify, fresh restore, a deliberately chosen root-sensitive restore test target, local/rclone transfer, cancellation, Activity recreation, notification return, and phone/tablet + light/dark/dynamic-color layouts. Never use protected Android system roots as smoke-test destinations.

## Go-only Android runtime

The APK does not apply Chaquopy or package Python. Release validation requires `verifyBkxGoOnlyAndroidRuntimeContract`, the generated ARM64 ELF, `ndkVersion = "29.0.14206865"`, and the r29+ floor. The Android artifact is intentionally `arm64-v8a` only.

Release validation also requires `verifyBkx16KbNativePageSizeContract`. It builds debug and release APKs and parses every packaged native ELF; every `PT_LOAD` must be compatible with 16 KiB pages. The rclone runtime is built from the SHA-256-pinned official v1.75.1 vendored source because the upstream Android v1.75.1 testbuild is only 4 KiB aligned. NDK r28+ produces 16 KiB alignment by default; BKX still passes the 16 KiB linker policy explicitly and verifies the resulting ELF and APK.

The same packaged Go protocol-v1 runtime powers normal app execution and the explicit libsu UID-0 route. Rclone is a separate pinned Android ARM64 executable boundary and does not replace the BKX engine.

For native Termux builds, compiler selection is probe-driven. A candidate that fails the real Go/cgo Android/arm64 + 16 KiB link probe is rejected and the installer continues to the next lane. The preferred fallback uses native Termux clang with the Gradle-selected NDK r29 sysroot, avoiding unlaunchable glibc-hosted NDK compiler binaries while preserving the Android API/ABI link environment.

### Tasker Event / State conditions

`verifyBkxAutomationProductContract` also seals the bidirectional condition surface. It requires the pinned Tasker condition library, distinct Event/State configuration activities, Action-component manifest removal, authoritative coordinator and Google Drive publisher hooks, canonical progress buckets, bounded redacted condition state, all promised Event/State selectors, and the `%bkx_*` output-variable contract. `testDebugUnitTest` covers wildcard/remote/progress matching; `lintDebug` and `bkxAndroidReleaseSeal` remain release blockers.

## BR19+BR20 backup automation final seal

The final Saved Jobs/Restic/Android/Tasker roadmap gate is `:app:verifyBkxBackupAutomationRoadmapFinalSeal`. It is also a dependency of `:app:bkxAndroidReleaseSeal` and verifies the shared jobs/repositories/schedules/run-history control plane, Restic `latest~N` selectors, Android Jobs/Repositories/Snapshots surfaces, Tasker V2 actions/metrics, and reboot/scheduling recovery contracts. The canonical roadmap evidence is `../../docs/BR19_BR20_FINAL_BACKUP_AUTOMATION_SEAL.md`.

This BR19+BR20 overlay is the first full-validation step for the BR01–BR20 series; earlier roadmap overlays intentionally used no full Gradle validation.

### BR20 v6 standalone parity closure

`bkx_br20_android_final_backup_automation_seal_v6.zip` is the standalone final BR20 overlay for the same post-BR19 baseline; it supersedes the failed v4 and interim v5 artifacts. The final gate additionally seals atomic Saved Job provenance and replay, canonical Go-to-Android run history, distinct cancelled/interrupted phases, conservative Restic stale-lock recovery, repository/job lease concurrency, Tasker effective repository overrides and rich result metrics, foreground-service lifetime under concurrent repositories, `repository status`, positional snapshot context, and the product-oriented Saved Job editor. The artifact validator still requires the real declared `github.com/klauspost/compress v1.18.4` module and runs `go mod verify`, `go test ./...`, and `go vet ./...` before the explicit Android tasks.


### BR20 v7 validation/startup hotfix

`bkx_br20_full_backup_jobs_tasker_release_seal_v7.zip` supersedes v6 on the same post-BR19 baseline. It preserves all BR20 v6 feature work while fixing the final validation mismatch around repository-safe concurrency and making Android startup cache-first for run history. The release gate must reject any stale `foregroundOperationMutex` requirement, require idle-aware foreground-service lifetime for concurrent repositories, and verify that `BkxAppViewModel.init` does not start canonical `runs.list` I/O. Full validation is still required.
