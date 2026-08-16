from __future__ import annotations

import json
from pathlib import Path

import pytest

from bkx.core.errors import NotFoundError, ValidationError
from bkx.core.impl.run_store import JsonRunStore
from bkx.core.services.run import RunService


def test_custom_run_store_parent_and_json_safe_snapshot(tmp_path: Path) -> None:
    runs = tmp_path / "nested" / "history" / "runs.jsonl"
    store = JsonRunStore(runs_file=runs)

    meta = store.record_start("run-1", "archive", ("/src",), None, "/out.tar", spec_snapshot={"path": tmp_path / "src"})
    store.record_completion(meta.run_id, "success", files_count=1, bytes_processed=4)

    assert runs.exists()
    rows = [json.loads(line) for line in runs.read_text().splitlines()]
    assert rows[0]["spec_snapshot"]["path"] == str(tmp_path / "src")


def test_malformed_records_are_quarantined_and_clearable(tmp_path: Path) -> None:
    runs = tmp_path / "runs.jsonl"
    runs.write_text('{"run_id":"bad","started_at":"not-a-date","completed_at":null,"operation":"archive","status":"started","source_paths":[],"destination_path":null,"archive_path":null,"files_count":null,"bytes_processed":null,"error_message":null,"spec_snapshot":null}\nnot-json\n')
    store = JsonRunStore(runs_file=runs, recover_started=False)

    assert store.list_runs() == ()
    assert store.quarantine_file.exists()
    assert runs.read_text() == ""
    assert store.clear_all() == 1
    assert runs.exists() and runs.read_text() == ""


def test_unique_run_ids_and_unknown_completion_are_explicit(tmp_path: Path) -> None:
    store = JsonRunStore(runs_file=tmp_path / "runs.jsonl")
    store.record_start("same", "backup", ("/src",), "/dest", None)

    with pytest.raises(ValidationError):
        store.record_start("same", "backup", ("/src",), "/dest", None)
    with pytest.raises(NotFoundError):
        store.record_completion("missing", "success")


def test_started_runs_recover_as_interrupted_on_new_store(tmp_path: Path) -> None:
    path = tmp_path / "runs.jsonl"
    first = JsonRunStore(runs_file=path, recover_started=False)
    first.record_start("crashed", "restore", ("/archive",), "/dest", "/archive")

    recovered = JsonRunStore(runs_file=path)
    run = recovered.get_run("crashed")

    assert run is not None
    assert run.status == "interrupted"
    assert run.completed_at is not None


def test_retention_and_total_count_are_truthful(tmp_path: Path) -> None:
    store = JsonRunStore(runs_file=tmp_path / "runs.jsonl", max_records=3)
    for index in range(5):
        run_id = f"run-{index}"
        store.record_start(run_id, "archive", (str(index),), None, f"{index}.tar")
        store.record_completion(run_id, "success")

    service = RunService(run_store=store, max_list_limit=2)
    result = service.list(limit=10)

    assert len(result.runs) == 2
    assert result.total_count == 3
    assert result.limit == 2
