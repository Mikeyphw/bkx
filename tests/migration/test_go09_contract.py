from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_go09_transfer_sources_and_migration_boundary():
    for rel in (
        "internal/transferops/transfer.go",
        "internal/transferops/transfer_test.go",
        "tools/go_migration/verify_go09_parity.py",
        "scripts/validate-go09.sh",
    ):
        assert (ROOT / rel).is_file(), rel
    main = (ROOT / "cmd/bkx/main.go").read_text()
    assert 'args[1] == "transfer"' in main
    assert 'case "plan":' in main
    assert 'case "run":' in main
    assert "Go migration scaffold is not the production backend yet" in main


def test_go09_transfer_preserves_safe_publication_and_run_history():
    source = (ROOT / "internal/transferops/transfer.go").read_text()
    assert "publishNoReplace" in source
    assert "renameNoReplace" in source
    assert "os.O_EXCL" in source
    assert "os.Link(" not in source
    assert "RecordStart" in source
    assert "RecordCompletion" in source
    assert '"transfer"' in source
    assert "ensureNoDestinationSymlink" in source


def test_go09_transfer_has_collision_checksum_and_cancel_regressions():
    tests = (ROOT / "internal/transferops/transfer_test.go").read_text()
    for name in (
        "TestMultiSourceMappingsAndCollisionPreflight",
        "TestChecksumAlgorithmAndExpectedDigest",
        "TestCancellationBeforePublicationMarksRunInterrupted",
        "TestNoReplaceFallbackHandlesEPERMAndNeverClobbers",
    ):
        assert name in tests


def test_go09_validator_chains_go08_and_android_arch_gate():
    text = (ROOT / "scripts/validate-go09.sh").read_text()
    assert "validate-go08.sh" in text
    assert "verify_go09_parity.py" in text
    assert "go version -m" in text
    assert "GOOS=android" in text
    assert "GOARCH=arm64" in text
    assert "machine != 183" in text
    assert "file -b" not in text
