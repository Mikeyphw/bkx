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
  --task :app:testDebugUnitTest \
  --task :app:lintDebug \
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

## Google Drive release setup

Google Drive authorization uses `com.google.android.gms:play-services-auth:21.6.0` and only the `drive.file` scope. A build intended to use Drive must have the Drive API enabled and an Android OAuth client matching `com.mikeyphw.bkx.android` plus the SHA-1 of the permanent BKX signing certificate. Run `bash tools/setup-personal-signing.sh` once; the real keystore and password properties remain under `~/.config/bkx/` (or another external path selected by environment variables), never in the source tree. When configured, both debug and release APKs use that same certificate.

Before distributing/installing a permanent build, run:

```bash
gradle :app:verifyBkxPersonalSigningConfigured :app:assemblePersonalRelease
```

Use `gradle :app:assemblePersonalDebug` when a debuggable APK is desired without changing Android/Google OAuth identity.

The manifest must retain `android.permission.INTERNET`; the packaged rclone process executes under the BKX app UID and relies on that permission. Validation must also prove that access-token values never enter UI models, bridge payloads, run history, or diagnostics.

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
