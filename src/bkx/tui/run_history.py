"""Run history support

Data models and persistence for run history tracking.
Stores past operations (backup, archive, restore, transfer) with status and details.
"""

from __future__ import annotations

import json
import threading
import time
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any


def _get_config_dir() -> Path:
    """Get the BKX config directory."""
    import os

    config_home = os.environ.get("XDG_CONFIG_HOME", "")
    if config_home:
        return Path(config_home) / "bkx"
    return Path.home() / ".config" / "bkx"


def _get_run_history_file() -> Path:
    """Get the run history file path."""
    return _get_config_dir() / "run_history.json"


@dataclass(slots=True)
class RunHistoryItem:
    """A single run/history entry."""

    run_id: str
    operation: str  # "backup", "archive", "restore", "transfer"
    status: str  # "success", "failed", "running", "cancelled"
    start_time: float  # Unix timestamp
    end_time: float | None  # Unix timestamp
    duration_seconds: float | None
    source_summary: str  # Summary of sources
    destination_summary: str | None
    archive_path: str | None
    files_count: int | None
    bytes_transferred: int | None
    error_message: str | None
    log_snippet: str | None  # Last N lines of logs

    @classmethod
    def start(
        cls,
        operation: str,
        source_summary: str,
        destination_summary: str | None = None,
    ) -> RunHistoryItem:
        """Start a new run entry."""
        now = time.time()
        return cls(
            run_id=_generate_run_id(),
            operation=operation,
            status="running",
            start_time=now,
            end_time=None,
            duration_seconds=None,
            source_summary=source_summary,
            destination_summary=destination_summary,
            archive_path=None,
            files_count=None,
            bytes_transferred=None,
            error_message=None,
            log_snippet=None,
        )

    def complete(
        self,
        status: str = "success",
        archive_path: str | None = None,
        files_count: int | None = None,
        bytes_transferred: int | None = None,
        log_snippet: str | None = None,
        error_message: str | None = None,
    ) -> None:
        """Mark the run as complete."""
        now = time.time()
        self.status = status
        self.end_time = now
        self.duration_seconds = now - self.start_time
        if archive_path:
            self.archive_path = archive_path
        if files_count is not None:
            self.files_count = files_count
        if bytes_transferred is not None:
            self.bytes_transferred = bytes_transferred
        if log_snippet:
            self.log_snippet = log_snippet
        if error_message:
            self.error_message = error_message

    def fail(self, error_message: str) -> None:
        """Mark the run as failed."""
        self.complete(status="failed", error_message=error_message)

    def cancel(self) -> None:
        """Mark the run as cancelled."""
        self.complete(status="cancelled")

    @property
    def start_time_str(self) -> str:
        """Get formatted start time."""
        return datetime.fromtimestamp(self.start_time).strftime("%Y-%m-%d %H:%M:%S")

    @property
    def duration_str(self) -> str:
        """Get formatted duration."""
        if self.duration_seconds is None:
            return "..."
        seconds = int(self.duration_seconds)
        if seconds < 60:
            return f"{seconds}s"
        minutes = seconds // 60
        seconds = seconds % 60
        if minutes < 60:
            return f"{minutes}m {seconds}s"
        hours = minutes // 60
        minutes = minutes % 60
        return f"{hours}h {minutes}m"

    @property
    def relative_time(self) -> str:
        """Get relative time string (e.g., "2 hours ago")."""
        now = time.time()
        diff = now - self.start_time

        if diff < 60:
            return "just now"
        if diff < 3600:
            minutes = int(diff // 60)
            return f"{minutes} minute{'s' if minutes != 1 else ''} ago"
        if diff < 86400:
            hours = int(diff // 3600)
            return f"{hours} hour{'s' if hours != 1 else ''} ago"
        if diff < 604800:
            days = int(diff // 86400)
            return f"{days} day{'s' if days != 1 else ''} ago"

        return self.start_time_str

    def to_dict(self) -> dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> RunHistoryItem:
        """Create from dictionary."""
        return cls(**data)


def _generate_run_id() -> str:
    """Generate a unique run ID."""
    return f"run-{datetime.now().strftime('%Y%m%d%H%M%S')}-{int(time.time() * 1000) % 1000:03d}"


class RunHistory:
    """Thread-safe run history manager.

    Persists run history to JSON file and provides querying.
    """

    def __init__(self, max_entries: int = 1000) -> None:
        """Initialize run history manager.

        Args:
            max_entries: Maximum number of entries to keep
        """
        self._max_entries = max_entries
        self._lock = threading.Lock()
        self._items: list[RunHistoryItem] = []
        self._load()

    def _load(self) -> None:
        """Load run history from disk."""
        history_file = _get_run_history_file()
        if not history_file.exists():
            return

        try:
            with open(history_file, encoding="utf-8") as f:
                data = json.load(f)

            self._items = [RunHistoryItem.from_dict(item) for item in data]
        except Exception:
            self._items = []

    def _save(self) -> None:
        """Save run history to disk."""
        history_file = _get_run_history_file()

        try:
            history_file.parent.mkdir(parents=True, exist_ok=True)

            # Convert to dicts
            data = [item.to_dict() for item in self._items]

            with open(history_file, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2)
        except Exception:
            pass  # Best-effort save

    def add(self, item: RunHistoryItem) -> None:
        """Add a new run entry.

        Args:
            item: The run history item to add
        """
        with self._lock:
            self._items.append(item)

            # Trim to max entries (keep newest)
            while len(self._items) > self._max_entries:
                self._items.pop(0)

        self._save()

    def get_all(self) -> list[RunHistoryItem]:
        """Get all run history entries, newest first."""
        with self._lock:
            return list(reversed(self._items))

    def get_recent(self, count: int = 20) -> list[RunHistoryItem]:
        """Get recent run history entries.

        Args:
            count: Number of entries to return

        Returns:
            List of run history items, newest first
        """
        with self._lock:
            return list(reversed(self._items[-count:]))

    def get_by_status(self, status: str) -> list[RunHistoryItem]:
        """Get run history entries by status.

        Args:
            status: Status to filter by

        Returns:
            List of matching entries, newest first
        """
        with self._lock:
            return list(reversed([i for i in self._items if i.status == status]))

    def get_by_operation(self, operation: str) -> list[RunHistoryItem]:
        """Get run history entries by operation type.

        Args:
            operation: Operation type to filter by

        Returns:
            List of matching entries, newest first
        """
        with self._lock:
            return list(reversed([i for i in self._items if i.operation == operation]))

    def get_running(self) -> list[RunHistoryItem]:
        """Get currently running operations."""
        return self.get_by_status("running")

    def get_by_id(self, run_id: str) -> RunHistoryItem | None:
        """Get a specific run by ID.

        Args:
            run_id: The run ID to find

        Returns:
            The matching entry or None
        """
        with self._lock:
            for item in reversed(self._items):
                if item.run_id == run_id:
                    return item
        return None

    def count(self) -> int:
        """Get total number of entries."""
        with self._lock:
            return len(self._items)

    def clear(self) -> None:
        """Clear all run history."""
        with self._lock:
            self._items.clear()
        self._save()

    def prune(self, older_than: float) -> int:
        """Remove entries older than a certain time.

        Args:
            older_than: Unix timestamp - remove entries older than this

        Returns:
            Number of entries removed
        """
        with self._lock:
            original_count = len(self._items)
            self._items = [i for i in self._items if i.start_time >= older_than]
            removed = original_count - len(self._items)

        if removed > 0:
            self._save()

        return removed


# Global run history instance
_run_history: RunHistory | None = None


def get_run_history(max_entries: int = 1000) -> RunHistory:
    """Get the global run history instance.

    Args:
        max_entries: Maximum entries for new instance (ignored if exists)

    Returns:
        RunHistory singleton
    """
    global _run_history
    if _run_history is None:
        _run_history = RunHistory(max_entries)
    return _run_history


def reset_run_history() -> RunHistory:
    """Reset and return a new run history instance.

    Useful for testing.

    Returns:
        New RunHistory instance
    """
    global _run_history
    _run_history = RunHistory()
    return _run_history


# Convenience functions for tracking operations
def track_backup_start(source_summary: str, destination_summary: str | None = None) -> RunHistoryItem:
    """Start tracking a backup operation.

    Returns:
        The created RunHistoryItem (call complete() or fail() when done)
    """
    item = RunHistoryItem.start("backup", source_summary, destination_summary)
    get_run_history().add(item)
    return item


def track_archive_start(source_summary: str, destination_summary: str | None = None) -> RunHistoryItem:
    """Start tracking an archive operation."""
    item = RunHistoryItem.start("archive", source_summary, destination_summary)
    get_run_history().add(item)
    return item


def track_restore_start(source_summary: str, destination_summary: str | None = None) -> RunHistoryItem:
    """Start tracking a restore operation."""
    item = RunHistoryItem.start("restore", source_summary, destination_summary)
    get_run_history().add(item)
    return item


def track_transfer_start(source_summary: str, destination_summary: str) -> RunHistoryItem:
    """Start tracking a transfer operation."""
    item = RunHistoryItem.start("transfer", source_summary, destination_summary)
    get_run_history().add(item)
    return item
