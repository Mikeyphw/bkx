# BKX Android Final Validation

> **Roadmap status after AOV-13→16:** the previous AOV-12 release seal is now a historical baseline, not the final standalone-BKX qualification. Do not run the final release gate for this interim mega overlay. Apply AOV-13→16, AOV-17→20, and AOV-21→24 with `--no-validate`; AOV-25→32 will replace this document with the final embedded-engine/device qualification command.

## Historical AOV-12 baseline

AOV-12 is the final roadmap overlay. The SDK hotfix pins the app to the latest SDK package the Termux/chroot validator can install reliably.

Optional static preflight, which does not replace Devtool validation:

```bash
bash android/BKX.Android/tools/bkx-android-release-seal.sh
```

Apply the SDK hotfix without validation:

```bash
ARTIFACT="$HOME/Downloads/bkx_android_sdk36_validation_hotfix_v2.zip"
devtool --copy -y -r "$HOME/Code/bkx" apply-overlay "$ARTIFACT"   --no-validate   --commit "Pin BKX Android validation to installable SDK 36"
```

Then run final validation:

```bash
export ANDROID_SERIAL=127.0.0.1:5555
export ADB_SERVER_SOCKET=tcp:127.0.0.1:5037

devtool --copy -y -r "$HOME/Code/bkx" \
  --target bkx_android \
  validate \
  --sequential \
  --complete \
  --keep-gradle-daemon

# Final named release-seal contract. With a connected device the
# sequential pipeline has already run instrumentation, so its
# dependencies should be cached/up-to-date here.
devtool --copy -y -r "$HOME/Code/bkx" \
  --target bkx_android \
  validate \
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

## SDK availability policy

The final validation target intentionally uses `compileSdk = 36`, `targetSdk = 36`, and Devtool Android platform `36`. The validator reported that `sdkmanager` could not resolve `platforms;android-37`, so API 37 is not a valid validation dependency in this environment. Lint's newer-version suggestions are suppressed for SDK/dependency availability checks only; contract, Kotlin, Android, unit, lint, assemble, instrumentation, and release-seal gates still run.

## Why foreground service and notification checks exist

BKX backup/restore/transfer operations may be long-running and user-visible. Android 13+ requires runtime notification permission for non-exempt notifications, including foreground-service notifications. Android 15+ also applies cumulative time limits to `dataSync` foreground services, so Android must expose progress, cancellation, and reconnect instead of assuming an immortal worker.