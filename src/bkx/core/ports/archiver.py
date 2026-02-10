"""ArchiverPort - interface for archive creation/extraction.

Contract rule:
- Services accept Specs, use ports for IO.
- Ports are pure interfaces with fallback implementations.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from ..results.archive import (
    ArchiveCreateResult,
    ArchiveEntry,
    ArchiveInfoResult,
    ArchiveVerifyResult,
)
from ..specs.archive import ArchiveCreateSpec
from ..specs.restore import RestoreSpec


class ArchiverPort(Protocol):
    """Protocol for archive operations.

    Implement this to support different archive formats.
    Each implementation must have a fallback in core/impl/.
    """

    @property
    def name(self) -> str:
        """Human-readable name of this archiver."""
        ...

    @property
    def supported_formats(self) -> tuple[str, ...]:
        """List of supported format identifiers."""
        ...

    def create(
        self,
        spec: ArchiveCreateSpec,
        *,
        total_size: int | None = None,
    ) -> ArchiveCreateResult:
        """Create an archive from sources.

        Args:
            spec: Normalized archive creation spec.
            total_size: Optional total size for progress reporting.

        Returns:
            Result with metrics and archive path.
        """
        ...

    def list(
        self,
        archive_path: Path,
        *,
        filter_pattern: str | None = None,
        page: int = 0,
        page_size: int = 100,
    ) -> tuple[ArchiveEntry, ...]:
        """List contents of an archive.

        Args:
            archive_path: Path to archive.
            filter_pattern: Optional glob pattern to filter.
            page: Page number for pagination.
            page_size: Entries per page.

        Returns:
            List of archive entries.
        """
        ...

    def info(self, archive_path: Path) -> ArchiveInfoResult:
        """Get metadata about an archive.

        Args:
            archive_path: Path to archive.

        Returns:
            Archive metadata.
        """
        ...

    def verify(self, archive_path: Path) -> ArchiveVerifyResult:
        """Verify archive integrity.

        Args:
            archive_path: Path to archive.

        Returns:
            Verification result.
        """
        ...

    def extract(
        self,
        spec: RestoreSpec,
    ) -> tuple[Path, ...]:
        """Extract files from archive.

        Args:
            spec: Normalized restore spec.

        Returns:
            Paths of extracted files.
        """
        ...


@dataclass(frozen=True)
class ArchiveFormat:
    """Metadata about an archive format."""

    identifier: str
    extension: str
    mime_type: str
    supports_compression: bool = True
    supports_splitting: bool = False


# Predefined format registry
TAR_ZSTD = ArchiveFormat(
    identifier="tar+zstd",
    extension=".tar.zst",
    mime_type="application/x-tar",
    supports_compression=True,
    supports_splitting=True,
)

TAR_GZIP = ArchiveFormat(
    identifier="tar+gzip",
    extension=".tar.gz",
    mime_type="application/x-tar",
    supports_compression=True,
    supports_splitting=True,
)

TAR = ArchiveFormat(
    identifier="tar",
    extension=".tar",
    mime_type="application/x-tar",
    supports_compression=False,
    supports_splitting=True,
)

ZIP = ArchiveFormat(
    identifier="zip",
    extension=".zip",
    mime_type="application/zip",
    supports_compression=True,
    supports_splitting=False,
)
