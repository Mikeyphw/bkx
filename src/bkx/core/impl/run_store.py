"""Durable JSONL RunStore implementation.

Contract rule:
- Implementations live in impl/, use ports from ports/.
- Fallback implementations work without external dependencies.
"""

from __future__ import annotations

import json
import os
import tempfile
from contextlib import contextmanager
from dataclasses import asdict, dataclass, is_dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Iterator

from bkx.core.errors import NotFoundError, ValidationError, expected_error_boundary
from bkx.core.ports.run_store import RunMetadata, RunStorePort

try:  # pragma: no cover - Windows fallback is covered by best-effort behavior.
    import fcntl
except ImportError:  # pragma: no cover
    fcntl = None  # type: ignore[assignment]

# Default location for run history
DEFAULT_RUNS_DIR = Path.home() / ".local" / "share" / "bkx" / "runs"
RUNS_FILE = DEFAULT_RUNS_DIR / "runs.jsonl"
_VALID_STATUSES = {"started", "success", "failed", "interrupted"}
_VALID_OPERATIONS = {"backup", "archive", "transfer", "restore", "run", "unknown"}


@dataclass(frozen=True)
class _RunRecord:
    """Internal record format for JSON storage."""

    run_id: str
    started_at: str  # ISO format
    completed_at: str | None
    operation: str
    status: str
    source_paths: list[str]
    destination_path: str | None
    archive_path: str | None
    files_count: int | None
    bytes_processed: int | None
    error_message: str | None
    spec_snapshot: dict[str, Any] | None


def _now() -> datetime:
    """Get current datetime."""
    return datetime.now()


def _iso_format(dt: datetime) -> str:
    """Convert datetime to ISO format string."""
    return dt.isoformat()


def _parse_iso(s: str) -> datetime:
    """Parse ISO format string to datetime."""
    return datetime.fromisoformat(s)


