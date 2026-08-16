"""Truthful immutable archive Result contracts."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from .backup import Timing
from .common import require_nonnegative


@dataclass(frozen=True, kw_only=True)
class ArchiveCreateResult:
    success: bool
    dry_run: bool
    timing: Timing = field(compare=False)
    files_archived: int = 0
    bytes_written: int = 0
    original_size: int = 0
    archive_path: Path | None = None
    split_parts: tuple[Path, ...] = field(default_factory=tuple)
    verification_passed: bool | None = None
    compression_ratio: float | None = None
    warnings: tuple[str, ...] = field(default_factory=tuple)
    errors: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        require_nonnegative(files_archived=self.files_archived, bytes_written=self.bytes_written, original_size=self.original_size)
        if self.compression_ratio is not None:
            require_nonnegative(compression_ratio=self.compression_ratio)
        object.__setattr__(self, "split_parts", tuple(self.split_parts))
        object.__setattr__(self, "warnings", tuple(self.warnings))
        object.__setattr__(self, "errors", tuple(self.errors))
        if self.success and self.errors:
            raise ValueError("successful ArchiveCreateResult cannot contain errors")
        if not self.success and self.verification_passed is True:
            raise ValueError("failed archive creation cannot claim successful verification")

    @property
    def duration_ms(self) -> int: return self.timing.duration_ms
    @property
    def human_bytes_written(self) -> str: return _human_bytes(self.bytes_written)
    @property
    def output_path(self) -> Path | None: return self.archive_path
    @property
    def bytes_total(self) -> int: return self.bytes_written
    @property
    def files_count(self) -> int: return self.files_archived


@dataclass(frozen=True, kw_only=True)
class ArchiveEntry:
    path: str
    is_dir: bool
    size: int
    mtime_ns: int

    def __post_init__(self) -> None:
        require_nonnegative(size=self.size, mtime_ns=self.mtime_ns)
        if not self.path:
            raise ValueError("archive entry path must not be empty")

    @property
    def human_size(self) -> str: return _human_bytes(self.size)


@dataclass(frozen=True, kw_only=True)
class ArchiveListResult:
    success: bool
    archive_path: Path
    entries: tuple[ArchiveEntry, ...] = field(default_factory=tuple)
    total_entries: int = 0
    page: int = 0
    page_size: int = 100
    filter_pattern: str | None = None
    warnings: tuple[str, ...] = field(default_factory=tuple)
    errors: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        require_nonnegative(total_entries=self.total_entries, page=self.page)
        if self.page_size <= 0:
            raise ValueError("page_size must be positive")
        object.__setattr__(self, "entries", tuple(self.entries))
        object.__setattr__(self, "warnings", tuple(self.warnings))
        object.__setattr__(self, "errors", tuple(self.errors))
        if self.total_entries < len(self.entries):
            raise ValueError("total_entries cannot be smaller than returned entries")
        if self.success and self.errors:
            raise ValueError("successful ArchiveListResult cannot contain errors")

    @property
    def total_size(self) -> int: return sum(e.size for e in self.entries)


@dataclass(frozen=True, kw_only=True)
class ArchiveInfoResult:
    success: bool
    archive_path: Path
    format: str
    created_at: int
    total_entries: int
    total_size: int
    uncompressed_size: int
    compression_ratio: float
    warnings: tuple[str, ...] = field(default_factory=tuple)
    errors: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        require_nonnegative(created_at=self.created_at, total_entries=self.total_entries, total_size=self.total_size, uncompressed_size=self.uncompressed_size, compression_ratio=self.compression_ratio)
        object.__setattr__(self, "warnings", tuple(self.warnings))
        object.__setattr__(self, "errors", tuple(self.errors))
        if self.success and self.errors:
            raise ValueError("successful ArchiveInfoResult cannot contain errors")

    @property
    def path(self) -> Path: return self.archive_path
    @property
    def files_count(self) -> int: return self.total_entries
    @property
    def bytes_total(self) -> int: return self.total_size
    @property
    def created(self) -> int: return self.created_at
    @property
    def compression_algorithm(self) -> str: return self.format


@dataclass(frozen=True, kw_only=True)
class ArchiveVerifyResult:
    success: bool
    archive_path: Path
    checksums_valid: bool | None = None
    structure_valid: bool | None = None
    entries_checked: int = 0
    entries_failed: int = 0
    failed_entries: tuple[str, ...] = field(default_factory=tuple)
    warnings: tuple[str, ...] = field(default_factory=tuple)
    errors: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        require_nonnegative(entries_checked=self.entries_checked, entries_failed=self.entries_failed)
        object.__setattr__(self, "failed_entries", tuple(self.failed_entries))
        object.__setattr__(self, "warnings", tuple(self.warnings))
        object.__setattr__(self, "errors", tuple(self.errors))
        if self.entries_failed > self.entries_checked:
            raise ValueError("entries_failed cannot exceed entries_checked")
        if self.entries_failed != len(self.failed_entries):
            raise ValueError("entries_failed must match failed_entries")
        if self.success and (self.checksums_valid is False or self.structure_valid is False or self.errors):
            raise ValueError("successful verification cannot contain failed checks/errors")
        if not self.success and self.checksums_valid is True and self.structure_valid is True and not self.errors and self.entries_failed == 0:
            raise ValueError("failed verification must describe a failed check or error")


@dataclass(frozen=True, kw_only=True)
class ArchiveBrowseResult:
    success: bool
    archive_path: Path
    query: str | None = None
    file_type: str | None = None
    min_size: int | None = None
    max_size: int | None = None
    entries: tuple[ArchiveEntry, ...] = field(default_factory=tuple)
    total_matches: int = 0
    limit: int = 50
    offset: int = 0
    sort_by: str = "path"
    sort_order: str = "asc"
    total_size: int = 0
    directories_count: int = 0
    files_count: int = 0

    def __post_init__(self) -> None:
        require_nonnegative(total_matches=self.total_matches, offset=self.offset, total_size=self.total_size, directories_count=self.directories_count, files_count=self.files_count)
        if self.limit <= 0:
            raise ValueError("limit must be positive")
        object.__setattr__(self, "entries", tuple(self.entries))
        if self.total_matches < len(self.entries):
            raise ValueError("total_matches cannot be smaller than returned entries")
        if self.directories_count + self.files_count != len(self.entries):
            raise ValueError("browse file/directory counts must match returned entries")


def _human_bytes(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024: return f"{n:.2f}{unit}"
        n /= 1024
    return f"{n:.2f}PB"
