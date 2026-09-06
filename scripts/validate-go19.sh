#!/usr/bin/env bash
set -euo pipefail
repo="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$repo"
export TZ=UTC
probe_root="$(mktemp -d "$repo/.bkx-go19-validation.XXXXXX")"
trap 'rm -rf "$probe_root"' EXIT HUP INT TERM

echo '==> GO19 inherited GO18 Go-only compatibility/release seal'
bash ./scripts/validate-go18.sh

echo '==> GO19 final roadmap/Android/runtime audit'
go test ./internal/audit -count=1

echo '==> GO19 formatting, complete Go regression, and vet'
unformatted="$(gofmt -l cmd internal)"
test -z "$unformatted" || { printf 'gofmt required:\n%s\n' "$unformatted" >&2; exit 1; }
go test ./...
go vet ./...

echo '==> GO19 production binary + frozen contract check'
go build -trimpath -buildvcs=false -ldflags='-buildid= -X bkx/internal/version.Commit=go19-validation' -o "$probe_root/bkx" ./cmd/bkx
"$probe_root/bkx" contract check migration/contracts/v1
"$probe_root/bkx" --version | grep -Fx 'INFO: bkx 1.0.0'

echo '==> GO19 final audit document has no open migration promises'
grep -F '"open_promises": []' migration/audit/v1/final_audit.json >/dev/null
phase="$(sed -nE 's/^[[:space:]]*MigrationPhase[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' internal/version/version.go | head -n1)"
test "$phase" = GO19

echo 'BKX GO19 final migration/release audit seal passed.'
