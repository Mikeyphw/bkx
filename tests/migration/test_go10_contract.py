from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FROZEN = {
    "archive.browse",
    "archive.info",
    "archive.list",
    "archive.verify",
    "backup.create",
    "backup.plan",
    "filesystem.list",
    "filesystem.stat",
    "restore.extract",
    "restore.preview",
    "runs.list",
    "runs.show",
    "storage.locations",
    "transfer.plan",
    "transfer.run",
}


def test_go10_service_sources_and_migration_boundary():
    for rel in (
        "internal/services/dispatcher.go",
        "internal/services/dispatcher_test.go",
        "tools/go_migration/verify_go10_service_parity.py",
        "scripts/validate-go10.sh",
    ):
        assert (ROOT / rel).is_file(), rel
    main = (ROOT / "cmd/bkx/main.go").read_text()
    assert 'args[1] == "service"' in main
    assert 'case "operations":' in main
    assert 'case "dispatch":' in main
    assert "Go migration scaffold is not the production backend yet" in main


def test_go10_dispatcher_composes_all_migrated_domains_without_shell_surface():
    source = (ROOT / "internal/services/dispatcher.go").read_text()
    for package in ("archiveio", "backupops", "fsutil", "runstore", "transferops"):
        assert f'"bkx/internal/{package}"' in source
    assert '"os/exec"' not in source
    assert "exec.Command" not in source
    assert "sh -c" not in source
    assert "shell" not in source.lower()
    for operation in FROZEN:
        assert f'"{operation}"' in source


def test_go10_service_regressions_cover_registry_composition_and_cancel():
    tests = (ROOT / "internal/services/dispatcher_test.go").read_text()
    for name in (
        "TestSupportedOperationsMatchFrozenBridge",
        "TestFilesystemAndStorageDispatch",
        "TestBackupArchiveRestoreRunsAndArchiveIndexDispatch",
        "TestTransferDispatchAndCancellation",
        "TestServiceRefusesUnknownAndSyncOperations",
        "TestArchiveIndexMergesRunHistoryOutsideRoots",
    ):
        assert name in tests


def test_go10_parity_covers_all_read_plan_history_bridge_surfaces():
    text = (ROOT / "tools/go_migration/verify_go10_service_parity.py").read_text()
    for operation in (
        "filesystem.stat",
        "filesystem.list",
        "storage.locations",
        "archive.info",
        "archive.browse",
        "archive.verify",
        "archive.list",
        "backup.plan",
        "transfer.plan",
        "restore.preview",
        "runs.list",
        "runs.show",
    ):
        assert f'"{operation}"' in text
    assert "engine.supported_operations()" in text


def test_go10_validator_chains_go09_and_android_arch_gate():
    text = (ROOT / "scripts/validate-go10.sh").read_text()
    assert "validate-go09.sh" in text
    assert "verify_go10_service_parity.py" in text
    assert "go version -m" in text
    assert "GOOS=android" in text
    assert "GOARCH=arm64" in text
    assert "machine != 183" in text
    assert "file -b" not in text
