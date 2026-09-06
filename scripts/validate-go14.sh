#!/usr/bin/env bash
set -euo pipefail
repo="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$repo"
export PYTHONPATH="$repo/src${PYTHONPATH:+:$PYTHONPATH}"
export TZ=UTC

echo '==> GO00-GO13 regression gates'
bash ./scripts/validate-go13.sh

echo '==> GO14 Android Go-only source contracts'
python3 -m pytest -q \
  tests/migration/test_go14_contract.py \
  tests/test_android_embedded_engine_contract.py \
  tests/test_android_root_storage_contract.py \
  tests/test_android_final_release_seal.py

echo '==> GO14 complete Go regression + vet'
go test ./...
go vet ./...

probe_root="${TMPDIR:-/tmp}/bkx-go14-$$"
mkdir -p "$probe_root"
trap 'rm -rf "$probe_root"' EXIT HUP INT TERM

echo '==> GO14 packaged Android ARM64 runtime'
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
if len(p) < 20 or p[:4] != b"\x7fELF": raise SystemExit("GO14 Android runtime is not ELF")
bo = "little" if p[5] == 1 else "big" if p[5] == 2 else None
if bo is None: raise SystemExit("GO14 Android runtime has invalid ELF byte order")
machine = int.from_bytes(p[18:20], bo)
if machine != 183: raise SystemExit(f"GO14 Android runtime e_machine={machine}, expected 183")
print("GO14 packaged runtime metadata/ELF architecture: android/arm64 AArch64")
PYARCH

echo '==> GO14 Android Python/Chaquopy retirement seal'
python3 - <<'PYGOONLY'
from pathlib import Path
catalog = Path("android/BKX.Android/gradle/libs.versions.toml").read_text()
root_build = Path("android/BKX.Android/build.gradle.kts").read_text()
app = Path("android/BKX.Android/app/build.gradle.kts").read_text()
graph = Path("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt").read_text()
backend = Path("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/EmbeddedBkxBackend.kt").read_text()
gateway = Path("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt").read_text()
retired = [
    Path("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/ChaquopyPythonGateway.kt"),
    Path("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/EmbeddedPythonGateway.kt"),
    Path("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/NativePreferredGateway.kt"),
    Path("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoRuntimeCutoverPolicy.kt"),
]
assert "chaquopy" not in catalog.lower()
assert "libs.plugins.chaquopy" not in root_build and "libs.plugins.chaquopy" not in app
for token in ("chaquopy {", "buildPython(", "bkxEmbeddedPythonVersion", "bkxBuildPythonCommand"):
    assert token not in app
assert 'abiFilters += listOf("arm64-v8a")' in app and "x86_64" not in app
assert 'ndkVersion = "29.0.14206865"' in app and 'check(bkxNdkMajor >= 29)' in app
assert "val local = GoNativeGateway(appContext)" in graph
assert "ChaquopyPythonGateway" not in graph and "NativePreferredGateway" not in graph
assert 'setOf("go-native-android")' in backend and "embedded-cpython" not in backend
assert 'const val ABI = "arm64-v8a"' in gateway
assert not any(path.exists() for path in retired)
print("GO14 Android runtime is arm64-v8a Go-only; Chaquopy/Python fallback removed; NDK r29+ retained")
PYGOONLY

echo '==> GO14 human product command boundary remains fail-closed'
go build -o "$probe_root/bkx" ./cmd/bkx
if "$probe_root/bkx" backup plan >/dev/null 2>&1; then
  echo 'Go runtime unexpectedly exposed human production backup command' >&2
  exit 1
fi

echo 'BKX GO14 Android Python-retirement gates passed.'
