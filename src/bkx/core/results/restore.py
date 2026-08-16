"""Truthful immutable restore Result contracts."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from .backup import Timing
from .common import require_nonnegative


@dataclass(frozen=True, kw_only=True)
class RestoreResult:
    success: bool
    dry_run: bool
    timing: Timing = field(compare=False)
    files_restored: int = 0
    bytes_restored: int = 0
    archive_path: Path | None = None
    destination_path: Path | None = None
    restored_paths: tuple[Path, ...] = field(default_factory=tuple)
    verification_passed: bool | None = None
    warnings: tuple[str, ...] = field(default_factory=tuple)
    errors: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        require_nonnegative(files_restored=self.files_restored, bytes_restored=self.bytes_restored)
        object.__setattr__(self, "restored_paths", tuple(self.restored_paths))
        object.__setattr__(self, "warnings", tuple(self.warnings))
        object.__setattr__(self, "errors", tuple(self.errors))
        if self.success and self.errors:
            raise ValueError("successful RestoreResult cannot contain errors")
        if not self.success and self.verification_passed is True:
            raise ValueError("failed restore cannot claim successful verification")

    @property
    def duration_ms(self) -> int: return self.timing.duration_ms
    @property
    def human_bytes_restored(self) -> str: return _human_bytes(self.bytes_restored)
    @property
    def destination(self) -> Path | None: return self.destination_path
    @property
    def files_count(self) -> int: return self.files_restored


@dataclass(frozen=True, kw_only=True)
class RestorePreviewResult:
    success: bool
    archive_path: Path
    total_files: int = 0
    total_size: int = 0
    files: tuple[RestoreFileInfo, ...] = field(default_factory=tuple)
    existing_files: int = 0
    warnings: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        require_nonnegative(total_files=self.total_files, total_size=self.total_size, existing_files=self.existing_files)
        object.__setattr__(self, "files", tuple(self.files))
        object.__setattr__(self, "warnings", tuple(self.warnings))
        if self.total_files != len(self.files):
            raise ValueError("total_files must match preview files")


@dataclass(frozen=True, kw_only=True)
class RestoreFileInfo:
    archive_path: str
    disk_path: Path
    size: int
    mtime_ns: int
    is_partial: bool = False

    def __post_init__(self) -> None:
        if not self.archive_path:
            raise ValueError("archive_path must not be empty")
        require_nonnegative(size=self.size, mtime_ns=self.mtime_ns)


def _human_bytes(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024: return f"{n:.2f}{unit}"
        n /= 1024
    return f"{n:.2f}PB"
