from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_go04_sources_and_fail_closed_boundary():
    for rel in (
        "internal/fsutil/fsutil.go",
        "internal/fsutil/traversal.go",
        "internal/hashing/hashing.go",
        "internal/tools/tools.go",
        "tools/go_migration/verify_go04_parity.py",
        "scripts/validate-go04.sh",
    ):
        assert (ROOT / rel).is_file(), rel
    main = (ROOT / "cmd/bkx/main.go").read_text()
    assert 'args[0] == "migration"' in main
    assert "Go migration scaffold is not the production backend yet" in main


def test_android_architecture_gate_is_deterministic_not_file_heuristic():
    go02 = (ROOT / "scripts/validate-go02-go03.sh").read_text()
    go04 = (ROOT / "scripts/validate-go04.sh").read_text()
    for text in (go02, go04):
        assert "go version -m" in text
        assert "GOOS=android" in text
        assert "GOARCH=arm64" in text
        assert "machine != 183" in text
        assert "file -b" not in text
        assert "warning: file(1)" not in text
