from __future__ import annotations

from pathlib import Path
import tomllib


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "android" / "BKX.Android" / "gradle" / "libs.versions.toml"
APP_GRADLE = ROOT / "android" / "BKX.Android" / "app" / "build.gradle.kts"
DEVTOOL = ROOT / ".devtool.toml"

EXPECTED = {
    "composeBom": "2026.06.01",
    "activity": "1.12.4",
    "appcompat": "1.7.1",
    "coreKtx": "1.17.0",
    "lifecycle": "2.10.0",
}


def test_android_dependency_lane_is_pinned_to_verified_api36_versions():
    catalog = tomllib.loads(CATALOG.read_text(encoding="utf-8"))
    versions = catalog["versions"]
    assert {key: versions[key] for key in EXPECTED} == EXPECTED


def test_android_sdk36_contract_stays_aligned_across_gradle_and_devtool():
    app_gradle = APP_GRADLE.read_text(encoding="utf-8")
    devtool = tomllib.loads(DEVTOOL.read_text(encoding="utf-8"))

    assert "val bkxValidationSdk = 36" in app_gradle
    assert "compileSdk = bkxValidationSdk" in app_gradle
    assert "targetSdk = bkxValidationSdk" in app_gradle
    assert devtool["targets"]["bkx_android"]["android"]["platform"] == "36"


def test_sdk36_dependency_guard_runs_in_preflight_and_release_seal():
    app_gradle = APP_GRADLE.read_text(encoding="utf-8")
    devtool = tomllib.loads(DEVTOOL.read_text(encoding="utf-8"))

    task = ":app:verifyBkxAndroidSdk36DependencyLane"
    assert task in devtool["validation"]["phases"]["preflight"]
    assert 'tasks.register("verifyBkxAndroidSdk36DependencyLane")' in app_gradle
    assert '"verifyBkxAndroidSdk36DependencyLane"' in app_gradle
