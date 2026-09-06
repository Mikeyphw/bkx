#!/usr/bin/env bash
set -euo pipefail
repo="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$repo"
export TZ=UTC
probe_root="$(mktemp -d "$repo/.bkx-validation.XXXXXX")"
trap 'rm -rf "$probe_root"' EXIT HUP INT TERM

echo '==> BKX immutable compatibility/archive evidence'
( cd migration/fixtures/v1 && sha256sum -c SHA256SUMS )
( cd migration/archive && sha256sum -c SHA256SUMS )

echo '==> BKX formatting, full Go tests, and vet'
unformatted="$(gofmt -l cmd internal)"
test -z "$unformatted" || { printf 'gofmt required:\n%s\n' "$unformatted" >&2; exit 1; }
go test ./...
go vet ./...

echo '==> BKX frozen contract + final audit + maintenance baseline'
go build -trimpath -buildvcs=false -ldflags='-buildid= -X bkx/internal/version.Commit=maintenance-validation' -o "$probe_root/bkx" ./cmd/bkx
"$probe_root/bkx" contract check migration/contracts/v1
"$probe_root/bkx" --version | grep -Fx 'INFO: bkx 1.0.0'
go test ./internal/audit ./internal/compatfixtures -count=1

echo '==> BKX active repository ownership and retired migration scaffolding'
for retired in scripts/validate-go17.sh scripts/validate-go18.sh scripts/validate-go19.sh; do
  test ! -e "$retired" || { echo "retired validator remains: $retired" >&2; exit 1; }
done
if grep -Eq 'go-native-migration|func NewWithRuntime|func New\(store \*runstore\.Store\)' internal/bridge/bridge.go; then
  echo 'retired migration bridge identity/constructor remains' >&2
  exit 1
fi
if grep -Eq 'args\[0\][[:space:]]*==[[:space:]]*"migration"|Go migration scaffold' cmd/bkx/main.go; then
  echo 'retired migration CLI scaffold remains' >&2
  exit 1
fi
phase="$(sed -nE 's/^[[:space:]]*MigrationPhase[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' internal/version/version.go | head -n1)"
test "$phase" = GO20

echo '==> BKX deterministic host + Android ARM64 release'
BKX_BUILD_COMMIT=maintenance-validation bash ./scripts/build-go-release.sh "$probe_root/release-a" >/dev/null
BKX_BUILD_COMMIT=maintenance-validation bash ./scripts/build-go-release.sh "$probe_root/release-b" >/dev/null
diff -u "$probe_root/release-a/SHA256SUMS" "$probe_root/release-b/SHA256SUMS"
while read -r _ rel; do cmp "$probe_root/release-a/$rel" "$probe_root/release-b/$rel"; done < "$probe_root/release-a/SHA256SUMS"
meta="$(go version -m "$probe_root/release-a/android/arm64-v8a/libbkxgo.so")"
printf '%s\n' "$meta" | grep -Eq '^[[:space:]]*build[[:space:]]+GOOS=android$'
printf '%s\n' "$meta" | grep -Eq '^[[:space:]]*build[[:space:]]+GOARCH=arm64$'
machine="$(od -An -tu2 -j18 -N2 "$probe_root/release-a/android/arm64-v8a/libbkxgo.so" | tr -d '[:space:]')"
test "$machine" = 183

echo 'BKX GO20 post-migration maintenance baseline passed.'
