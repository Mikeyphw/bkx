#!/usr/bin/env bash
# Read-only REAL device extraction/loader probe. No app mutation, no arbitrary shell.
set -euo pipefail
serial="${1:?usage: bkx-rel09-device-runtime-probe.sh EXACT_ADB_SERIAL}"
[[ "$serial" =~ ^[A-Za-z0-9_.:-]{1,100}$ ]] || { echo 'Invalid ADB serial' >&2; exit 2; }
package=com.mikeyphw.bkx.android
adb -s "$serial" get-state | grep -qx device
adb -s "$serial" shell getprop ro.product.cpu.abilist
# pm path only supplies a constrained installed base.apk path, not executable input.
mapfile -t apkpaths < <(adb -s "$serial" shell pm path "$package" | tr -d '\r' | sed -n 's/^package://p')
[[ ${#apkpaths[@]} -eq 1 ]] || { echo 'Expected one installed BKX base APK' >&2; exit 2; }
base="${apkpaths[0]}"
[[ "$base" =~ ^/data/app/[A-Za-z0-9_+=~.-]+/[A-Za-z0-9_+=~.-]+/base\.apk$ ]] || {
  echo 'Untrusted installed base.apk path' >&2; exit 2;
}
native="${base%/base.apk}/lib/arm64"
[[ "$native" =~ ^/data/app/[A-Za-z0-9_+=~./-]+/lib/arm64$ ]] || exit 2
printf 'SOURCE_DEVICE_SERIAL=%s\nINSTALLED_BASE_APK=%s\nEXPECTED_NATIVE_DIR=%s\n' "$serial" "$base" "$native"
adb -s "$serial" shell su -c id
for name in libbkxgo.so libbkxrclone_exec.so; do
  case "$name" in libbkxgo.so|libbkxrclone_exec.so) :;; *) exit 2;; esac
  echo "===== $name ====="
  # Fixed package-derived path only; never interpolate caller commands.
  adb -s "$serial" shell su -c "ls -l $native/$name" || true
  adb -s "$serial" shell su -c "sha256sum $native/$name" || true
done
echo '===== Android dynamic linker ====='
adb -s "$serial" shell su -c 'ls -l /system/bin/linker64' || true
printf 'READ_ONLY_RUNTIME_PROBE_COMPLETE; results are observations, NOT release qualification\n'
