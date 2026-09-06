#!/usr/bin/env bash
set -euo pipefail
repo="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$repo"
export PYTHONPATH="$repo/src${PYTHONPATH:+:$PYTHONPATH}"
export TZ=UTC

echo '==> GO00-GO11 regression gates'
bash ./scripts/validate-go11.sh

echo '==> GO12 migration/source contracts'
python3 -m pytest -q tests/migration/test_go12_contract.py \
  tests/test_android_embedded_engine_contract.py \
  tests/test_android_root_storage_contract.py \
  tests/test_android_final_release_seal.py

echo '==> GO12 focused Go packages'
go test ./internal/bridge ./internal/services ./internal/archiveio ./internal/backupops ./internal/transferops ./internal/runstore ./internal/fsutil ./cmd/bkx ./internal/version

echo '==> GO12 Go vet'
go vet ./...

probe_root="${TMPDIR:-/tmp}/bkx-go12-$$"
mkdir -p "$probe_root"
trap 'rm -rf "$probe_root"' EXIT HUP INT TERM

echo '==> GO12 Linux concurrent Android runtime session'
go build -o "$probe_root/bkx" ./cmd/bkx
python3 tools/go_migration/verify_go12_android_runtime.py --go-binary "$probe_root/bkx"

echo '==> GO12 generated Android ARM64 runtime packaging input'
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
if len(p) < 20 or p[:4] != b"\x7fELF": raise SystemExit("GO12 Android runtime is not ELF")
byteorder = "little" if p[5] == 1 else "big" if p[5] == 2 else None
if byteorder is None: raise SystemExit("GO12 Android runtime has invalid ELF byte order")
machine = int.from_bytes(p[18:20], byteorder)
if machine != 183: raise SystemExit(f"GO12 Android runtime e_machine={machine}, expected 183")
print("GO12 packaged runtime metadata/ELF architecture: android/arm64 AArch64")
PYARCH

echo '==> GO12 production cutover remains sealed off by default'
python3 - <<'PYCUTOVER'
from pathlib import Path
graph = Path("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt").read_text()
root = Path("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/root/BkxRootEngineService.kt").read_text()
phase = Path("internal/version/version.go").read_text()
policy_path = Path("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoRuntimeCutoverPolicy.kt")
if 'MigrationPhase = "GO14"' in phase:
    if "val local = GoNativeGateway(appContext)" not in graph or "ChaquopyPythonGateway" in graph or "NativePreferredGateway" in graph:
        raise SystemExit("GO14 Android graph is not Go-only")
    if policy_path.exists():
        raise SystemExit("GO14 retained the obsolete Go cutover policy")
else:
    policy = policy_path.read_text()
    if "NativePreferredGateway" not in graph or "ChaquopyPythonGateway" not in graph:
        raise SystemExit("GO12 Android graph is missing Go integration or Python fallback")
    if 'MigrationPhase = "GO12"' in phase:
        if "ENABLED_BY_DEFAULT: Boolean = false" not in policy:
            raise SystemExit("GO12 production Go cutover is not sealed off")
        if 'getModule("bkx.android_api")' not in root:
            raise SystemExit("GO12 privileged fallback changed before root Go validation")
print("GO12 cutover seam and packaged runtime remain valid")
PYCUTOVER

echo '==> GO12 product command boundary remains fail-closed'
if "$probe_root/bkx" bridge execute '{}' - >/dev/null 2>&1; then
  echo 'Go runtime unexpectedly exposed product bridge command' >&2
  exit 1
fi
if "$probe_root/bkx" backup plan >/dev/null 2>&1; then
  echo 'Go runtime unexpectedly exposed production backup command' >&2
  exit 1
fi

# A real Android/Kotlin compile is intentionally owned by the bkx_android target,
# whose Devtool lane supplies SDK/AAPT2/Gradle environment. GO12 nevertheless ships
# JVM unit + ARM64 instrumentation sources which that target will execute unchanged.
echo 'BKX GO12 Android runtime integration gates passed.'
