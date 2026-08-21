# BKX Android final release validation — AOV-32

AOV-25→32 is the final validation overlay. Unlike the prior interim mega-overlays, apply this overlay **with validation enabled**.

## Devtool release validation

Each Gradle task is passed through its own `--task` flag:

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
  --task :app:assembleRelease \
  --task :app:connectedDebugAndroidTest \
  --task :app:bkxAndroidReleaseSeal
```

The target's normal `devtool -r ~/Code/bkx validate` is preferred once these tasks are committed into `.devtool.toml`.

## Explicit rooted device qualification

The normal connected test suite intentionally skips the root-required qualification case unless explicitly enabled. Root authorization is a user-controlled root-manager action and cannot be safely assumed by a generic build.

After installing the generated debug and androidTest APKs, grant BKX root in Magisk/KernelSU/APatch-compatible root management, then run:

```bash
cd ~/Code/bkx/android/BKX.Android
./scripts/qualify-device.sh
```

The qualification fails unless:

- All files access is enabled;
- BKX receives a real UID-0 libsu RootService;
- BKX can directly stat `/data/data/com.termux/files`;
- root-disabled mode still resolves to the embedded non-root engine;
- Home, Backup, Archives, Activity, and More have clickable accessibility semantics;
- five real-device product screenshots are captured and copied to `artifacts/qualification-screenshots`.

## Manual operational smoke

On the physical device also exercise:

1. backup from shared storage;
2. backup from `/data/data/com.termux/files/home` with root;
3. archive browse and verification;
4. restore into a fresh shared-storage path;
5. privileged restore into an intentionally chosen Termux-private test directory using `RESTORE ROOT`;
6. local transfer;
7. cancellation during a multi-file backup/restore/transfer;
8. rotate/recreate Activity while work is running;
9. background the app and return through its operation notification;
10. light/dark/dynamic color and phone/tablet layouts.

Do not use protected Android system roots as smoke-test restore destinations.

## Completeness replay corrections (v2)

The final release seal also runs three gates added after replaying AOV-13→32 from the original snapshot and auditing the finished tree against the roadmap:

- `:app:verifyBkxStorageCompletenessContract` — direct/root filesystem browser, removable-volume discovery, direct archive index with metadata, and provider-only SAF input staging;
- `:app:verifyBkxLargeTreePerformanceSeal` — deterministic 768-file nested-tree performance/memory profile with a symlink escape trap;
- `:app:verifyBkxAccessibilityVisualContract` — Compose navigation accessibility assertions plus the opt-in real-device screenshot contract.

These are mandatory final preflight tasks, not documentation-only checks.


## Chaquopy build Python

Chaquopy 17 requires the build interpreter major/minor to match the packaged runtime.
BKX resolves an explicit build interpreter before Android configuration, preferring
`/data/data/com.termux/files/usr/bin/python3` on Termux and accepting Python 3.13 or
3.14. Override the executable with `-Pbkx.buildPython=/path/to/python` or the
`BKX_BUILD_PYTHON` environment variable. The packaged Chaquopy runtime is selected
to match the resolved interpreter.
