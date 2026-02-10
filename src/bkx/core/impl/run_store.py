"""JSON-based RunStore implementation.

Contract rule:
- Implementations live in impl/, use ports from ports/.
- Fallback implementations work without external dependencies.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path

from bkx.core.ports.run_store import RunMetadata, RunStorePort

# Default location for run history
DEFAULT_RUNS_DIR = Path.home() / ".local" / "share" / "bkx" / "runs"
RUNS_FILE = DEFAULT_RUNS_DIR / "runs.jsonl"


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
    spec_snapshot: dict | None


def _ensure_runs_dir() -> Path:
    """Ensure the runs directory exists."""
    DEFAULT_RUNS_DIR.mkdir(parents=True, exist_ok=True)
    return DEFAULT_RUNS_DIR


def _now() -> datetime:
    """Get current datetime."""
    return datetime.now()


def _iso_format(dt: datetime) -> str:
    """Convert datetime to ISO format string."""
    return dt.isoformat()


def _parse_iso(s: str) -> datetime:
    """Parse ISO format string to datetime."""
    return datetime.fromisoformat(s)


def _record_to_metadata(record: _RunRecord) -> RunMetadata:
    """Convert internal record to public metadata."""
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
    return _RunRecord(
        run_id=meta.run_id,
        started_at=_iso_format(meta.started_at),
        completed_at=_iso_format(meta.completed_at) if meta.completed_at else None,
        operation=meta.operation,
        status=meta.status,
        source_paths=list(meta.source_paths),
        destination_path=meta.destination_path,
        archive_path=meta.archive_path,
        files_count=meta.files_count,
        bytes_processed=meta.bytes_processed,
        error_message=meta.error_message,
        spec_snapshot=meta.spec_snapshot,
    )


class JsonRunStore:
    """JSON-based run history storage.

    Stores runs as JSONL (one JSON object per line) for append-only
    efficiency and easy inspection.
    """

    def __init__(self, runs_file: Path | None = None) -> None:
        """Initialize the run store.

        Args:
            runs_file: Optional custom path for the runs file.
        """
        self._runs_file = runs_file or RUNS_FILE

    def _read_all_records(self) -> list[_RunRecord]:
        """Read all records from the runs file."""
        if not self._runs_file.exists():
            return []

        records: list[_RunRecord] = []
        with open(self._runs_file) as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        data = json.loads(line)
                        records.append(_RunRecord(**data))
                    except (json.JSONDecodeError, TypeError, KeyError):
                        # Skip malformed lines
                        continue
        return records

    def _write_records(self, records: list[_RunRecord]) -> None:
        """Write all records to the runs file."""
        _ensure_runs_dir()
        with open(self._runs_file, "w") as f:
            for record in records:
                f.write(json.dumps(asdict(record)) + "\n")

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
        metadata = RunMetadata(
            run_id=run_id,
            started_at=_now(),
            completed_at=None,
            operation=operation,
            status="started",
            source_paths=source_paths,
            destination_path=destination_path,
            archive_path=archive_path,
            files_count=None,
            bytes_processed=None,
            error_message=None,
            spec_snapshot=spec_snapshot,
        )

        record = _metadata_to_record(metadata)
        # Append to file
        _ensure_runs_dir()
        with open(self._runs_file, "a") as f:
            f.write(json.dumps(asdict(record)) + "\n")

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
        records = self._read_all_records()

        # Find and update the record
        updated_index = -1
        for i, record in enumerate(records):
            if record.run_id == run_id:
                now = _now()
                records[i] = _RunRecord(
                    run_id=record.run_id,
                    started_at=record.started_at,
                    completed_at=_iso_format(now),
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
                updated_index = i
                break

        if updated_index >= 0:
            self._write_records(records)
            return _record_to_metadata(records[updated_index])

        # If not found, create a new completed record
        metadata = RunMetadata(
            run_id=run_id,
            started_at=_now(),
            completed_at=_now(),
            operation="unknown",
            status=status,
            source_paths=tuple(),
            destination_path=None,
            archive_path=None,
            files_count=files_count,
            bytes_processed=bytes_processed,
            error_message=error_message,
            spec_snapshot=None,
        )
        return metadata

    def list_runs(
        self,
        limit: int = 20,
        operation: str | None = None,
    ) -> tuple[RunMetadata, ...]:
        """List recent runs."""
        records = self._read_all_records()

        # Filter by operation if specified
        if operation:
            records = [r for r in records if r.operation == operation]

        # Convert to metadata and sort by start time (newest first)
        metadatas = [_record_to_metadata(r) for r in records]
        metadatas.sort(key=lambda m: m.started_at, reverse=True)

        return tuple(metadatas[:limit])

    def get_run(self, run_id: str) -> RunMetadata | None:
        """Get a specific run by ID."""
        records = self._read_all_records()

        for record in records:
            if record.run_id == run_id:
                return _record_to_metadata(record)

        return None

    def clear_all(self) -> int:
        """Clear all run history."""
        records = self._read_all_records()
        count = len(records)

        if count > 0:
            _ensure_runs_dir()
            # Create empty file
            with open(self._runs_file, "w"):
                pass

        return count


# Global instance for convenience
_default_store: RunStorePort | None = None


def get_run_store() -> RunStorePort:
    """Get the default run store instance."""
    global _default_store
    if _default_store is None:
        _default_store = JsonRunStore()
    return _default_store


def set_run_store(store: RunStorePort) -> None:
    """Set the default run store instance (for testing)."""
    global _default_store
    _default_store = store


# For backward compatibility, add the missing function
def _metadata_to_metadata(meta: RunMetadata) -> RunMetadata:
    """Identity function for type checking."""
    return meta
