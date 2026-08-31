# BKX Android release validation

The normal release validation is driven by `.devtool.toml`; explicit runs should pass every Gradle task through its own `--task` flag.

```bash
devtool -r ~/Code/bkx validate \
  --task :app:verifyBkxAndroidSdk36DependencyLane \
  --task :app:verifyBkxEmbeddedEngineContract \
  --task :app:verifyBkxRootStorageContract \
  --task :app:verifyBkxProductUiContract \
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
  --task :app:verifyBkxFinalReleaseContract \
  --task :app:verifyBkxAndroidManifestReleaseContract \
  --task :app:verifyBkxAndroidSigningPlaceholders \
  --task :app:verifyBkxAndroidReleaseDocs \
  --task :app:testDebugUnitTest \
  --task :app:lintDebug \
  --task :app:assembleDebug \
  --task :app:assembleDebugAndroidTest \
  --task :app:assembleRelease
```

## Opt-in physical-device qualification

Root authorization is a user-controlled action, so the root-required qualification lane is explicit:

```bash
cd ~/Code/bkx/android/BKX.Android
./scripts/qualify-device.sh
```

It verifies All files access, a real UID-0 libsu RootService, direct Termux-private access, embedded local fallback, navigation semantics, and product screenshots.

## Manual operational smoke

Exercise backup, archive browse/verify, fresh restore, a deliberately chosen root-sensitive restore test target, local transfer, cancellation, Activity recreation, notification return, and phone/tablet + light/dark/dynamic-color layouts. Never use protected Android system roots as smoke-test destinations.

## Chaquopy build Python

Chaquopy 17 requires the build interpreter major/minor to match the packaged runtime. BKX accepts Python 3.13 or 3.14 and supports `-Pbkx.buildPython=/path/to/python` or `BKX_BUILD_PYTHON`.
