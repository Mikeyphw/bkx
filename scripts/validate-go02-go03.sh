#!/usr/bin/env bash
set -euo pipefail

repo="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$repo"

export PYTHONPATH="$repo/src${PYTHONPATH:+:$PYTHONPATH}"

echo '==> Frozen contract fixtures'
if python3 -c 'import typer' >/dev/null 2>&1; then
  python3 tools/go_migration/export_contracts.py --check
else
  echo 'GO00 live Python exporter dependency unavailable; committed contract manifest will be verified by Go.'
fi

echo '==> Go unit tests'
go test ./...

echo '==> Go vet'
go vet ./...

probe_root="${TMPDIR:-/tmp}/bkx-go02-go03-$$"
mkdir -p "$probe_root"
trap 'rm -rf "$probe_root"' EXIT HUP INT TERM

echo '==> Linux migration binary'
go build -o "$probe_root/bkx" ./cmd/bkx
"$probe_root/bkx" contract check migration/contracts/v1

echo '==> Python <-> Go differential parity'
python3 tools/go_migration/verify_go02_go03_parity.py --go-binary "$probe_root/bkx"

echo '==> Android ARM64 CGO-free compile gate'
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

echo 'BKX GO02-GO03 migration gates passed.'