def _json_safe(value: Any) -> Any:
    """Return a deterministic JSON-safe representation of user/spec data."""
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, datetime):
        return value.isoformat()
    if is_dataclass(value) and not isinstance(value, type):
        return _json_safe(asdict(value))
    if isinstance(value, dict):
        return {str(_json_safe(k)): _json_safe(v) for k, v in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [_json_safe(v) for v in value]
    # Unknown objects are not allowed to leak raw into json.dumps after the file
    # has been opened. Preserve useful diagnostics with a stable textual form.
    return str(value)


def _validate_json_snapshot(snapshot: dict | None) -> dict[str, Any] | None:
    if snapshot is None:
        return None
    if not isinstance(snapshot, dict):
        raise ValidationError(msg="run spec_snapshot must be a mapping or None", details={"actual_type": type(snapshot).__name__})
    safe = _json_safe(snapshot)
    try:
        json.dumps(safe, sort_keys=True)
    except (TypeError, ValueError) as exc:  # pragma: no cover - _json_safe should handle supported shapes.
        raise ValidationError(msg="run spec_snapshot must be JSON serializable", details={"reason": str(exc)}) from exc
    return safe


def _validate_record(record: _RunRecord) -> None:
    if not isinstance(record.run_id, str) or not record.run_id.strip():
        raise ValueError("run_id must be non-empty")
    _parse_iso(record.started_at)
    if record.completed_at is not None:
        _parse_iso(record.completed_at)
    if record.operation not in _VALID_OPERATIONS:
        raise ValueError(f"invalid operation: {record.operation}")
    if record.status not in _VALID_STATUSES:
        raise ValueError(f"invalid status: {record.status}")
    if not isinstance(record.source_paths, list) or not all(isinstance(item, str) for item in record.source_paths):
        raise ValueError("source_paths must be a list of strings")
    for name, value in (("files_count", record.files_count), ("bytes_processed", record.bytes_processed)):
        if value is not None and (not isinstance(value, int) or value < 0):
            raise ValueError(f"{name} must be a non-negative integer or null")
    if record.spec_snapshot is not None:
        _validate_json_snapshot(record.spec_snapshot)


def _record_to_metadata(record: _RunRecord) -> RunMetadata:
    """Convert internal record to public metadata."""
    _validate_record(record)
    return RunMetadata(
        run_id=record.run_id,
        started_at=_parse_iso(record.started_at),
        completed_at=_parse_iso(record.completed_at) if record.completed_at else None,
        operation=record.operation,
        status=record.status,
        source_paths=tuple(record.source_paths),
        destination_path=record.destination_path,
        archive_path=record.archive_path,
        files_count=record.files_count,
        bytes_processed=record.bytes_processed,
        error_message=record.error_message,
        spec_snapshot=record.spec_snapshot,
    )


def _metadata_to_record(meta: RunMetadata) -> _RunRecord:
    """Convert public metadata to internal record."""
    snapshot = _validate_json_snapshot(meta.spec_snapshot)
    return _RunRecord(
        run_id=meta.run_id,
        started_at=_iso_format(meta.started_at),
        completed_at=_iso_format(meta.completed_at) if meta.completed_at else None,
        operation=meta.operation,
        status=meta.status,
        source_paths=[str(path) for path in meta.source_paths],
        destination_path=str(meta.destination_path) if meta.destination_path is not None else None,
        archive_path=str(meta.archive_path) if meta.archive_path is not None else None,
        files_count=meta.files_count,
        bytes_processed=meta.bytes_processed,
        error_message=meta.error_message,
        spec_snapshot=snapshot,
    )


def _serialize_record(record: _RunRecord) -> str:
    _validate_record(record)
    return json.dumps(asdict(record), sort_keys=True, separators=(",", ":")) + "\n"


class JsonRunStore:
    """Durable JSONL run history storage.

    The store is intentionally simple but not unsafe: transactions are protected
    by an advisory lock, rewrites are published with os.replace(), malformed or
    duplicate records are quarantined and removed from the live file, and run IDs
    are unique.
    """

    def __init__(self, runs_file: Path | None = None, *, max_records: int | None = None, recover_started: bool = True) -> None:
        self._runs_file = runs_file or RUNS_FILE
        if max_records is not None and max_records <= 0:
            raise ValidationError(msg="max_records must be greater than zero", details={"max_records": max_records})
        self._max_records = max_records
        if recover_started:
            self.recover_interrupted()

    @property
    def runs_file(self) -> Path:
        return self._runs_file

    @property
    def quarantine_file(self) -> Path:
        return self._runs_file.with_name(f"{self._runs_file.name}.corrupt")

    def _ensure_parent(self) -> None:
        self._runs_file.parent.mkdir(parents=True, exist_ok=True)

    @contextmanager
    def _locked(self) -> Iterator[None]:
        self._ensure_parent()
        lock_path = self._runs_file.with_name(f"{self._runs_file.name}.lock")
        with open(lock_path, "a+") as lock:
            if fcntl is not None:
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                if fcntl is not None:
                    fcntl.flock(lock.fileno(), fcntl.LOCK_UN)

    def _load_records_unlocked(self, *, clean: bool = True) -> list[_RunRecord]:
        if not self._runs_file.exists():
            return []
        records: list[_RunRecord] = []
        malformed: list[str] = []
        seen: set[str] = set()
        with open(self._runs_file, encoding="utf-8") as f:
            for line_number, raw in enumerate(f, 1):
                line = raw.strip()
                if not line:
                    continue
                try:
                    data = json.loads(line)
                    if not isinstance(data, dict):
                        raise ValueError("record must be a JSON object")
                    record = _RunRecord(**data)
                    _validate_record(record)
                    if record.run_id in seen:
                        raise ValueError(f"duplicate run_id: {record.run_id}")
                    seen.add(record.run_id)
                    records.append(record)
                except Exception as exc:
                    malformed.append(json.dumps({"line_number": line_number, "line": raw.rstrip("\n"), "error": str(exc)}, sort_keys=True))
        if malformed and clean:
            with open(self.quarantine_file, "a", encoding="utf-8") as f:
                for item in malformed:
                    f.write(item + "\n")
            self._write_records_unlocked(records)
        return records

    def _read_all_records(self) -> list[_RunRecord]:
        with self._locked():
            return self._load_records_unlocked(clean=True)

    def _write_records_unlocked(self, records: list[_RunRecord]) -> None:
        self._ensure_parent()
        records = self._apply_retention(records)
        serialized = "".join(_serialize_record(record) for record in records)
        fd, raw_tmp = tempfile.mkstemp(prefix=f".{self._runs_file.name}.", suffix=".tmp", dir=str(self._runs_file.parent))
        tmp_path = Path(raw_tmp)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(serialized)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp_path, self._runs_file)
            _fsync_dir(self._runs_file.parent)
        finally:
            tmp_path.unlink(missing_ok=True)

    def _write_records(self, records: list[_RunRecord]) -> None:
        with self._locked():
            self._write_records_unlocked(records)

    def _apply_retention(self, records: list[_RunRecord]) -> list[_RunRecord]:
        if self._max_records is None or len(records) <= self._max_records:
            return records
        sorted_records = sorted(records, key=lambda record: _parse_iso(record.started_at), reverse=True)
        keep = {record.run_id for record in sorted_records[: self._max_records]}
        return [record for record in records if record.run_id in keep]

    def recover_interrupted(self) -> int:
        """Mark runs left in started state by a prior process as interrupted."""
        with self._locked():
            records = self._load_records_unlocked(clean=True)
            changed = 0
            now = _iso_format(_now())
            recovered: list[_RunRecord] = []
            for record in records:
                if record.status == "started":
                    record = _RunRecord(
                        run_id=record.run_id,
                        started_at=record.started_at,
                        completed_at=now,
                        operation=record.operation,
                        status="interrupted",
                        source_paths=record.source_paths,
                        destination_path=record.destination_path,
                        archive_path=record.archive_path,
                        files_count=record.files_count,
                        bytes_processed=record.bytes_processed,
                        error_message=record.error_message or "Run interrupted before completion",
                        spec_snapshot=record.spec_snapshot,
                    )
                    changed += 1
                recovered.append(record)
            if changed:
                self._write_records_unlocked(recovered)
            return changed

    def record_start(
        self,
        run_id: str,
        operation: str,
        source_paths: tuple[str, ...],
        destination_path: str | None,
        archive_path: str | None,
        spec_snapshot: dict | None = None,
    ) -> RunMetadata:
        """Record the start of a run."""
        if operation not in _VALID_OPERATIONS:
            raise ValidationError(msg="Invalid run operation", details={"operation": operation})
        safe_snapshot = _validate_json_snapshot(spec_snapshot)
        metadata = RunMetadata(
            run_id=run_id,
            started_at=_now(),
            completed_at=None,
            operation=operation,
            status="started",
            source_paths=tuple(str(path) for path in source_paths),
            destination_path=str(destination_path) if destination_path is not None else None,
            archive_path=str(archive_path) if archive_path is not None else None,
            files_count=None,
            bytes_processed=None,
            error_message=None,
            spec_snapshot=safe_snapshot,
        )
        record = _metadata_to_record(metadata)
        # Validate and serialize before touching the live history file.
        _serialize_record(record)
        with self._locked():
            records = self._load_records_unlocked(clean=True)
            if any(existing.run_id == run_id for existing in records):
                raise ValidationError(msg="Run ID already exists", details={"run_id": run_id})
            self._write_records_unlocked([*records, record])
        return metadata

    def record_completion(
        self,
        run_id: str,
        status: str,
        files_count: int | None = None,
        bytes_processed: int | None = None,
        error_message: str | None = None,
    ) -> RunMetadata:
        """Record the completion of a run."""
        if status not in {"success", "failed", "interrupted"}:
            raise ValidationError(msg="Invalid run completion status", details={"status": status})
        for name, value in (("files_count", files_count), ("bytes_processed", bytes_processed)):
            if value is not None and (not isinstance(value, int) or value < 0):
                raise ValidationError(msg=f"{name} must be a non-negative integer or None", details={name: value})
        with self._locked():
            records = self._load_records_unlocked(clean=True)
            updated_index = -1
            updated_record: _RunRecord | None = None
            for i, record in enumerate(records):
                if record.run_id == run_id:
                    updated_record = _RunRecord(
                        run_id=record.run_id,
                        started_at=record.started_at,
                        completed_at=_iso_format(_now()),
                        operation=record.operation,
                        status=status,
                        source_paths=record.source_paths,
                        destination_path=record.destination_path,
                        archive_path=record.archive_path,
                        files_count=files_count,
                        bytes_processed=bytes_processed,
                        error_message=error_message,
                        spec_snapshot=record.spec_snapshot,
                    )
                    records[i] = updated_record
                    updated_index = i
                    break
            if updated_index < 0 or updated_record is None:
                raise NotFoundError(msg=f"Run not found: {run_id}", details={"run_id": run_id})
            self._write_records_unlocked(records)
            return _record_to_metadata(updated_record)

    def list_runs(self, limit: int = 20, operation: str | None = None) -> tuple[RunMetadata, ...]:
        """List recent runs."""
        if limit <= 0:
            raise ValidationError(msg="Run list limit must be greater than zero", details={"limit": limit})
        records = self._read_all_records()
        if operation:
            records = [r for r in records if r.operation == operation]
        metadatas = [_record_to_metadata(r) for r in records]
        metadatas.sort(key=lambda m: m.started_at, reverse=True)
        return tuple(metadatas[:limit])

    def count_runs(self, operation: str | None = None) -> int:
        records = self._read_all_records()
        if operation:
            return sum(1 for record in records if record.operation == operation)
        return len(records)

    def get_run(self, run_id: str) -> RunMetadata | None:
        """Get a specific run by ID."""
        records = self._read_all_records()
        for record in records:
            if record.run_id == run_id:
                return _record_to_metadata(record)
        return None

    def clear_all(self) -> int:
        """Clear all run history, even if the live file only contains malformed lines."""
        with self._locked():
            valid_count = len(self._load_records_unlocked(clean=True))
            existed = self._runs_file.exists()
            self._write_records_unlocked([])
            return valid_count if valid_count > 0 else int(existed)


