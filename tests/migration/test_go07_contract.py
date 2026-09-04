from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_go07_restore_sources_and_migration_boundary():
    for rel in (
        "internal/archiveio/restore.go",
        "internal/archiveio/restore_test.go",
        "tools/go_migration/verify_go07_parity.py",
        "scripts/validate-go07.sh",
    ):
        assert (ROOT / rel).is_file(), rel
    main = (ROOT / "cmd/bkx/main.go").read_text()
    assert 'args[1] == "restore"' in main
    assert 'case "preview":' in main
    assert 'case "extract":' in main
    assert "Go migration scaffold is not the production backend yet" in main


def test_go07_restore_is_staged_constrained_and_rollback_capable():
    source = (ROOT / "internal/archiveio/restore.go").read_text()
    assert ".bkx-restore." in source
    assert ".bkx-rollback." in source
    assert "ensureNoSymlinkDestinationRoot" in source
    assert "ensureNoExistingSymlinkPath" in source
    assert "Archive link members are unsafe for restore" in source
    assert "Archive symlink members are unsafe for restore" in source
    assert "restoreRename(replaced[target], target)" in source
    assert "Preflight every target before mutation" in source


def test_go07_preview_and_selector_contract():
    source = (ROOT / "internal/archiveio/restore.go").read_text()
    assert "RestorePreviewResult" in source
    assert "ExistingFiles" in source
    assert "ensureRestoreSelectorsFound" in source
    assert "Requested restore paths were not found in archive" in source


def test_go07_validator_keeps_go06_and_deterministic_android_arch_gate():
    text = (ROOT / "scripts/validate-go07.sh").read_text()
    assert "validate-go06.sh" in text
    assert "go version -m" in text
    assert "GOOS=android" in text
    assert "GOARCH=arm64" in text
    assert "machine != 183" in text
    assert "file -b" not in text
