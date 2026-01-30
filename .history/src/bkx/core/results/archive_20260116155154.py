"""ArchiveResult - structured result for archive operations.

Returns structured data, never strings.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import NamedTuple

from .backup import Timing


@dataclass(frozen=True, kw_only=True)
class ArchiveCreateResult:
    """Structured result from archive creation.

    Contains all metrics and artifacts produced by the operation.
    """

    # Overall status
    success: bool
    dry_run: bool

    # Timing
    timing: Timing

    # Metrics
    files_archived: int = 0
    bytes_written: int = 0
    original_size: int = 0  # Uncompressed size

    # Paths produced
    archive_path: Path | None = None
    split_parts: tuple[Path, ...] = field(default_factory=tuple)

    # Verification
    verification_passed: bool | None = None

    # Compression stats
    compression_ratio: float | None = None  # compressed_size / original_size

    # Warnings
    warnings: tuple[str, ...] = field(default_factory=tuple)

    @property
    def duration_ms(self) -> int:
        """Get total duration in milliseconds."""
        return self.timing.duration_ms

    @property
    def human_bytes_written(self) -> str:
        """Get human-readable byte count."""
        return _human_bytes(self.bytes_written)

    @property
    def output_path(self) -> Path | None:
        """Alias for archive_path (CLI compatibility)."""
        return self.archive_path

    @property
    def bytes_total(self) -> int:
        """Alias for bytes_written (CLI compatibility)."""
        return self.bytes_written

    @property
    def files_count(self) -> int:
        """Alias for files_archived (CLI compatibility)."""
        return self.files_archived


@dataclass(frozen=True, kw_only=True)
class ArchiveListResult:
    """Result from listing archive contents."""

    success: bool
    archive_path: Path

    # Listing
    entries: tuple["ArchiveEntry", ...] = field(default_factory=tuple)

    # Pagination
    total_entries: int = 0
    page: int = 0
    page_size: int = 100

    # Filtering
    filter_pattern: str | None = None

    @property
    def total_size(self) -> int:
        """Calculate total size of all entries."""
        return sum(e.size for e in self.entries)


@dataclass(frozen=True, kw_only=True)
class ArchiveEntry:
    """Single entry in an archive."""

    path: str  # Relative path within archive
    is_dir: bool
    size: int
    mtime_ns: int  # Modification time in nanoseconds

    @property
    def human_size(self) -> str:
        """Get human-readable size."""
        return _human_bytes(self.size)


@dataclass(frozen=True, kw_only=True)
class ArchiveInfoResult:
    """Result from getting archive metadata."""

    success: bool
    archive_path: Path

    # Archive metadata
    format: str  # e.g., "tar+zstd"
    created_at: int  # Epoch seconds

    # Content stats
    total_entries: int
    total_size: int
    uncompressed_size: int

    # Compression
    compression_ratio: float

    # Warnings
    warnings: tuple[str, ...] = field(default_factory=tuple)


@dataclass(frozen=True, kw_only=True)
class ArchiveVerifyResult:
    """Result from verifying archive integrity."""

    success: bool
    archive_path: Path

    # Verification results
    checksums_valid: bool = True
    structure_valid: bool = True

    # Details
    entries_checked: int = 0
    entries_failed: int = 0
    failed_entries: tuple[str, ...] = field(default_factory=tuple)

    # Warnings
    warnings: tuple[str, ...] = field(default_factory=tuple)


def _human_bytes(n: int) -> str:
    """Format bytes as human-readable string."""
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024:
            return f"{n:.2f}{unit}"
        n /= 1024
    return f"{n:.2f}PB"

