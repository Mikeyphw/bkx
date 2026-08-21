from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android" / "BKX.Android"


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_aov17_libsu_all_files_and_root_service_contract() -> None:
    catalog = text("android/BKX.Android/gradle/libs.versions.toml")
    manifest = text("android/BKX.Android/app/src/main/AndroidManifest.xml")
    root_service = text("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/root/BkxRootEngineService.kt")
    root_client = text("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/root/RootBkxServiceClient.kt")

    assert 'libsu = "6.0.0"' in catalog
    assert "android.permission.MANAGE_EXTERNAL_STORAGE" in manifest
    assert ".root.BkxRootEngineService" in manifest
    assert "RootService" in root_service
    assert "Os.getuid()" in root_service
    assert 'getModule("bkx.android_api")' in root_service
    assert "Shell.getShell().isRoot" in root_client
    assert "RootService.bind" in root_client
    for forbidden in ("ProcessBuilder(", "su -c", "Runtime.getRuntime().exec"):
        assert forbidden not in root_service
        assert forbidden not in root_client


def test_aov17_termux_private_storage_is_a_first_class_root_location() -> None:
    storage = text("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/storage/StorageAccessModels.kt")
    engine = text("src/bkx/android_api/engine.py")
    assert "/data/data/com.termux/files" in storage
    assert "/data/data/com.termux/files/home" in storage
    assert "/data/data/com.termux/files/home/storage" in storage
    assert "/data/data/com.termux/files/usr" in storage
    assert '"root.filesystem"' in engine
    assert '"storage.termux.private"' in engine
    assert '"filesystem.list"' in engine
    assert '"filesystem.stat"' in engine


def test_aov20_termux_is_storage_only_not_an_execution_dependency() -> None:
    manifest = text("android/BKX.Android/app/src/main/AndroidManifest.xml")
    graph = text("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt")
    assert "com.termux.permission.RUN_COMMAND" not in manifest
    assert "com.termux.RUN_COMMAND" not in manifest
    assert "RootAwarePythonGateway" in graph
    assert "TermuxBridgeAdapter(" not in graph


def test_aov17_root_service_aidl_is_narrow_json_api() -> None:
    aidl = text("android/BKX.Android/app/src/main/aidl/com/mikeyphw/bkx/android/root/IBkxRootEngineService.aidl")
    assert "readinessJson" in aidl
    assert "executeJson" in aidl
    assert "cancelJson" in aidl
    for forbidden in ("exec(", "shell", "commandPath", "arguments"):
        assert forbidden not in aidl
