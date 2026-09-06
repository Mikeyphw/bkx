#!/usr/bin/env bash
set -euo pipefail
repo="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$repo"
export PYTHONPATH="$repo/src${PYTHONPATH:+:$PYTHONPATH}"
export TZ=UTC

probe_root="${TMPDIR:-/tmp}/bkx-go15-$$"
mkdir -p "$probe_root"
trap 'rm -rf "$probe_root"' EXIT HUP INT TERM

echo '==> GO15 migration/source contracts (flattened; no recursive Android rebuild chain)'
python3 -m pytest -q tests/migration \
  tests/test_android_embedded_engine_contract.py \
  tests/test_android_root_storage_contract.py \
  tests/test_android_final_release_seal.py

echo '==> GO15 complete Go regression + vet'
go test ./...
go vet ./...

echo '==> GO15 host public CLI build + frozen contract check'
go build -trimpath -o "$probe_root/bkx" ./cmd/bkx
"$probe_root/bkx" contract check migration/contracts/v1

echo '==> GO02-GO13 differential regression on one host binary'
for verifier in \
  verify_go02_go03_parity.py \
  verify_go04_parity.py \
  verify_go05_parity.py \
  verify_go06_parity.py \
  verify_go07_parity.py \
  verify_go08_parity.py \
  verify_go09_parity.py \
  verify_go10_service_parity.py \
  verify_go11_bridge_parity.py \
  verify_go12_android_runtime.py \
  verify_go13_root_runtime.py
do
  python3 "tools/go_migration/$verifier" --go-binary "$probe_root/bkx"
done

echo '==> GO15 public Python-reference <-> Go CLI parity'
python3 tools/go_migration/verify_go15_cli_parity.py --go-binary "$probe_root/bkx"

echo '==> GO15 public ownership / hidden probe boundary'
"$probe_root/bkx" backup >/dev/null
"$probe_root/bkx" archive >/dev/null
"$probe_root/bkx" restore >/dev/null
"$probe_root/bkx" transfer >/dev/null
"$probe_root/bkx" tools >/dev/null
"$probe_root/bkx" run >/dev/null
"$probe_root/bkx" ui >/dev/null
if "$probe_root/bkx" service >/dev/null 2>&1; then
  echo 'internal GO10 service probe leaked into public CLI' >&2
  exit 1
fi
if "$probe_root/bkx" bridge >/dev/null 2>&1; then
  echo 'internal GO11 bridge probe leaked into public CLI' >&2
  exit 1
fi

if [ "${BKX_GO15_SKIP_ANDROID_RUNTIME:-0}" != 1 ]; then
  echo '==> GO15 Android arm64/NDK29 regression (single native build)'
  bash ./scripts/build-go-android-runtime.sh "$probe_root/jniLibs" >/dev/null
  runtime="$probe_root/jniLibs/arm64-v8a/libbkxgo.so"
  test -x "$runtime"
  meta="$(go version -m "$runtime")"
  printf '%s\n' "$meta" | grep -Eq '^[[:space:]]*build[[:space:]]+GOOS=android$'
  printf '%s\n' "$meta" | grep -Eq '^[[:space:]]*build[[:space:]]+GOARCH=arm64$'
  python3 - "$runtime" <<'PYARCH'
from pathlib import Path
import sys
p = Path(sys.argv[1]).read_bytes()[:20]
if len(p) < 20 or p[:4] != b"\x7fELF": raise SystemExit("GO15 Android runtime is not ELF")
bo = "little" if p[5] == 1 else "big" if p[5] == 2 else None
if bo is None: raise SystemExit("GO15 Android runtime has invalid ELF byte order")
machine = int.from_bytes(p[18:20], bo)
if machine != 183: raise SystemExit(f"GO15 Android runtime e_machine={machine}, expected 183")
print("GO15 packaged runtime: android/arm64 AArch64")
PYARCH
  python3 - <<'PYPOLICY'
from pathlib import Path
app = Path("android/BKX.Android/app/build.gradle.kts").read_text()
assert 'abiFilters += listOf("arm64-v8a")' in app
assert 'ndkVersion = "29.0.14206865"' in app
assert 'check(bkxNdkMajor >= 29)' in app
assert '"ChromeOsAbiSupport"' in app
assert "x86_64" not in app
assert "chaquopy" not in app.lower()
print("GO15 Android ABI policy: arm64-v8a only; ChromeOS alternate-ABI lint explicitly inapplicable; NDK r29+ retained")
PYPOLICY
fi

echo 'BKX GO15 public Go CLI cutover gates passed.'
