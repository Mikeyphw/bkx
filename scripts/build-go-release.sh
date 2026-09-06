#!/usr/bin/env bash
set -euo pipefail
repo="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
out="${1:-$repo/dist/go-release}"
version="$(sed -n 's/^[[:space:]]*Version[[:space:]]*=[[:space:]]*"\([^"]*\)"/\1/p' "$repo/internal/version/version.go" | head -n1)"
test -n "$version" || { echo 'unable to resolve BKX Go version' >&2; exit 1; }

host_os="$(go env GOHOSTOS)"
host_arch="$(go env GOHOSTARCH)"
commit="${BKX_BUILD_COMMIT:-}"
if [ -z "$commit" ]; then
  commit="$(git -C "$repo" rev-parse --verify HEAD 2>/dev/null || printf '%s' dev)"
fi
case "$commit" in
  *[!A-Za-z0-9._+-]*) echo "invalid BKX_BUILD_COMMIT: $commit" >&2; exit 2 ;;
esac

parent="$(dirname -- "$out")"
mkdir -p "$parent"
stage="$(mktemp -d "$parent/.bkx-release.XXXXXX")"
trap 'rm -rf "$stage"' EXIT HUP INT TERM
host_name="bkx-${version}-${host_os}-${host_arch}"

(
  cd "$repo"
  CGO_ENABLED=0 GOOS="$host_os" GOARCH="$host_arch" go build \
    -trimpath \
    -buildvcs=false \
    -ldflags="-buildid= -X bkx/internal/version.Commit=$commit" \
    -o "$stage/$host_name" ./cmd/bkx
)
chmod 0755 "$stage/$host_name"
BKX_BUILD_COMMIT="$commit" bash "$repo/scripts/build-go-android-runtime.sh" "$stage/android" >/dev/null

"$stage/$host_name" --version >/dev/null
android_runtime="$stage/android/arm64-v8a/libbkxgo.so"
test -x "$android_runtime"

(
  cd "$stage"
  sha256sum "$host_name" "android/arm64-v8a/libbkxgo.so" | LC_ALL=C sort -k2 > SHA256SUMS
  sha256sum -c SHA256SUMS >/dev/null
)

rm -rf "$out"
mv "$stage" "$out"
trap - EXIT HUP INT TERM
printf '%s\n' "$out"
