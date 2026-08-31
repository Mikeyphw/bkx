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

for marker in \
  ':app:testDebugUnitTest' \
  ':app:lintDebug' \
  ':app:connectedDebugAndroidTest' \
  ':app:bkxAndroidReleaseSeal'; do
  grep -R -- "$marker" .devtool.toml android/BKX.Android/docs >/dev/null || fail "missing release marker $marker"
done

MANIFEST=android/BKX.Android/app/src/main/AndroidManifest.xml
for marker in \
  'android.permission.POST_NOTIFICATIONS' \
  'android.permission.FOREGROUND_SERVICE' \
  'android.permission.FOREGROUND_SERVICE_DATA_SYNC' \
  'BkxOperationForegroundService' \
  'android:foregroundServiceType="dataSync"'; do
  grep -q -- "$marker" "$MANIFEST" || fail "manifest missing $marker"
done

if find android/BKX.Android -name 'bkx-release-signing.properties' -o -name '*.jks' -o -name '*.keystore' | grep -q .; then
  fail "release signing secret appears to be inside source tree"
fi

printf 'BKX Android release seal static preflight passed. Run Devtool final validation from RELEASE_VALIDATION.md.\n'