class DisabledRunStore:
    """No-op run store used when run history is disabled by configuration."""

    def record_start(self, run_id, operation, source_paths, destination_path, archive_path, spec_snapshot=None):
        return RunMetadata(
            run_id=run_id, started_at=_now(), completed_at=None, operation=operation, status="started",
            source_paths=source_paths, destination_path=destination_path, archive_path=archive_path,
            files_count=None, bytes_processed=None, error_message=None, spec_snapshot=spec_snapshot,
        )

    def record_completion(self, run_id, status, files_count=None, bytes_processed=None, error_message=None):
        now = _now()
        return RunMetadata(
            run_id=run_id, started_at=now, completed_at=now, operation="unknown", status=status,
            source_paths=(), destination_path=None, archive_path=None, files_count=files_count,
            bytes_processed=bytes_processed, error_message=error_message, spec_snapshot=None,
        )

    def list_runs(self, limit=20, operation=None):
        return ()

    def count_runs(self, operation=None):
        return 0

    def get_run(self, run_id):
        return None

    def clear_all(self):
        return 0


# Global instance for convenience
_default_store: RunStorePort | None = None
_default_store_key: tuple[bool, int | None] | None = None


def get_run_store(*, enabled: bool = True, max_records: int | None = None) -> RunStorePort:
    """Get the default run store, honoring live run-history settings."""
    if not enabled:
        return DisabledRunStore()
    global _default_store, _default_store_key
    key = (enabled, max_records)
    if _default_store is None or _default_store_key != key:
        _default_store = JsonRunStore(max_records=max_records)
        _default_store_key = key
    return _default_store


def set_run_store(store: RunStorePort) -> None:
    """Set the default run store instance (for testing)."""
    global _default_store, _default_store_key
    _default_store = store
    _default_store_key = None


# For backward compatibility, add the missing function
def _metadata_to_metadata(meta: RunMetadata) -> RunMetadata:
    """Identity function for type checking."""
    return meta


def _fsync_dir(path: Path) -> None:
    try:
        fd = os.open(path, os.O_DIRECTORY)
    except (AttributeError, OSError):
        return
    try:
        os.fsync(fd)
    finally:
        os.close(fd)
