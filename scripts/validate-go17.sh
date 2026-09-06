#!/usr/bin/env bash
set -euo pipefail
repo="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$repo"
export TZ=UTC
probe_root="$(mktemp -d "$repo/.bkx-go17-validation.XXXXXX")"
trap 'rm -rf "$probe_root"' EXIT HUP INT TERM

echo '==> GO17 immutable fixture/archive integrity'
( cd migration/fixtures/v1 && sha256sum -c SHA256SUMS )
( cd migration/archive && sha256sum -c SHA256SUMS )

echo '==> GO17 complete Go regression + vet (no Python reference execution)'
go test ./...
go vet ./...

echo '==> GO17 frozen GO00 contracts + GO17 reference oracle'
go build -trimpath -buildvcs=false -ldflags='-buildid= -X bkx/internal/version.Commit=go17-validation' -o "$probe_root/bkx" ./cmd/bkx
"$probe_root/bkx" contract check migration/contracts/v1
go test ./internal/compatfixtures -count=1

echo '==> GO17 production/release path is Python-free'
for file in scripts/build-go-release.sh scripts/build-go-android-runtime.sh .github/workflows/ci.yml; do
  if grep -Eq 'PYTHONPATH|src/bkx|python3|setup-python|pip install|pytest' "$file"; then
    echo "GO17 active validation/release path still depends on Python: $file" >&2
    exit 1
  fi
done
if grep -Eq 'src/bkx|python3|PYTHONPATH|pyproject\.toml' build-logic/android-verification.gradle.kts android/BKX.Android/app/build.gradle.kts; then
  echo 'GO17 Android production validation still depends on the Python reference tree' >&2
  exit 1
fi

echo '==> GO17 deterministic release regression'
BKX_BUILD_COMMIT=go17-validation bash ./scripts/build-go-release.sh "$probe_root/release-a" >/dev/null
BKX_BUILD_COMMIT=go17-validation bash ./scripts/build-go-release.sh "$probe_root/release-b" >/dev/null
diff -u "$probe_root/release-a/SHA256SUMS" "$probe_root/release-b/SHA256SUMS"
while read -r _ rel; do cmp "$probe_root/release-a/$rel" "$probe_root/release-b/$rel"; done < "$probe_root/release-a/SHA256SUMS"
meta="$(go version -m "$probe_root/release-a/android/arm64-v8a/libbkxgo.so")"
printf '%s\n' "$meta" | grep -Eq '^[[:space:]]*build[[:space:]]+GOOS=android$'
printf '%s\n' "$meta" | grep -Eq '^[[:space:]]*build[[:space:]]+GOARCH=arm64$'
machine="$(od -An -tu2 -j18 -N2 "$probe_root/release-a/android/arm64-v8a/libbkxgo.so" | tr -d '[:space:]')"
test "$machine" = 183

echo 'BKX GO17 immutable Go-owned compatibility oracle / dynamic Python parity retirement gates passed.'
