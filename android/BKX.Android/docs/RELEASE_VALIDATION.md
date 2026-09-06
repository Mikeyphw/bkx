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
  --task :app:verifyBkxGoProductionCutoverContract \
  --task :app:verifyBkxGoOnlyAndroidRuntimeContract \
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

It verifies All files access, a real UID-0 libsu RootService, direct Termux-private access, the packaged local Go runtime, navigation semantics, and product screenshots.

## Manual operational smoke

Exercise backup, archive browse/verify, fresh restore, a deliberately chosen root-sensitive restore test target, local transfer, cancellation, Activity recreation, notification return, and phone/tablet + light/dark/dynamic-color layouts. Never use protected Android system roots as smoke-test destinations.

## GO14 Go-only Android runtime

The APK no longer applies Chaquopy or packages Python. Release validation requires `verifyBkxGoOnlyAndroidRuntimeContract`, the generated ARM64 ELF, `ndkVersion = "29.0.14206865"`, and the r29+ floor. The Android artifact is intentionally `arm64-v8a` only.


GO13/GO14 acceptance requires `verifyBkxGoProductionCutoverContract`, Android unit/compile/package gates, normal ARM64 Go cutover instrumentation, and explicit `qualify-device.sh` evidence proving both local and privileged readiness report `go-native-android`.
