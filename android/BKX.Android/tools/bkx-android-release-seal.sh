#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"
cd "$REPO"

fail() {
  printf 'BKX Android release seal failed: %s\n' "$*" >&2
  exit 1
}

[[ -f .devtool.toml ]] || fail "missing .devtool.toml at BKX repo root"
[[ -f android/BKX.Android/docs/RELEASE_VALIDATION.md ]] || fail "missing final validation guide"
[[ -f android/BKX.Android/docs/SETUP_GUIDE.md ]] || fail "missing setup guide"
[[ -f android/BKX.Android/docs/SIGNING_PLACEHOLDERS.md ]] || fail "missing signing placeholder guide"
[[ -f android/BKX.Android/gradle/bkx-release-signing.properties.example ]] || fail "missing signing placeholder example"
[[ -f android/BKX.Android/tools/setup-personal-signing.sh ]] || fail "missing permanent signing setup helper"
[[ -f android/BKX.Android/tools/print-personal-signing-fingerprint.sh ]] || fail "missing signing fingerprint helper"
[[ -f scripts/verify-native-page-size.go ]] || fail "missing Go-native 16 KiB ELF/APK verifier"
[[ -f android/BKX.Android/app/proguard-rules.pro ]] || fail "missing release R8 keep rules"
[[ -f android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/release/BkxReleaseArtifactPolicy.kt ]] || fail "missing BR35 release artifact policy"

for marker in \
  ':app:testEmbeddedDebugUnitTest' \
  ':app:testLiteDebugUnitTest' \
  ':app:lintEmbeddedDebug' \
  ':app:lintLiteDebug' \
  ':app:connectedEmbeddedDebugAndroidTest' \
  ':app:verifyBkxRcloneRemoteTransportContract' \
  ':app:verifyBkx16KbNativePageSizeContract' \
  ':app:verifyBkxGoogleDriveProductContract' \
  ':app:verifyBkxAutomationProductContract' \
  ':app:verifyBkxCloudProductReleaseSeal' \
  ':app:verifyBkxPersonalSigningContract' \
  ':app:verifyBkxReleaseMergedManifestContract' \
  ':app:verifyBkxReleasePackagingSigningNativeRuntimeSeal' \
  ':app:lintEmbeddedRelease' \
  ':app:lintLiteRelease' \
  ':app:bkxAndroidReleaseSeal'; do
  grep -R -- "$marker" .devtool.toml android/BKX.Android/docs >/dev/null || fail "missing release marker $marker"
done

MANIFEST=android/BKX.Android/app/src/main/AndroidManifest.xml
for marker in \
  'android.permission.INTERNET' \
  'android.permission.POST_NOTIFICATIONS' \
  'android.permission.FOREGROUND_SERVICE' \
  'android.permission.FOREGROUND_SERVICE_DATA_SYNC' \
  'BkxOperationForegroundService' \
  'android:foregroundServiceType="dataSync"'; do
  grep -q -- "$marker" "$MANIFEST" || fail "manifest missing $marker"
done

for file in \
  android/BKX.Android/docs/AUTOMATION_TASKER.md \
  docs/checklists/CHECKLIST_ANDROID_AUTOMATION.md \
  scripts/verify-android-automation.sh \
  docs/guide/05_RCLONE_REMOTE_TRANSPORT.md \
  docs/guide/06_GOOGLE_DRIVE_PRODUCT.md \
  docs/guide/07_ANDROID_PRODUCT_ROADMAP_SEAL.md \
  docs/checklists/CHECKLIST_ANDROID_REMOTE_TRANSPORT.md \
  docs/checklists/CHECKLIST_ANDROID_GOOGLE_DRIVE.md \
  android/BKX.Android/docs/OV56_GOOGLE_DRIVE_RELEASE_ACCEPTANCE.md; do
  [[ -f "$file" ]] || fail "missing remote/cloud release evidence $file"
done

if find android/BKX.Android -name 'bkx-release-signing.properties' -o -name '*.jks' -o -name '*.keystore' -o -name '*.p12' -o -name '*.pfx' | grep -q .; then
  fail "release signing secret appears to be inside source tree"
fi

for marker in   'bkxAndroidReleasePreflightSeal'   'bkxAndroidReleasePackageSeal'   'bkxAndroidReleaseInstrumentationSeal'   'bkxAndroidReleaseFinalSeal'   'bkxResolvedBuildCommit'   'isMinifyEnabled = true'   'isShrinkResources = true'; do
  grep -R -- "$marker" android/BKX.Android/app/build.gradle.kts build-logic/android-verification.gradle.kts >/dev/null || fail "missing BR35 release-packaging marker $marker"
done

if grep -A40 'preflight = \[' .devtool.toml | grep -B40 '\]' | grep -q ':app:verifyBkx16KbNativePageSizeContract'; then
  fail "16 KiB APK verification must not run in cheap preflight"
fi

printf 'BKX Android release seal static preflight passed. Run Devtool final validation from RELEASE_VALIDATION.md.\n'
