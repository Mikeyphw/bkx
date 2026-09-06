#!/usr/bin/env bash
set -euo pipefail
repo="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$repo"
export PYTHONPATH="$repo/src${PYTHONPATH:+:$PYTHONPATH}"
export TZ=UTC

echo '==> GO00-GO10 regression gates'
bash ./scripts/validate-go10.sh

echo '==> GO11 migration source contracts'
python3 -m pytest -q tests/migration/test_go11_contract.py

echo '==> GO11 focused Go packages'
go test ./internal/bridge ./internal/services ./internal/archiveio ./internal/backupops ./internal/transferops ./internal/runstore ./internal/fsutil ./cmd/bkx ./internal/version

echo '==> GO11 Go vet'
go vet ./...

probe_root="${TMPDIR:-/tmp}/bkx-go11-$$"
mkdir -p "$probe_root"
trap 'rm -rf "$probe_root"' EXIT HUP INT TERM

echo '==> GO11 Linux bridge parity binary'
go build -o "$probe_root/bkx" ./cmd/bkx
"$probe_root/bkx" contract check migration/contracts/v1

echo '==> GO11 Python <-> Go protocol-v1 bridge parity'
python3 tools/go_migration/verify_go11_bridge_parity.py --go-binary "$probe_root/bkx"

echo '==> GO11 frozen bridge registry counts'
python3 - "$probe_root/bkx" <<'PYBRIDGE'
import json
from pathlib import Path
frozen = json.loads(Path("migration/contracts/v1/bridge.json").read_text())
if frozen["protocolVersion"] != 1:
    raise SystemExit("bridge protocol drift")
if len(frozen["advertisedOperations"]) != 15 or len(frozen["handlers"]) != 16:
    raise SystemExit("bridge registry count drift")
if "run.cancel" in frozen["advertisedOperations"] or "run.cancel" not in frozen["handlers"]:
    raise SystemExit("run.cancel advertised/handler boundary drift")
print("GO11 frozen bridge registry: 15 advertised operations, 16 handlers")
PYBRIDGE

echo '==> GO11 product/Android cutover boundary stays fail-closed'
if "$probe_root/bkx" bridge execute '{}' - >/dev/null 2>&1; then
  echo 'Go migration binary unexpectedly accepted product bridge command' >&2
  exit 1
fi
if "$probe_root/bkx" backup plan >/dev/null 2>&1; then
  echo 'Go migration binary unexpectedly accepted production backup command' >&2
  exit 1
fi
python3 - <<'PYANDROID'
from pathlib import Path
graph = Path("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt").read_text()
policy = Path("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoRuntimeCutoverPolicy.kt")
if "EmbeddedBkxBackend" not in graph or "ChaquopyPythonGateway" not in graph:
    raise SystemExit("Android production backend no longer retains embedded Python")
phase = Path("internal/version/version.go").read_text()
if 'MigrationPhase = \"GO11\"' in phase or 'MigrationPhase = \"GO12\"' in phase:
    if policy.exists() and "ENABLED_BY_DEFAULT: Boolean = false" not in policy.read_text():
        raise SystemExit("Android Go cutover was enabled before the GO13 device gate")
print("GO11 production compatibility boundary remains explicit")
PYANDROID

echo '==> GO11 Android ARM64 deterministic architecture gate'
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

echo 'BKX GO11 migration gates passed.'
