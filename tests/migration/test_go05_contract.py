from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_go05_archive_read_sources_and_boundary():
    for rel in (
        "internal/archiveio/archiveio.go",
        "internal/archiveio/archiveio_test.go",
        "tools/go_migration/verify_go05_parity.py",
        "scripts/validate-go05.sh",
    ):
        assert (ROOT / rel).is_file(), rel
    main = (ROOT / "cmd/bkx/main.go").read_text()
    assert 'args[1] == "archive"' in main
    assert 'args[0] == "migration"' in main
    assert "Go migration scaffold is not the production backend yet" in main


def test_go05_is_read_only_and_keeps_mutating_archive_operations_deferred():
    source = (ROOT / "internal/archiveio/archiveio.go").read_text()
    assert "func List(" in source
    assert "func Info(" in source
    assert "func Browse(" in source
    assert "func Verify(" in source
    assert "func Create(" not in source
    assert "func Extract(" not in source


def test_go05_validator_retains_deterministic_android_arch_gate():
    text = (ROOT / "scripts/validate-go05.sh").read_text()
    assert "go version -m" in text
    assert "GOOS=android" in text
    assert "GOARCH=arm64" in text
    assert "machine != 183" in text
    assert "file -b" not in text
