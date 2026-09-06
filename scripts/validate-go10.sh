#!/usr/bin/env bash
set -euo pipefail
repo="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$repo"
export PYTHONPATH="$repo/src${PYTHONPATH:+:$PYTHONPATH}"
export TZ=UTC

echo '==> GO00-GO09 regression gates'
bash ./scripts/validate-go09.sh

echo '==> GO10 migration source contracts'
python3 -m pytest -q tests/migration/test_go10_contract.py

echo '==> GO10 focused Go packages'
go test ./internal/services ./internal/archiveio ./internal/backupops ./internal/transferops ./internal/runstore ./internal/fsutil ./cmd/bkx ./internal/version

echo '==> GO10 Go vet'
go vet ./...

probe_root="${TMPDIR:-/tmp}/bkx-go10-$$"
mkdir -p "$probe_root"
trap 'rm -rf "$probe_root"' EXIT HUP INT TERM

echo '==> GO10 Linux service parity binary'
go build -o "$probe_root/bkx" ./cmd/bkx
"$probe_root/bkx" contract check migration/contracts/v1

echo '==> GO10 Python <-> Go transport-neutral service parity'
python3 tools/go_migration/verify_go10_service_parity.py --go-binary "$probe_root/bkx"

echo '==> GO10 service registry equals frozen advertised operations'
python3 - "$probe_root/bkx" <<'PYOPS'
import json
from pathlib import Path
import subprocess
import sys
binary = Path(sys.argv[1])
frozen = json.loads(Path("migration/contracts/v1/bridge.json").read_text())
expected = list(frozen["advertisedOperations"])
actual = json.loads(subprocess.check_output([str(binary), "migration", "service", "operations"], text=True))
if actual != expected:
    raise SystemExit(f"service operation registry drift: {actual!r} != {expected!r}")
print(f"GO10 service registry: {len(actual)} frozen operations")
PYOPS

echo '==> GO10 product/service boundary stays fail-closed'
if "$probe_root/bkx" service dispatch filesystem.stat '{}' - - >/dev/null 2>&1; then
  echo 'Go migration binary unexpectedly accepted product service command' >&2
  exit 1
fi
if "$probe_root/bkx" backup plan >/dev/null 2>&1; then
  echo 'Go migration binary unexpectedly accepted production backup command' >&2
  exit 1
fi

echo '==> GO10 Android ARM64 deterministic architecture gate'
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -o "$probe_root/bkx-android-arm64" ./cmd/bkx
meta="$(go version -m "$probe_root/bkx-android-arm64")"
printf '%s\n' "$meta" | grep -Eq '^[[:space:]]*build[[:space:]]+GOOS=android$'
printf '%s\n' "$meta" | grep -Eq '^[[:space:]]*build[[:space:]]+GOARCH=arm64$'
python3 - "$probe_root/bkx-android-arm64" <<'PYARCH'
from pathlib import Path
import sys
p = Path(sys.argv[1]).read_bytes()[:20]
if len(p) < 20 or p[:4] != b"\x7fELF": raise SystemExit("Android Go binary is not ELF")
byteorder = "little" if p[5] == 1 else "big" if p[5] == 2 else None
if byteorder is None: raise SystemExit("Android Go binary has invalid ELF byte order")
machine = int.from_bytes(p[18:20], byteorder)
if machine != 183: raise SystemExit(f"Android Go binary e_machine={machine}, expected 183 (AArch64)")
print("Android Go binary metadata/ELF architecture: android/arm64 AArch64")
PYARCH

echo 'BKX GO10 migration gates passed.'
