from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android" / "BKX.Android"
MAIN = ANDROID / "app" / "src" / "main"


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_process_scoped_runtime_and_dynamic_root_route_are_sealed() -> None:
    coordinator = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/runtime/BkxOperationCoordinator.kt")
    graph = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt")
    gateway = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/RootAwareGateway.kt")
    application = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/BkxAndroidApplication.kt")
    assert "SupervisorJob() + Dispatchers.IO" in coordinator
    assert "foregroundOperationMutex" in coordinator
    assert "startProgressPoller" in coordinator
    assert "OperationFailureMapper.interrupted" in coordinator
    assert "recoverInterruptedOperations" in application
    assert "modeProvider = { settings.readNow().rootAccessMode }" in graph
    assert "val local = GoNativeGateway(appContext)" in graph
    assert "ChaquopyPythonGateway" not in graph
    assert "NativePreferredGateway" not in graph
    assert "requestRoutes" in gateway and "requestIdFrom" in gateway


def test_restore_policy_matches_kotlin_and_python() -> None:
    models = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/restore/RestoreModels.kt")
    engine = read("src/bkx/android_api/engine.py")
    archives = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/archives/ArchivesScreen.kt")
    for path in ("/proc", "/sys", "/dev", "/system", "/metadata", "/data/system", "/data/misc"):
        assert path in models and path in engine
    assert 'ROOT_CONFIRMATION_PHRASE = "RESTORE ROOT"' in models
    assert 'payload.get("root_confirmation") != "RESTORE ROOT"' in engine
    assert "_restore_destination_writable_by_current_process" in engine
    assert "os.W_OK | os.X_OK" in engine
    assert 'if (destination == null)' in models
    assert "does not already exist" in engine
    assert "requires an existing directory" in engine
    assert "New destination path" in archives and "Existing destination path" in archives


def test_progress_metrics_and_safe_termux_symlink_behavior_are_sealed() -> None:
    engine = read("src/bkx/android_api/engine.py")
    archiver = read("src/bkx/core/impl/archiver.py")
    traversal = read("src/bkx/core/impl/traversal.py")
    backup = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/backup/BackupScreen.kt")
    operations = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/operations/OperationModels.kt")
    assert '"metrics"' in engine and '"duration_ms"' in engine
    assert '"run.cancel.cooperative"' in engine and '"run.progress"' in engine
    assert 'symlink_policy="skip" if spec.skip_symlinks else "reject"' in archiver
    assert 'SymlinkPolicy = Literal["reject", "skip", "preserve"]' in traversal
    assert "Symbolic links are skipped safely" in backup
    assert "Follow symbolic links" not in backup
    assert "maxOperations: Int = 64" in operations and "maxEventsPerRequest: Int = 200" in operations
    backup_schema = read("src/bkx/core/schemas/backup_spec.json")
    archive_schema = read("src/bkx/core/schemas/archive_spec.json")
    assert '"skip_symlinks"' in backup_schema and '"skip_symlinks"' in archive_schema


def test_release_assets_and_devtool_matrix_exist() -> None:
    app_build = read("android/BKX.Android/app/build.gradle.kts")
    verification = read("build-logic/android-verification.gradle.kts")
    build = app_build + "\n" + verification
    devtool = read(".devtool.toml")
    release = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/release/BkxAndroidReleaseContract.kt")
    release_contract = read("android/BKX.Android/docs/RELEASE_CONTRACT.md")
    release_validation = read("android/BKX.Android/docs/RELEASE_VALIDATION.md")
    architecture = read("android/BKX.Android/docs/ARCHITECTURE.md")
    assert "verifyBkxEmbeddedParitySeal" in build
    assert "verifyBkxReleaseHygiene" in build
    assert "verifyBkxDeviceQualificationContract" in build
    assert "verifyBkxLargeTreePerformanceSeal" in build
    assert "verifyBkxStorageCompletenessContract" in build
    assert "verifyBkxAccessibilityVisualContract" in build
    assert "verifyBkxFinalReleaseContract" in build
    for task in (
        ":app:verifyBkxRuntimeHardeningContract",
        ":app:verifyBkxRestoreSafetySeal",
        ":app:verifyBkxPerformanceAndProgressContract",
        ":app:verifyBkxLargeTreePerformanceSeal",
        ":app:verifyBkxStorageCompletenessContract",
        ":app:verifyBkxAccessibilityVisualContract",
        ":app:verifyBkxUxHardeningContract",
        ":app:verifyBkxEmbeddedParitySeal",
        ":app:verifyBkxReleaseHygiene",
        ":app:verifyBkxDeviceQualificationContract",
        ":app:verifyBkxGoProductionCutoverContract",
        ":app:verifyBkxGoOnlyAndroidRuntimeContract",
        ":app:verifyBkxFinalReleaseContract",
    ):
        assert task in devtool and task in release
    assert "versionCode = bkxVersionCode" in app_build
    assert "versionName = bkxVersionName" in app_build
    assert 'version = "1.0.0"' in read("pyproject.toml")
    assert "large-tree performance" in release_contract
    assert "qualify-device.sh" in release_validation
    assert "protocol-v1" in architecture
    assert (ANDROID / "scripts" / "verify-embedded-parity.py").is_file()
    assert (ANDROID / "scripts" / "qualify-device.sh").is_file()
    assert (ANDROID / "app" / "src" / "androidTest" / "java" / "com" / "mikeyphw" / "bkx" / "android" / "release" / "ReleaseQualificationInstrumentationTest.kt").is_file()

def test_release_production_source_has_no_termux_execution_or_generic_root_shell() -> None:
    forbidden = (
        "com.termux.permission.RUN_COMMAND",
        "com.termux.RUN_COMMAND",
        "TermuxBridgeAdapter(",
        "termux_run_command",
        "TermuxRunCommand",
        "su -c",
    )
    source_files = [
        path for path in MAIN.rglob("*")
        if path.is_file() and path.suffix in {".kt", ".java", ".xml", ".aidl"}
    ]
    for path in source_files:
        text = path.read_text(encoding="utf-8")
        for token in forbidden:
            assert token not in text, f"{token!r} in {path}"

    go_gateway = read("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt")
    assert "ProcessBuilder(" in go_gateway
    assert "applicationInfo.nativeLibraryDir" in go_gateway
    assert '"android-runtime"' in go_gateway and '"bridge-session"' in go_gateway
    for token in ("/system/bin/sh", "sh -c", "bash -c", "su -c", "Runtime.getRuntime().exec"):
        assert token not in go_gateway
