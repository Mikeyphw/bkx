#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

command -v python3 >/dev/null 2>&1 || { echo "python3 is required for GO00 drift validation" >&2; exit 2; }
command -v go >/dev/null 2>&1 || { echo "Go 1.23+ is required for GO01 validation" >&2; exit 2; }

PYTHONPATH=src python3 tools/go_migration/export_contracts.py --check
PYTHONPATH=src python3 -m pytest -q tests/migration/test_go00_go01_contract.py

go test ./...
go vet ./...

tmp=${TMPDIR:-/tmp}/bkx-go00-go01-$$
rm -rf "$tmp"
mkdir -p "$tmp"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

go build -o "$tmp/bkx-linux" ./cmd/bkx
"$tmp/bkx-linux" --version
"$tmp/bkx-linux" contract check migration/contracts/v1

CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -o "$tmp/bkx-android-arm64" ./cmd/bkx
python3 - "$tmp/bkx-android-arm64" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
data = path.read_bytes()
assert data[:4] == b"\x7fELF", f"Android GO01 artifact is not ELF: {data[:4]!r}"
assert len(data) > 100_000, f"Android GO01 artifact is unexpectedly small: {len(data)} bytes"
print(f"Android ARM64 scaffold compile proof: {len(data)} bytes")
PY

printf '%s\n' 'BKX GO00/GO01 validation passed.'
