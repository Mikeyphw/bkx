from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_go06_archive_creation_sources_and_boundary():
    for rel in (
        "internal/archiveio/create.go",
        "internal/archiveio/create_test.go",
        "tools/go_migration/verify_go06_parity.py",
        "scripts/validate-go06.sh",
    ):
        assert (ROOT / rel).is_file(), rel
    main = (ROOT / "cmd/bkx/main.go").read_text()
    assert 'case "create":' in main
    assert 'args[0] == "migration"' in main
    assert "Go migration scaffold is not the production backend yet" in main


def test_go06_creation_is_transactional_and_verifies_before_publish():
    source = (ROOT / "internal/archiveio/create.go").read_text()
    assert "os.CreateTemp" in source
    assert "Verify(tmpPath" in source
    assert "syncFile(tmpPath)" in source
    assert "os.Rename(tmpPath, output)" in source
    assert "if !published" in source
    assert "Archive output already exists" in source


def test_go06_compression_and_cross_reader_contract():
    source = (ROOT / "internal/archiveio/create.go").read_text()
    reader = (ROOT / "internal/archiveio/archiveio.go").read_text()
    for algorithm in ('"none"', '"gzip"', '"bzip2"', '"xz"'):
        assert algorithm in source
    assert "zipMethodBzip2" in source
    assert "zipMethodLZMA" in source
    assert "openZipPayload" in reader
    assert "LZMA-compressed ZIP" in reader


def test_go06_validator_keeps_deterministic_android_arch_gate():
    text = (ROOT / "scripts/validate-go06.sh").read_text()
    assert "go version -m" in text
    assert "GOOS=android" in text
    assert "GOARCH=arm64" in text
    assert "machine != 183" in text
    assert "file -b" not in text
