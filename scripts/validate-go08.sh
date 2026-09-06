#!/usr/bin/env bash
set -euo pipefail
repo="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$repo"
export PYTHONPATH="$repo/src${PYTHONPATH:+:$PYTHONPATH}"
export TZ=UTC

echo '==> GO00-GO07 regression gates'
bash ./scripts/validate-go07.sh

echo '==> GO08 focused Go packages'
go test ./internal/backupops ./internal/archiveio ./internal/runstore ./internal/validation ./cmd/bkx ./internal/version

echo '==> GO08 Go vet'
go vet ./...

probe_root="${TMPDIR:-/tmp}/bkx-go08-$$"
mkdir -p "$probe_root"
trap 'rm -rf "$probe_root"' EXIT HUP INT TERM

echo '==> GO08 Linux backup parity binary'
go build -o "$probe_root/bkx" ./cmd/bkx
"$probe_root/bkx" contract check migration/contracts/v1

echo '==> GO08 Python <-> Go backup plan/create/run-history parity'
python3 tools/go_migration/verify_go08_parity.py --go-binary "$probe_root/bkx"

echo '==> GO08 production backup boundary stays fail-closed'
if "$probe_root/bkx" backup create /tmp/source -d /tmp/out >/dev/null 2>&1; then
  echo 'Go migration binary unexpectedly accepted production backup command' >&2
  exit 1
fi

echo '==> GO08 Android ARM64 deterministic architecture gate'
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -o "$probe_root/bkx-android-arm64" ./cmd/bkx
meta="$(go version -m "$probe_root/bkx-android-arm64")"
printf '%s\n' "$meta" | grep -Eq '^[[:space:]]*build[[:space:]]+GOOS=android$'
printf '%s\n' "$meta" | grep -Eq '^[[:space:]]*build[[:space:]]+GOARCH=arm64$'
python3 - "$probe_root/bkx-android-arm64" <<'PYARCH'
from pathlib import Path
import sys
p = Path(sys.argv[1]).read_bytes()[:20]
if len(p) < 20 or p[:4] != b"\x7fELF":
    raise SystemExit("Android Go binary is not ELF")
byteorder = "little" if p[5] == 1 else "big" if p[5] == 2 else None
if byteorder is None:
    raise SystemExit("Android Go binary has invalid ELF byte order")
machine = int.from_bytes(p[18:20], byteorder)
if machine != 183:
    raise SystemExit(f"Android Go binary e_machine={machine}, expected 183 (AArch64)")
print("Android Go binary metadata/ELF architecture: android/arm64 AArch64")
PYARCH

echo 'BKX GO08 migration gates passed.'
