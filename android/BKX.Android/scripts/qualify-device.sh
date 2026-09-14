#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ADB="${ADB:-adb}"
PACKAGE="com.mikeyphw.bkx.android"
TEST_PACKAGE="com.mikeyphw.bkx.android.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
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
  [ -s "$RELEASE_APK" ] || { echo "release APK not found: $RELEASE_APK" >&2; exit 66; }
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

"$ADB" "${SERIAL_ARGS[@]}" shell am instrument -w   -e bkxExactReleaseArtifact true   -e bkxDeviceQualification true   -e bkxVisualQualification true   "$TEST_PACKAGE/$RUNNER"

for screen in home backup archives activity more; do
  "$ADB" "${SERIAL_ARGS[@]}" exec-out run-as "$PACKAGE"     cat "files/qualification-screenshots/$screen.png" > "$OUT_DIR/$screen.png"
  if [ ! -s "$OUT_DIR/$screen.png" ]; then
    echo "Missing qualification screenshot: $screen" >&2
    exit 2
  fi
done

echo "BKX qualification evidence: $OUT_DIR"
