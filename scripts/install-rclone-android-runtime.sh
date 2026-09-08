#!/usr/bin/env bash
set -euo pipefail

# Install the official rclone Android ARM64 testbuild into a generated JNI directory.
# rclone publishes these Android executables as part of its Android CI/release lane.
# The executable is deliberately named libbkxrclone_exec.so so AGP extracts it into
# ApplicationInfo.nativeLibraryDir, where BKX can launch it directly.

readonly RCLONE_VERSION="1.75.1"
readonly RCLONE_ANDROID_API="21"
readonly RCLONE_ANDROID_ARCH="armv8a"
readonly RCLONE_ARCHIVE="rclone-android-${RCLONE_ANDROID_API}-${RCLONE_ANDROID_ARCH}.gz"
readonly RCLONE_URL="https://beta.rclone.org/v${RCLONE_VERSION}/testbuilds/${RCLONE_ARCHIVE}"

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
# shellcheck source=scripts/rclone-runtime-contract.sh
source "$script_dir/rclone-runtime-contract.sh"

fail() {
    local code=$1
    local stage=$2
    shift 2
    printf 'BKX rclone runtime install failed [%s]: %s\n' "$stage" "$*" >&2
    exit "$code"
}

if [[ $# -ne 1 ]]; then
    echo "usage: $0 <jni-output-directory>" >&2
    exit 64
fi

output_root=$1
output_dir="$output_root/arm64-v8a"
cache_root="${BKX_RCLONE_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/bkx/rclone/v${RCLONE_VERSION}}"
archive="$cache_root/$RCLONE_ARCHIVE"
target="$output_dir/libbkxrclone_exec.so"
tmp_target="$target.tmp.$$"
mkdir -p "$cache_root" "$output_dir"
trap 'rm -f "$tmp_target"' EXIT

fetch() {
    local tmp="$archive.download.$$"
    rm -f "$tmp"
    printf 'BKX rclone runtime: fetching %s\n' "$RCLONE_URL" >&2
    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --retry 3 --retry-delay 2 --output "$tmp" "$RCLONE_URL" || {
            rm -f "$tmp"
            fail 69 fetch 'curl could not fetch the pinned Android runtime'
        }
    elif command -v wget >/dev/null 2>&1; then
        wget --tries=3 --timeout=30 --output-document="$tmp" "$RCLONE_URL" || {
            rm -f "$tmp"
            fail 69 fetch 'wget could not fetch the pinned Android runtime'
        }
    else
        fail 69 fetch 'curl or wget is required to fetch the pinned Android runtime'
    fi
    [[ -s "$tmp" ]] || fail 69 fetch 'downloaded archive is empty'
    mv -f "$tmp" "$archive"
}

if [[ ! -s "$archive" ]]; then
    fetch
else
    printf 'BKX rclone runtime: using cached archive %s\n' "$archive" >&2
fi

# The testbuild directory does not publish a checksum manifest next to these Android
# executables. Controlled builds may supply an independently obtained digest pin.
if [[ -n "${BKX_RCLONE_ANDROID_SHA256:-}" ]]; then
    actual=$(sha256sum "$archive" | awk '{print $1}')
    [[ "$actual" == "$BKX_RCLONE_ANDROID_SHA256" ]] || {
        printf 'expected: %s\nactual:   %s\n' "$BKX_RCLONE_ANDROID_SHA256" "$actual" >&2
        fail 65 checksum 'rclone Android runtime checksum mismatch'
    }
    printf 'BKX rclone runtime: optional SHA-256 pin matched\n' >&2
fi

printf 'BKX rclone runtime: validating gzip payload\n' >&2
if ! gzip -t "$archive"; then
    # A partially written cache must never become sticky. Re-fetch once before
    # reporting a hard data-integrity failure.
    printf 'BKX rclone runtime: cached gzip is invalid; refetching once\n' >&2
    rm -f "$archive"
    fetch
    gzip -t "$archive" || fail 65 gzip 'downloaded rclone archive failed gzip integrity validation'
fi

gzip -dc "$archive" > "$tmp_target" || fail 65 decompress 'could not decompress rclone Android runtime'
chmod 0755 "$tmp_target"

printf 'BKX rclone runtime: validating ELF and embedded version marker\n' >&2
python3 - "$tmp_target" "$RCLONE_VERSION" <<'PY' || exit_code=$?
from pathlib import Path
import struct
import sys
p = Path(sys.argv[1])
version = sys.argv[2]
data = p.read_bytes()
if len(data) < 20 or data[:4] != b"\x7fELF":
    raise SystemExit("rclone Android runtime is not an ELF executable")
if data[4] != 2:
    raise SystemExit("rclone Android runtime must be ELF64")
if data[5] != 1:
    raise SystemExit("rclone Android runtime must be little-endian")
elf_type = struct.unpack_from("<H", data, 16)[0]
if elf_type != 3:
    raise SystemExit(f"rclone Android runtime must be position-independent ET_DYN (e_type={elf_type})")
machine = struct.unpack_from("<H", data, 18)[0]
if machine != 183:
    raise SystemExit(f"rclone Android runtime is not AArch64 (e_machine={machine})")
# Official Android builds receive -X github.com/rclone/rclone/fs.Version=<VERSION>.
# That linker value is stored as the bare version string, not necessarily as the
# formatted CLI phrase "rclone v..." in raw binary strings.
needle = f"v{version}".encode("ascii")
if needle not in data:
    raise SystemExit(f"rclone Android runtime is missing embedded version marker {needle.decode()}")
PY
if [[ ${exit_code:-0} -ne 0 ]]; then
    fail 65 elf "ELF/version-marker validation failed (python exit ${exit_code})"
fi

printf 'BKX rclone runtime: validating Go Android/ARM64 build metadata\n' >&2
build_info=$(go version -m "$tmp_target" 2>&1) || {
    printf '%s\n' "$build_info" >&2
    fail 66 build-info 'could not inspect rclone Go build metadata'
}
if ! bkx_rclone_validate_build_info "$build_info"; then
    printf '%s\n' "$build_info" >&2
    fail 65 build-info 'rclone runtime build metadata is not the official Android/ARM64 shape'
fi

# On an Android build host (Termux/Devtool), execute the exact downloaded runtime.
# This is authoritative for the human-facing version string and catches dynamic
# linker/API problems before APK packaging. Non-Android CI keeps the ELF, embedded
# linker value, and Go build-metadata proofs above without executing Android code.
if [[ -d /system && -e /system/bin/linker64 ]]; then
    printf 'BKX rclone runtime: executing on-host version smoke test\n' >&2
    version_output=$("$tmp_target" version 2>&1) || {
        printf '%s\n' "$version_output" >&2
        fail 66 execute 'rclone Android runtime failed its on-host version smoke test'
    }
    if ! bkx_rclone_validate_version_output "$version_output" "$RCLONE_VERSION"; then
        printf '%s\n' "$version_output" >&2
        fail 65 identity 'unexpected rclone Android version output'
    fi
else
    printf 'BKX rclone runtime: non-Android host; executable smoke test skipped after static identity proofs\n' >&2
fi

mv -f "$tmp_target" "$target"
trap - EXIT
printf 'BKX rclone runtime: packaged v%s -> %s\n' "$RCLONE_VERSION" "$target" >&2
