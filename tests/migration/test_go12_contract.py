from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def test_go12_packages_generated_arm64_runtime_without_committed_binary():
    build = read("android/BKX.Android/app/build.gradle.kts")
    script = read("scripts/build-go-android-runtime.sh")
    assert 'libbkxgo.so' in build and 'libbkxgo.so' in script
    assert 'GOOS=android' in script and 'GOARCH=arm64' in script and 'CGO_ENABLED=0' in script
    assert '!= 183' in script and 'AArch64' in script
    assert 'useLegacyPackaging = true' in build
    assert 'buildBkxGoAndroidRuntime' in build and 'dependsOn(buildBkxGoAndroidRuntime)' in build
    assert not (ROOT / "android/BKX.Android/app/src/main/jniLibs/arm64-v8a/libbkxgo.so").exists()


def test_go12_android_runtime_command_is_internal_and_concurrent():
    main = read("cmd/bkx/main.go")
    bridge = read("internal/bridge/bridge.go")
    assert 'args[0] == "android-runtime"' in main
    assert 'case "bridge-session":' in main
    assert 'go func(command concurrentBridgeCommand)' in main
    assert '"go-native-android"' in bridge
    assert 'func NewAndroid(' in bridge
    assert 'Go migration scaffold is not the production backend yet' in main


def test_go12_kotlin_gateway_launches_only_packaged_runtime_without_shell():
    gateway = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt")
    wire = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeSessionWire.kt")
    assert 'applicationInfo.nativeLibraryDir' in gateway
    assert 'RUNTIME_LIBRARY = "libbkxgo.so"' in gateway
    assert 'ProcessBuilder(' in gateway
    assert '"android-runtime"' in gateway and '"bridge-session"' in gateway
    assert 'ConcurrentHashMap' in gateway and 'CompletableDeferred' in gateway
    assert 'entrypoint in ENTRYPOINTS' in wire
    for forbidden in ('/system/bin/sh', 'sh -c', 'bash -c', 'su -c', 'Runtime.getRuntime().exec'):
        assert forbidden not in gateway


def test_go12_cutover_policy_stays_python_by_default_with_go_device_probe():
    policy = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoRuntimeCutoverPolicy.kt")
    graph = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt")
    preferred = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/NativePreferredGateway.kt")
    root = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/root/BkxRootEngineService.kt")
    instrumentation = read("android/BKX.Android/app/src/androidTest/java/com/mikeyphw/bkx/android/embedded/GoNativeRuntimeInstrumentationTest.kt")
    assert 'ENABLED_BY_DEFAULT: Boolean = false' in policy
    assert 'NativePreferredGateway' in graph and 'ChaquopyPythonGateway' in graph
    assert 'goEnabledProvider = { GoRuntimeCutoverPolicy.ENABLED_BY_DEFAULT }' in graph
    assert 'goEnabledProvider() && nativeGateway.isAvailable()' in preferred
    assert 'getModule("bkx.android_api")' in root  # privileged fallback remains Python until later phase
    assert 'GoNativeGateway(context)' in instrumentation
    assert 'go-native-android' in instrumentation


def test_go12_backend_accepts_both_runtime_identities_at_same_protocol():
    backend = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/EmbeddedBkxBackend.kt")
    tests = read("android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/embedded/EmbeddedBkxBackendTest.kt")
    assert 'setOf("embedded-cpython", "go-native-android")' in backend
    assert 'readinessAcceptsGoNativeAndroidRuntime' in tests
    assert 'BridgeJson.SUPPORTED_PROTOCOL_VERSION' in backend


def test_go12_release_hygiene_scopes_processbuilder_to_native_gateway():
    verification = read("build-logic/android-verification.gradle.kts")
    release_test = read("tests/test_android_final_release_seal.py")
    architecture_test = read("android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/contracts/ArchitectureContractTest.kt")
    assert 'file.name == "GoNativeGateway.kt"' in verification
    assert 'applicationInfo.nativeLibraryDir' in verification
    assert 'goRuntimeProcessBuilderIsNarrowAndAppOwned' in architecture_test
    assert 'go_gateway = read(' in release_test


def test_go12_validator_chains_go11_and_exercises_concurrent_android_session():
    validator = read("scripts/validate-go12.sh")
    verifier = read("tools/go_migration/verify_go12_android_runtime.py")
    assert 'validate-go11.sh' in validator
    assert 'verify_go12_android_runtime.py' in validator
    assert 'build-go-android-runtime.sh' in validator
    assert 'android-runtime' in verifier and 'bridge-session' in verifier
    assert 'progress-transfer' in verifier and 'cancel-transfer' in verifier
