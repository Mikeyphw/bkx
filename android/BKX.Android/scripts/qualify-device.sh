#!/data/data/com.termux/files/usr/bin/bash
set -u

ADB="${ADB:-adb}"
PACKAGE="com.mikeyphw.bkx.android"
TEST_PACKAGE="com.mikeyphw.bkx.android.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
OUT_DIR="${BKX_QUALIFICATION_OUT:-$PWD/artifacts/qualification-screenshots}"
SERIAL_ARGS=()
if [ -n "${ANDROID_SERIAL:-}" ]; then SERIAL_ARGS=(-s "$ANDROID_SERIAL"); fi

"$ADB" "${SERIAL_ARGS[@]}" get-state >/dev/null

# MANAGE_EXTERNAL_STORAGE is a special app-op. This is suitable for a personal
# sideloaded qualification build and avoids making the test depend on UI taps.
"$ADB" "${SERIAL_ARGS[@]}" shell appops set "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true

cat <<'MSG'
BKX physical-device qualification requires your root manager to grant root to BKX.
If the root prompt appears, grant it. The test fails closed if root is unavailable.
The run also validates Compose navigation semantics and captures five product screenshots.
MSG

"$ADB" "${SERIAL_ARGS[@]}" shell am instrument -w \
  -e bkxDeviceQualification true \
  -e bkxVisualQualification true \
  "$TEST_PACKAGE/$RUNNER"

mkdir -p "$OUT_DIR"
for screen in home backup archives activity more; do
  "$ADB" "${SERIAL_ARGS[@]}" exec-out run-as "$PACKAGE" \
    cat "files/qualification-screenshots/$screen.png" > "$OUT_DIR/$screen.png"
  if [ ! -s "$OUT_DIR/$screen.png" ]; then
    echo "Missing qualification screenshot: $screen" >&2
    exit 2
  fi
done

echo "BKX qualification screenshots: $OUT_DIR"
