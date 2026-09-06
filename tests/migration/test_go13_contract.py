import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def phase_at_least(minimum: int) -> bool:
    match = re.search(r'MigrationPhase\s*=\s*"GO(\d+)"', read("internal/version/version.go"))
    assert match is not None
    return int(match.group(1)) >= minimum


def test_go13_enables_go_by_default_but_keeps_controlled_local_fallback():
    phase = read("internal/version/version.go")
    graph = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt")
    if phase_at_least(14):
        assert 'val local = GoNativeGateway(appContext)' in graph
        assert 'NativePreferredGateway' not in graph and 'ChaquopyPythonGateway' not in graph
        assert not (ROOT / "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoRuntimeCutoverPolicy.kt").exists()
        assert not (ROOT / "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/NativePreferredGateway.kt").exists()
        return
    policy = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoRuntimeCutoverPolicy.kt")
    preferred = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/NativePreferredGateway.kt")
    assert "ENABLED_BY_DEFAULT: Boolean = true" in policy
    assert "REQUIRE_GO_ON_ARM64: Boolean = true" in policy
    assert 'TARGET_ABI: String = "arm64-v8a"' in policy
    assert "NativePreferredGateway" in graph and "ChaquopyPythonGateway" in graph
    assert "ProductionGoRuntimeUnavailableException" in preferred
    assert "if (goRequiredProvider()) throw ProductionGoRuntimeUnavailableException()" in preferred
    assert "goEnabledProvider = { GoRuntimeCutoverPolicy.ENABLED_BY_DEFAULT }" in graph
    assert "if (!goEnabledProvider()) return Route.Python" in preferred
    assert "if (nativeGateway.isAvailable()) return Route.Go" in preferred


def test_go13_root_route_runs_packaged_go_as_uid0_without_python_or_shell():
    root = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/root/BkxRootEngineService.kt")
    gateway = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt")
    assert 'GoNativeGateway(baseContext, stateNamespace = ROOT_STATE_NAMESPACE)' in root
    assert 'ROOT_STATE_NAMESPACE = "go-root"' in root
    assert "Os.getuid() == ROOT_UID" in root
    assert "check(goGateway.isAvailable())" in root
    assert "runBlocking { goGateway.readinessJson() }" in root
    assert 'getModule("bkx.android_api")' not in root
    assert "com.chaquo.python" not in root
    assert "ProcessBuilder(" not in root
    assert "applicationInfo.nativeLibraryDir" in gateway
    assert 'RUNTIME_LIBRARY = "libbkxgo.so"' in gateway


def test_go13_root_and_local_go_state_are_isolated_to_avoid_uid_ownership_conflicts():
    gateway = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt")
    root = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/root/BkxRootEngineService.kt")
    assert 'LOCAL_STATE_NAMESPACE = "go-local"' in gateway
    assert 'setOf(LOCAL_STATE_NAMESPACE, "go-root")' in gateway
    assert 'resolve("bkx/$stateNamespace")' in gateway
    assert 'stateNamespace = ROOT_STATE_NAMESPACE' in root


def test_go13_renames_root_router_to_transport_neutral_gateway():
    graph = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt")
    gateway = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/RootAwareGateway.kt")
    assert "RootAwareGateway" in graph
    assert "requestRoutes" in gateway and "requestIdFrom" in gateway
    assert not (ROOT / "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/RootAwarePythonGateway.kt").exists()


def test_go13_device_tests_prove_local_and_privileged_go_runtime_identity():
    local = read("android/BKX.Android/app/src/androidTest/java/com/mikeyphw/bkx/android/embedded/GoProductionCutoverInstrumentationTest.kt")
    qualification = read("android/BKX.Android/app/src/androidTest/java/com/mikeyphw/bkx/android/release/ReleaseQualificationInstrumentationTest.kt")
    script = read("android/BKX.Android/scripts/qualify-device.sh")
    assert "go-native-android" in local
    assert 'assertEquals("go-native-android"' in local
    assert "Privileged production route must be Go-native" in qualification
    assert "Local production route must be Go-native" in qualification
    assert "bkxDeviceQualification true" in script


def test_go13_android_preflight_and_release_seal_require_cutover_gate():
    verification = read("build-logic/android-verification.gradle.kts")
    devtool = read(".devtool.toml")
    release = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/release/BkxAndroidReleaseContract.kt")
    assert 'verifyBkxGoProductionCutoverContract' in verification
    assert 'dependsOn("buildBkxGoAndroidRuntime")' in verification
    assert ':app:verifyBkxGoProductionCutoverContract' in devtool
    assert ':app:verifyBkxGoProductionCutoverContract' in release


def test_go13_validator_chains_go12_and_requires_root_runtime_capabilities():
    validator = read("scripts/validate-go13.sh")
    verifier = read("tools/go_migration/verify_go13_root_runtime.py")
    assert "test_go12_contract.py" in validator
    assert "verify_go12_android_runtime.py" in validator
    assert "BKX_GO13_FULL_REGRESSION" in validator
    assert "test_go13_contract.py" in validator
    assert "verify_go13_root_runtime.py" in validator
    assert '"root.filesystem"' in verifier
    assert '"storage.termux.private"' in verifier


def test_go13_python_fallback_is_lazy_after_production_cutover():
    phase = read("internal/version/version.go")
    app = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/BkxAndroidApplication.kt")
    assert "Python.start(" not in app
    if phase_at_least(14):
        assert not (ROOT / "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/ChaquopyPythonGateway.kt").exists()
        return
    python = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/ChaquopyPythonGateway.kt")
    assert "Python.start(AndroidPlatform(appContext))" in python


def test_go13_native_selector_unit_contract_covers_failclosed_and_rollback_lanes():
    phase = read("internal/version/version.go")
    path = ROOT / "android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/embedded/NativePreferredGatewayTest.kt"
    if phase_at_least(14):
        assert not path.exists()
        return
    test = path.read_text(encoding="utf-8")
    assert "arm64CutoverFailsClosedWhenPackagedGoRuntimeIsMissing" in test
    assert "nonArm64RollbackLaneMayUsePythonWhenGoIsUnavailable" in test
    assert "explicitRollbackSealDisablesGo" in test


def test_go13_android_gradle_uses_agp9_generated_source_api_and_ndk29_floor():
    gradle = read("android/BKX.Android/app/build.gradle.kts")
    assert 'ndkVersion = "29.0.14206865"' in gradle
    assert 'val bkxNdkVersion = "29.0.14206865"' in gradle
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
