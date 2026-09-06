#!/usr/bin/env bash
set -euo pipefail
repo="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$repo"
export PYTHONPATH="$repo/src${PYTHONPATH:+:$PYTHONPATH}"
export TZ=UTC

if [[ "${BKX_GO13_FULL_REGRESSION:-0}" == "1" ]]; then
  echo '==> GO00-GO12 complete regression chain'
  bash ./scripts/validate-go12.sh
else
  echo '==> GO12 + GO13 migration/source contracts'
  python3 -m pytest -q \
    tests/migration/test_go12_contract.py \
    tests/migration/test_go13_contract.py \
    tests/test_android_root_storage_contract.py \
    tests/test_android_final_release_seal.py
fi

echo '==> GO13 complete Go regression + vet'
go test ./...
go vet ./...

probe_root="${TMPDIR:-/tmp}/bkx-go13-$$"
mkdir -p "$probe_root"
trap 'rm -rf "$probe_root"' EXIT HUP INT TERM

echo '==> GO11 protocol-v1 bridge parity regression'
go build -o "$probe_root/bkx" ./cmd/bkx
python3 tools/go_migration/verify_go11_bridge_parity.py --go-binary "$probe_root/bkx"

echo '==> GO12 concurrent Android runtime regression'
python3 tools/go_migration/verify_go12_android_runtime.py --go-binary "$probe_root/bkx"

echo '==> GO13 runtime capability proof'
python3 tools/go_migration/verify_go13_root_runtime.py --go-binary "$probe_root/bkx"

echo '==> GO13 Android ARM64 packaged runtime gate'
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
if len(p) < 20 or p[:4] != b"\x7fELF": raise SystemExit("GO13 Android runtime is not ELF")
byteorder = "little" if p[5] == 1 else "big" if p[5] == 2 else None
if byteorder is None: raise SystemExit("GO13 Android runtime has invalid ELF byte order")
machine = int.from_bytes(p[18:20], byteorder)
if machine != 183: raise SystemExit(f"GO13 Android runtime e_machine={machine}, expected 183")
print("GO13 packaged runtime metadata/ELF architecture: android/arm64 AArch64")
PYARCH

echo '==> GO13 AGP9 generated-JNI and NDK r29+ toolchain seal'
python3 - <<'PYTOOLCHAIN'
from pathlib import Path
gradle = Path("android/BKX.Android/app/build.gradle.kts").read_text()
assert 'ndkVersion = "29.0.14206865"' in gradle
assert 'check(bkxNdkMajor >= 29)' in gradle
assert 'abstract class BuildBkxGoAndroidRuntimeTask' in gradle
assert '@get:OutputDirectory' in gradle
assert 'abstract val outputDirectory: DirectoryProperty' in gradle
assert 'tasks.register<BuildBkxGoAndroidRuntimeTask>("buildBkxGoAndroidRuntime")' in gradle
assert 'variant.sources.jniLibs?.addGeneratedSourceDirectory(' in gradle
assert 'BuildBkxGoAndroidRuntimeTask::outputDirectory' in gradle
assert 'jniLibs.directories.add(' not in gradle
assert 'jniLibs.srcDir(' not in gradle
assert 'tasks.registering(Exec::class)' not in gradle
print("GO13 Android Gradle wiring is AGP9-safe; NDK pin is 29.0.14206865 (r29+ policy)")
PYTOOLCHAIN

echo '==> GO13 production Go cutover static seal'
python3 - <<'PYCUTOVER'
from pathlib import Path
policy = Path("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoRuntimeCutoverPolicy.kt").read_text()
selector = Path("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/NativePreferredGateway.kt").read_text()
root = Path("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/root/BkxRootEngineService.kt").read_text()
graph = Path("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt").read_text()
app = Path("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/BkxAndroidApplication.kt").read_text()
assert "ENABLED_BY_DEFAULT: Boolean = true" in policy
assert "REQUIRE_GO_ON_ARM64: Boolean = true" in policy
assert "ProductionGoRuntimeUnavailableException" in selector
assert "if (goRequiredProvider()) throw ProductionGoRuntimeUnavailableException()" in selector
assert "GoNativeGateway(baseContext, stateNamespace = ROOT_STATE_NAMESPACE)" in root
assert 'getModule("bkx.android_api")' not in root and "com.chaquo.python" not in root
assert "RootAwareGateway" in graph and "NativePreferredGateway" in graph
assert "Python.start(" not in app
print("GO13 production local/root routing is fail-closed Go-native on ARM64")
PYCUTOVER

echo '==> GO13 human product command boundary remains fail-closed'
if "$probe_root/bkx" backup plan >/dev/null 2>&1; then
  echo 'Go runtime unexpectedly exposed human production backup command' >&2
  exit 1
fi

echo 'BKX GO13 Android/device cutover source gates passed.'
