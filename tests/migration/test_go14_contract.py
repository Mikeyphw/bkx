import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "android" / "BKX.Android"


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def phase_at_least(minimum: int) -> bool:
    match = re.search(r'MigrationPhase\s*=\s*"GO(\d+)"', read("internal/version/version.go"))
    assert match is not None
    return int(match.group(1)) >= minimum


def test_go14_phase_identity_and_ci_gate():
    assert phase_at_least(14)
    ci = read(".github/workflows/ci.yml")
    assert "GO00-GO15 public CLI cutover gate" in ci
    assert "./scripts/validate-go15.sh" in ci


def test_go14_android_removes_chaquopy_plugin_and_build_python_lane():
    catalog = read("android/BKX.Android/gradle/libs.versions.toml")
    root_build = read("android/BKX.Android/build.gradle.kts")
    app = read("android/BKX.Android/app/build.gradle.kts")
    assert "chaquopy" not in catalog.lower()
    assert "libs.plugins.chaquopy" not in root_build
    assert "libs.plugins.chaquopy" not in app
    for token in ("chaquopy {", "buildPython(", "bkxEmbeddedPythonVersion", "bkxBuildPythonCommand", "bkx.buildPython"):
        assert token not in app


def test_go14_android_is_explicitly_arm64_only_with_ndk29_floor():
    app = read("android/BKX.Android/app/build.gradle.kts")
    script = read("scripts/build-go-android-runtime.sh")
    assert 'abiFilters += listOf("arm64-v8a")' in app
    assert "x86_64" not in app
    assert 'ndkVersion = "29.0.14206865"' in app
    assert 'val bkxNdkVersion = "29.0.14206865"' in app
    assert 'check(bkxNdkMajor >= 29)' in app
    assert "CGO_ENABLED=0 GOOS=android GOARCH=arm64" in script
    assert "GOARCH=amd64" not in script
    assert "expected AArch64 (183)" in script


def test_go14_production_graph_has_no_python_or_fallback_selector():
    graph = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt")
    backend = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/EmbeddedBkxBackend.kt")
    gateway = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt")
    assert "val local = GoNativeGateway(appContext)" in graph
    assert "RootAwareGateway" in graph
    assert "ChaquopyPythonGateway" not in graph
    assert "NativePreferredGateway" not in graph
    assert 'setOf("go-native-android")' in backend
    assert "embedded-cpython" not in backend
    assert 'const val ABI = "arm64-v8a"' in gateway


def test_go14_retired_android_python_sources_are_deleted():
    base = ANDROID / "app/src/main/java/com/mikeyphw/bkx/android/embedded"
    for name in (
        "ChaquopyPythonGateway.kt",
        "EmbeddedPythonGateway.kt",
        "NativePreferredGateway.kt",
        "GoRuntimeCutoverPolicy.kt",
    ):
        assert not (base / name).exists(), name
    assert not (ANDROID / "app/src/test/java/com/mikeyphw/bkx/android/embedded/NativePreferredGatewayTest.kt").exists()


def test_go14_root_route_is_same_packaged_go_runtime():
    root = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/root/BkxRootEngineService.kt")
    gateway = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt")
    assert 'GoNativeGateway(baseContext, stateNamespace = ROOT_STATE_NAMESPACE)' in root
    assert 'check(goGateway.isAvailable())' in root
    assert "Os.getuid() == ROOT_UID" in root
    assert "com.chaquo.python" not in root
    assert 'getModule("bkx.android_api")' not in root
    assert 'applicationInfo.nativeLibraryDir' in gateway
    assert 'RUNTIME_LIBRARY = "libbkxgo.so"' in gateway


def test_go14_gradle_generated_jni_wiring_and_release_gate_remain_sealed():
    app = read("android/BKX.Android/app/build.gradle.kts")
    verifier = read("build-logic/android-verification.gradle.kts")
    devtool = read(".devtool.toml")
    release = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/release/BkxAndroidReleaseContract.kt")
    assert 'variant.sources.jniLibs?.addGeneratedSourceDirectory(' in app
    assert 'BuildBkxGoAndroidRuntimeTask::outputDirectory' in app
    assert 'jniLibs.directories.add(' not in app
    assert 'jniLibs.srcDir(' not in app
    assert 'tasks.register("verifyBkxGoOnlyAndroidRuntimeContract")' in verifier
    assert ':app:verifyBkxGoOnlyAndroidRuntimeContract' in devtool
    assert ':app:verifyBkxGoOnlyAndroidRuntimeContract' in release


def test_go14_human_go_cli_remains_separate_from_android_runtime():
    main = read("cmd/bkx/main.go")
    assert 'args[0] == "android-runtime"' in main
    assert 'Go migration scaffold is not the production backend yet' in main
