#!/usr/bin/env bash
set -euo pipefail
repo="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
out_root="${1:?usage: build-go-android-runtime.sh OUTPUT_JNILIBS_DIR}"
out="$out_root/arm64-v8a/libbkxgo.so"
commit="${BKX_BUILD_COMMIT:-dev}"
mkdir -p "$(dirname -- "$out")"
tmp="$(mktemp "${TMPDIR:-/tmp}/bkx-go-android-runtime.XXXXXX")"
trap 'rm -f "$tmp"' EXIT HUP INT TERM
(
  cd "$repo"
  CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build \
    -trimpath \
    -buildvcs=false \
    -ldflags="-buildid= -X bkx/internal/version.Commit=$commit" \
    -o "$tmp" ./cmd/bkx
)
meta="$(go version -m "$tmp")"
printf '%s\n' "$meta" | grep -Eq '^[[:space:]]*build[[:space:]]+GOOS=android$'
printf '%s\n' "$meta" | grep -Eq '^[[:space:]]*build[[:space:]]+GOARCH=arm64$'
magic="$(od -An -tx1 -N4 "$tmp" | tr -d '[:space:]')"
test "$magic" = 7f454c46 || { echo 'BKX Go Android runtime is not ELF' >&2; exit 1; }
byte_order="$(od -An -tu1 -j5 -N1 "$tmp" | tr -d '[:space:]')"
test "$byte_order" = 1 || { echo 'BKX Go Android runtime must be little-endian ELF' >&2; exit 1; }
machine="$(od -An -tu2 -j18 -N2 "$tmp" | tr -d '[:space:]')"
test "$machine" = 183 || { echo "BKX Go Android runtime e_machine=$machine, expected AArch64 (183)" >&2; exit 1; }
chmod 0755 "$tmp"
mv -f "$tmp" "$out"
trap - EXIT HUP INT TERM
printf '%s\n' "$out"
