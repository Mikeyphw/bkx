from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android" / "BKX.Android"


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_go_only_lane_is_pinned_and_sdk36_ndk29_are_retained() -> None:
    catalog = text("android/BKX.Android/gradle/libs.versions.toml")
    root_build = text("android/BKX.Android/build.gradle.kts")
    app = text("android/BKX.Android/app/build.gradle.kts")
    script = text("scripts/build-go-android-runtime.sh")
    assert 'agp = "9.2.1"' in catalog
    assert "chaquopy" not in catalog.lower()
    assert "libs.plugins.chaquopy" not in root_build
    assert "libs.plugins.chaquopy" not in app
    assert "chaquopy {" not in app
    assert "buildPython(" not in app
    assert 'minSdk = 24' in app
    assert 'compileSdk = bkxValidationSdk' in app
    assert 'val bkxValidationSdk = 36' in app
    assert 'ndkVersion = "29.0.14206865"' in app
    assert 'check(bkxNdkMajor >= 29)' in app
    assert 'abiFilters += listOf("arm64-v8a")' in app
    assert 'x86_64' not in app
    assert 'GOARCH=arm64' in script and 'e_machine={machine}, expected AArch64 (183)' in script
    assert 'GOARCH=amd64' not in script


def test_android_api_reference_is_core_only_and_never_cli_shell_dispatch() -> None:
    # The Python implementation remains a parity/reference and CLI implementation,
    # but GO14 no longer packages it inside Android.
    engine = text("src/bkx/android_api/engine.py")
    assert "from bkx.core" in engine
    for forbidden in ("bkx.__main__", "subprocess", "argparse", "textual", "typer"):
        assert forbidden not in engine


def test_production_graph_is_direct_go_only() -> None:
    graph = text("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt")
    backend = text("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/EmbeddedBkxBackend.kt")
    gateway = text("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt")
    assert "EmbeddedBkxBackend" in graph
    assert "val local = GoNativeGateway(appContext)" in graph
    assert "ChaquopyPythonGateway" not in graph
    assert "NativePreferredGateway" not in graph
    assert 'setOf("go-native-android")' in backend
    assert "embedded-cpython" not in backend
    assert 'const val ABI = "arm64-v8a"' in gateway
    assert "TermuxBridgeAdapter(" not in graph


def test_android_python_fallback_sources_are_removed() -> None:
    retired = (
        "ChaquopyPythonGateway.kt",
        "EmbeddedPythonGateway.kt",
        "NativePreferredGateway.kt",
        "GoRuntimeCutoverPolicy.kt",
    )
    base = ANDROID / "app/src/main/java/com/mikeyphw/bkx/android/embedded"
    for name in retired:
        assert not (base / name).exists(), name
    assert (base / "EmbeddedBkxBackend.kt").is_file()
    assert (base / "GoNativeGateway.kt").is_file()


def test_release_gate_keeps_embedded_contract_and_adds_go14_seal() -> None:
    verifier = text("build-logic/android-verification.gradle.kts")
    devtool = text(".devtool.toml")
    assert 'tasks.register("verifyBkxEmbeddedEngineContract")' in verifier
    assert 'tasks.register("verifyBkxGoOnlyAndroidRuntimeContract")' in verifier
    assert ':app:verifyBkxEmbeddedEngineContract' in devtool
    assert ':app:verifyBkxGoOnlyAndroidRuntimeContract' in devtool
