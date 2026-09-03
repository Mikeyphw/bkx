from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_go02_go03_files_and_fail_closed_ledger_are_present() -> None:
    required = [
        "internal/berror/errors.go",
        "internal/config/config.go",
        "internal/models/models.go",
        "internal/runstore/store.go",
        "internal/validation/validation.go",
        "scripts/validate-go02-go03.sh",
        "tools/go_migration/verify_go02_go03_parity.py",
    ]
    for relative in required:
        assert (ROOT / relative).is_file(), relative

    ledger = (ROOT / "docs/migration/python-to-go.md").read_text(encoding="utf-8")
    assert "GO02 — Run-history persistence compatibility — COMPLETE" in ledger
    assert "GO03 — Models, validation, config — COMPLETE" in ledger
    assert "production operations" in ledger


def test_go03_still_consumes_frozen_go00_core_contract() -> None:
    core = json.loads((ROOT / "migration/contracts/v1/core.json").read_text(encoding="utf-8"))
    assert core["contractVersion"] == 1
    assert len(core["config"]["fields"]) == 12
    assert len(core["runHistory"]["recordFields"]) == 12
    assert core["runHistory"]["lockSuffix"] == ".lock"
    assert core["runHistory"]["quarantineSuffix"] == ".corrupt"
