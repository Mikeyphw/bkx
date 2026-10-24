#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ADB="${ADB:-adb}"
PACKAGE="com.mikeyphw.bkx.android"
TEST_PACKAGE="com.mikeyphw.bkx.android.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
APKSIGNER="${APKSIGNER:-apksigner}"
# Share the strict verified-certificate parser with REL-07 Python preflight.
CERT_TOOL="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)/scripts/bkx_apksigner_certificate.py"
# Explicitly exclude the separate Skybind operator-owned 12-case campaign.
RELEASE_CLASSES="com.mikeyphw.bkx.android.release.ReleaseQualificationInstrumentationTest,com.mikeyphw.bkx.android.release.ProductAccessibilityVisualQualificationTest,com.mikeyphw.bkx.android.embedded.GoNativeRuntimeInstrumentationTest"
OUT_DIR="${BKX_QUALIFICATION_OUT:-$PWD/artifacts/qualification-screenshots}"
RELEASE_APK=""
ANDROID_TEST_APK=""
SERIAL_ARGS=()
if [ -n "${ANDROID_SERIAL:-}" ]; then SERIAL_ARGS=(-s "$ANDROID_SERIAL"); fi

usage() {
  cat >&2 <<'MSG'
usage: qualify-device.sh [--release-apk PATH] [--android-test-apk PATH]

When --release-apk is supplied, BKX installs and qualifies that exact release
artifact instead of whatever package is already installed. Supplying
--android-test-apk installs the matching instrumentation APK before running the
qualification lane against the release package.
MSG
}

while [ $# -gt 0 ]; do
  case "$1" in
    --release-apk)
      RELEASE_APK="${2:?missing value for --release-apk}"; shift 2 ;;
    --android-test-apk)
      ANDROID_TEST_APK="${2:?missing value for --android-test-apk}"; shift 2 ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      usage; echo "unknown argument: $1" >&2; exit 64 ;;
  esac
done

"$ADB" "${SERIAL_ARGS[@]}" get-state >/dev/null

if [ -n "$RELEASE_APK" ]; then
  if [ -z "$ANDROID_TEST_APK" ]; then
    echo "An explicitly matching, same-signer instrumentation APK is required for a signed release" >&2
    exit 64
  fi
  [ -s "$RELEASE_APK" ] || { echo "release APK not found: $RELEASE_APK" >&2; exit 66; }
  [ -s "$ANDROID_TEST_APK" ] || { echo "Missing test APK" >&2; exit 66; }
  if ! app_cert=$(python3 "$CERT_TOOL" --apksigner "$APKSIGNER" --apk "$RELEASE_APK"); then
    echo "Release APK signer verification failed" >&2; exit 65
  fi
  if ! test_cert=$(python3 "$CERT_TOOL" --apksigner "$APKSIGNER" --apk "$ANDROID_TEST_APK"); then
    echo "Instrumentation APK signer verification failed" >&2; exit 65
  fi
  if ! [[ "$app_cert" =~ ^[a-fA-F0-9]{64}$ && "$app_cert" == "$test_cert" ]]; then
    echo "Refusing instrumentation APK with mismatched signing identity" >&2
    exit 65
  fi
  "$ADB" "${SERIAL_ARGS[@]}" install -r "$RELEASE_APK" >/dev/null
fi
if [ -n "$ANDROID_TEST_APK" ]; then
  [ -s "$ANDROID_TEST_APK" ] || { echo "Android test APK not found: $ANDROID_TEST_APK" >&2; exit 66; }
  "$ADB" "${SERIAL_ARGS[@]}" install -r "$ANDROID_TEST_APK" >/dev/null
fi

# MANAGE_EXTERNAL_STORAGE is a special app-op. This is suitable for a personal
# sideloaded qualification build and avoids making the test depend on UI taps.
"$ADB" "${SERIAL_ARGS[@]}" shell appops set "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true

mkdir -p "$OUT_DIR"
"$ADB" "${SERIAL_ARGS[@]}" shell pm path "$PACKAGE" > "$OUT_DIR/release-package-path.txt"
"$ADB" "${SERIAL_ARGS[@]}" shell dumpsys package "$PACKAGE" > "$OUT_DIR/release-installed-package.txt"
if [ -n "$RELEASE_APK" ]; then
  sha256sum "$RELEASE_APK" > "$OUT_DIR/release-apk.sha256"
fi

cat <<'MSG'
BKX physical-device qualification requires your root manager to grant root to BKX.
If the root prompt appears, grant it. The test fails closed if root is unavailable.
The run validates the exact installed release artifact when --release-apk is supplied,
then exercises Compose navigation semantics and captures five product screenshots.
MSG

# am instrument can print a failing test with exit code zero. Require an explicit
# successful terminal status and retain its raw host-side log as evidence.
INSTRUMENT_LOG="$OUT_DIR/release-instrumentation.txt"
"$ADB" "${SERIAL_ARGS[@]}" shell am instrument -w \
  -e class "$RELEASE_CLASSES" \
  -e bkxExactReleaseArtifact true \
  -e bkxDeviceQualification true \
  -e bkxVisualQualification true \
  "$TEST_PACKAGE/$RUNNER" | tee "$INSTRUMENT_LOG"
if ! grep -Eq '^OK \([0-9]+ tests?\)' "$INSTRUMENT_LOG" || \
   ! grep -q 'INSTRUMENTATION_CODE: -1' "$INSTRUMENT_LOG" || \
   grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED' "$INSTRUMENT_LOG"; then
  echo "Missing successful AndroidJUnit instrumentation terminal receipt" >&2
  exit 2
fi

# Production APKs are not debuggable: run-as is correctly unavailable.
# The operator already authorized root for this explicit qualification.
"$ADB" "${SERIAL_ARGS[@]}" shell su -c id | grep -q 'uid=0'
for screen in home backup archives activity more; do
  "$ADB" "${SERIAL_ARGS[@]}" exec-out su -c \
    "cat /data/user/0/$PACKAGE/files/qualification-screenshots/$screen.png" > "$OUT_DIR/$screen.png"
  if [ ! -s "$OUT_DIR/$screen.png" ]; then
    echo "Missing qualification screenshot: $screen" >&2
    exit 2
  fi
done

echo "BKX qualification evidence: $OUT_DIR"
