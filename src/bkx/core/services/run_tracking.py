"""Shared service-level run history tracking."""

from __future__ import annotations

import uuid
from dataclasses import asdict, is_dataclass
from pathlib import Path
from typing import Any, Callable, TypeVar

from ..ports.run_store import RunStorePort

T = TypeVar("T")


def snapshot_spec(spec: Any) -> dict[str, Any]:
    return {"type": type(spec).__name__, "values": _jsonish(spec)}


def track_run(
    run_store: RunStorePort | None,
    *,
    operation: str,
    source_paths: tuple[str, ...],
    destination_path: str | None,
    archive_path: str | None,
    spec_snapshot: dict[str, Any] | None,
    execute: Callable[[], T],
    files_count: Callable[[T], int | None],
    bytes_processed: Callable[[T], int | None],
) -> T:
    if run_store is None:
        return execute()
    run_id = uuid.uuid4().hex
    run_store.record_start(
        run_id,
        operation,
        source_paths,
        destination_path,
        archive_path,
        spec_snapshot=spec_snapshot,
    )
    try:
        result = execute()
    except Exception as exc:
        try:
            run_store.record_completion(run_id, "failed", error_message=str(exc))
        finally:
            raise
    status = "success" if bool(getattr(result, "success", True)) else "failed"
    run_store.record_completion(
        run_id,
        status,
        files_count=files_count(result),
        bytes_processed=bytes_processed(result),
        error_message="; ".join(getattr(result, "errors", ()) or ()) or None,
    )
    return result


def _jsonish(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, Path):
        return str(value)
    if is_dataclass(value) and not isinstance(value, type):
        return _jsonish(asdict(value))
    if isinstance(value, dict):
        return {str(_jsonish(k)): _jsonish(v) for k, v in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [_jsonish(v) for v in value]
    return str(value)
