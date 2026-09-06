from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android" / "BKX.Android"


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_libsu_all_files_and_root_service_contract() -> None:
    catalog = text("android/BKX.Android/gradle/libs.versions.toml")
    manifest = text("android/BKX.Android/app/src/main/AndroidManifest.xml")
    root_service = text("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/root/BkxRootEngineService.kt")
    root_client = text("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/root/RootBkxServiceClient.kt")

    assert 'libsu = "6.0.0"' in catalog
    assert "android.permission.MANAGE_EXTERNAL_STORAGE" in manifest
    assert ".root.BkxRootEngineService" in manifest
    assert "RootService" in root_service
    assert "Os.getuid()" in root_service
    assert 'GoNativeGateway(baseContext, stateNamespace = ROOT_STATE_NAMESPACE)' in root_service
    assert 'runBlocking { goGateway.readinessJson() }' in root_service
    assert 'getModule("bkx.android_api")' not in root_service
    assert "Shell.getShell().isRoot" in root_client
    assert "RootService.bind" in root_client
    for forbidden in ("ProcessBuilder(", "su -c", "Runtime.getRuntime().exec", "com.chaquo.python"):
        assert forbidden not in root_service
        assert forbidden not in root_client


def test_termux_private_storage_is_a_first_class_root_location() -> None:
    storage = text("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/storage/StorageAccessModels.kt")
    bridge = text("internal/bridge/bridge.go")
    assert "/data/data/com.termux/files" in storage
    assert "/data/data/com.termux/files/home" in storage
    assert "/data/data/com.termux/files/home/storage" in storage
    assert "/data/data/com.termux/files/usr" in storage
    assert '"root.filesystem"' in bridge
    assert '"storage.termux.private"' in bridge
    assert '"filesystem.list"' in text("migration/contracts/v1/bridge.json")
    assert '"filesystem.stat"' in text("migration/contracts/v1/bridge.json")


def test_termux_is_storage_only_not_an_execution_dependency() -> None:
    manifest = text("android/BKX.Android/app/src/main/AndroidManifest.xml")
    graph = text("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt")
    assert "com.termux.permission.RUN_COMMAND" not in manifest
    assert "com.termux.RUN_COMMAND" not in manifest
    assert "RootAwareGateway" in graph
    assert "val local = GoNativeGateway(appContext)" in graph
    assert "ChaquopyPythonGateway" not in graph
    assert "TermuxBridgeAdapter(" not in graph


def test_root_service_aidl_is_narrow_json_api() -> None:
    aidl = text("android/BKX.Android/app/src/main/aidl/com/mikeyphw/bkx/android/root/IBkxRootEngineService.aidl")
    assert "readinessJson" in aidl
    assert "executeJson" in aidl
    assert "cancelJson" in aidl
    for forbidden in ("exec(", "shell", "commandPath", "arguments"):
        assert forbidden not in aidl


def test_root_go_runtime_is_app_owned_and_fail_closed() -> None:
    root_service = text(
        "android/BKX.Android/app/src/main/java/"
        "com/mikeyphw/bkx/android/root/BkxRootEngineService.kt"
    )
    gateway = text(
        "android/BKX.Android/app/src/main/java/"
        "com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt"
    )
    assert 'GoNativeGateway(baseContext, stateNamespace = ROOT_STATE_NAMESPACE)' in root_service
    assert 'check(goGateway.isAvailable())' in root_service
    assert 'applicationInfo.nativeLibraryDir' in gateway
    assert '"android-runtime"' in gateway and '"bridge-session"' in gateway
    assert 'const val ABI = "arm64-v8a"' in gateway
