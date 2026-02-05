"""RestoreResult - structured result for restore operations.

Returns structured data, never strings.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import NamedTuple

from .backup import Timing


@dataclass(frozen=True, kw_only=True)
class RestoreResult:
    """Structured result from a restore operation.

    Contains all metrics and artifacts produced by the operation.
    """

    # Overall status
    success: bool
    dry_run: bool

    # Timing
    timing: Timing

    # Metrics
    files_restored: int = 0
    bytes_restored: int = 0

    # Paths
    archive_path: Path | None = None
    restored_paths: tuple[Path, ...] = field(default_factory=tuple)

    # Verification
    verification_passed: bool | None = None

    # Warnings
    warnings: tuple[str, ...] = field(default_factory=tuple)

    # Errors (partial failures)
    errors: tuple[str, ...] = field(default_factory=tuple)

    @property
    def duration_ms(self) -> int:
        """Get total duration in milliseconds."""
        return self.timing.duration_ms

    @property
    def human_bytes_restored(self) -> str:
        """Get human-readable byte count."""
        return _human_bytes(self.bytes_restored)


@dataclass(frozen=True, kw_only=True)
class RestorePreviewResult:
    """Result from previewing a restore operation.

    Shows what would happen without executing.
    """

    success: bool
    archive_path: Path

    # What would be restored
    total_files: int = 0
    total_size: int = 0
    files: tuple["RestoreFileInfo", ...] = field(default_factory=tuple)

    # Would overwrite
    existing_files: int = 0

    # Warnings about restore
    warnings: tuple[str, ...] = field(default_factory=tuple)


@dataclass(frozen=True, kw_only=True)
class RestoreFileInfo:
    """Information about a file to be restored."""

    archive_path: str  # Path within archive
    disk_path: Path  # Destination on disk
    size: int
    mtime_ns: int
    is_partial: bool = False  # Would restore only part of file


def _human_bytes(n: int) -> str:
    """Format bytes as human-readable string."""
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024:
            return f"{n:.2f}{unit}"
        n /= 1024
    return f"{n:.2f}PB"
