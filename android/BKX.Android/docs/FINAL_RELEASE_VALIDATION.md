
# BKX Android Final Release Validation

AOV-12 is the final roadmap overlay. After applying this overlay, run validation through Devtool, not raw Gradle.

Optional static preflight, which does not replace Devtool validation:

```bash
bash android/BKX.Android/tools/bkx-android-release-seal.sh
```

```bash
ARTIFACT="$HOME/Downloads/bkx_android_aov11_aov12_instrumentation_release_seal_v2.zip"
devtool --copy -y -r "$HOME/Code/bkx" apply-overlay "$ARTIFACT" \
  --validate \
  --task :app:testDebugUnitTest \
  --task :app:lintDebug \
  --task :app:connectedDebugAndroidTest \
  --task :app:assembleDebug \
  --task :app:assembleRelease \
  --task :app:bkxAndroidReleaseSeal \
  --commit "Seal BKX Android instrumentation and release gates"
```

If you choose to keep overlay application and final validation separate, apply first with `--no-validate`, then run:

```bash
devtool --copy -y -r "$HOME/Code/bkx" validate \
  --task :app:testDebugUnitTest \
  --task :app:lintDebug \
  --task :app:connectedDebugAndroidTest \
  --task :app:assembleDebug \
  --task :app:assembleRelease \
  --task :app:bkxAndroidReleaseSeal
```

Release is blocked unless all gates pass. `:app:connectedDebugAndroidTest` intentionally requires an attached/emulated Android device because AOV-11 promises lifecycle/notification instrumentation coverage.

## Included release gates

- local unit tests for bridge/result/adversarial contracts;
- Android lint;
- instrumentation tests for activity recreation and notification/reconnect intent contracts;
- debug and release package assembly;
- release docs presence checks;
- signing placeholder checks that fail if real keystore material is found in the source tree;
- manifest checks for notification and foreground-service declarations.

## Why foreground service and notification checks exist

BKX backup/restore/transfer operations may be long-running and user-visible. Android 13+ requires runtime notification permission for non-exempt notifications, including foreground-service notifications. Android 15+ also applies cumulative time limits to `dataSync` foreground services, so Android must expose progress, cancellation, and reconnect instead of assuming an immortal worker.
