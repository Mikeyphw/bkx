from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_go08_backup_sources_and_migration_boundary():
    for rel in (
        "internal/backupops/backup.go",
        "internal/backupops/backup_test.go",
        "tools/go_migration/verify_go08_parity.py",
        "scripts/validate-go08.sh",
    ):
        assert (ROOT / rel).is_file(), rel
    main = (ROOT / "cmd/bkx/main.go").read_text()
    assert 'args[1] == "backup"' in main
    assert 'case "plan":' in main
    assert 'case "create":' in main
    assert "Go migration scaffold is not the production backend yet" in main


def test_go08_backup_reuses_archive_and_run_history_primitives():
    source = (ROOT / "internal/backupops/backup.go").read_text()
    assert "archiveio.Create" in source
    assert "RecordStart" in source
    assert "RecordCompletion" in source
    assert 'operation="backup"' not in source  # Go uses the typed positional run-store API.
    assert '"backup"' in source
    assert "publishFile" in source
    assert "os.Link(tmp, dst)" in source
    assert "Transfer destination already exists" in source


def test_go08_cancellation_and_progress_are_prepublication_safe():
    source = (ROOT / "internal/backupops/backup.go").read_text()
    assert "ErrCancelled" in source
    assert 'emit(options, "archiving"' in source
    assert 'emit(options, "publishing"' in source
    assert 'complete("interrupted"' in source
    tests = (ROOT / "internal/backupops/backup_test.go").read_text()
    assert "TestCreateCancellationAfterArchivePreventsPublicationAndMarksInterrupted" in tests


def test_go08_validator_keeps_go07_and_deterministic_android_arch_gate():
    text = (ROOT / "scripts/validate-go08.sh").read_text()
    assert "validate-go07.sh" in text
    assert "verify_go08_parity.py" in text
    assert "go version -m" in text
    assert "GOOS=android" in text
    assert "GOARCH=arm64" in text
    assert "machine != 183" in text
    assert "file -b" not in text
