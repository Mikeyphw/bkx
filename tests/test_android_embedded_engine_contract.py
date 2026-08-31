from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android" / "BKX.Android"


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_chaquopy_lane_is_pinned_and_sdk36_is_retained() -> None:
    catalog = text("android/BKX.Android/gradle/libs.versions.toml")
    app = text("android/BKX.Android/app/build.gradle.kts")
    assert 'agp = "9.2.1"' in catalog
    assert 'chaquopy = "17.0.0"' in catalog
    assert 'minSdk = 24' in app
    assert 'compileSdk = bkxValidationSdk' in app
    assert 'val bkxValidationSdk = 36' in app
    assert 'val bkxSupportedEmbeddedPythonVersions = setOf("3.13", "3.14")' in app
    assert '"/data/data/com.termux/files/usr/bin/python3"' in app
    assert 'version = bkxEmbeddedPythonVersion' in app
    assert 'buildPython(bkxBuildPythonCommand)' in app
    assert 'bkx.buildPython' in app
    assert 'srcDir(rootProject.file("../../src"))' in app
    assert '"arm64-v8a", "x86_64"' in app


def test_android_api_is_core_only_and_never_cli_shell_dispatch() -> None:
    engine = text("src/bkx/android_api/engine.py")
    assert "from bkx.core" in engine
    for forbidden in ("bkx.__main__", "subprocess", "argparse", "textual", "typer"):
        assert forbidden not in engine


def test_production_graph_uses_embedded_backend() -> None:
    graph = text("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt")
    assert "EmbeddedBkxBackend" in graph
    assert "ChaquopyPythonGateway" in graph
    assert "TermuxBridgeAdapter(" not in graph


def test_legacy_termux_execution_adapter_is_removed() -> None:
    assert not (ANDROID / "app/src/main/java/com/mikeyphw/bkx/android/termux/TermuxBridgeAdapter.kt").exists()
    assert (ANDROID / "app/src/main/java/com/mikeyphw/bkx/android/embedded/EmbeddedBkxBackend.kt").is_file()


def test_parity_suite_exists_and_release_gate_depends_on_embedded_contract() -> None:
    assert (ROOT / "tests/android_api/test_embedded_android_api.py").is_file()
    verifier = text("build-logic/android-verification.gradle.kts")
    assert 'tasks.register("verifyBkxEmbeddedEngineContract")' in verifier
    assert '"verifyBkxEmbeddedEngineContract"' in verifier
