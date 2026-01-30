"""ArchiveService - handles archive operations.

Contract rule:
- Services accept Specs only.
- Services return Results only.
- Services raise typed errors only.
- No rendering, no parsing.
"""

from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

from ..results.archive import (
    ArchiveCreateResult,
    ArchiveListResult,
    ArchiveEntry,
    ArchiveInfoResult,
    ArchiveVerifyResult,
    Timing,
)

if TYPE_CHECKING:
    from ..specs.archive import ArchiveCreateSpec
    from ..ports.archiver import ArchiverPort


class ArchiveService:
    """Handles archive creation, listing, info, and verification.

    Operations:
    - create: Create new archive
    - list: List archive contents
    - info: Show archive metadata
    - verify: Check archive integrity
    """

    def __init__(
        self,
        *,
        archiver: ArchiverPort,
    ) -> None:
        """Initialize service with required ports.

        Args:
            archiver: Port for archive operations.
        """
        self._archiver = archiver

    def create(self, spec: ArchiveCreateSpec) -> ArchiveCreateResult:
        """Create a new archive.

        Args:
            spec: Normalized archive creation specification.

        Returns:
            ArchiveCreateResult with metrics and output path.

        Raises:
            ValidationError: If spec is invalid.
            NotFoundError: If source doesn't exist.
        """
        import time

        start_ms = int(time.time() * 1000)

        if spec.dry_run:
            return ArchiveCreateResult(
                success=True,
                dry_run=True,
                timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
                warnings=("Dry run - archive not created",),
            )

        result = self._archiver.create(spec)
        return result

    def list(
        self,
        archive_path: Path,
        *,
        filter_pattern: str | None = None,
        page: int = 0,
        page_size: int = 100,
    ) -> ArchiveListResult:
        """List contents of an archive.

        Args:
            archive_path: Path to archive.
            filter_pattern: Optional glob pattern to filter.
            page: Page number (0-indexed).
            page_size: Entries per page.

        Returns:
            ArchiveListResult with entries and pagination info.
        """
        if not archive_path.exists():
            from ..errors import NotFoundError

            raise NotFoundError(
                msg=f"Archive not found: {archive_path}",
                details={"path": str(archive_path)},
            )

        entries = self._archiver.list(
            archive_path,
            filter_pattern=filter_pattern,
            page=page,
            page_size=page_size,
        )

        return ArchiveListResult(
            success=True,
            archive_path=archive_path,
            entries=entries,
            total_entries=len(entries),
            filter_pattern=filter_pattern,
        )

    def info(self, archive_path: Path) -> ArchiveInfoResult:
        """Get metadata about an archive.

        Args:
            archive_path: Path to archive.

        Returns:
            ArchiveInfoResult with metadata.
        """
        if not archive_path.exists():
            from ..errors import NotFoundError

            raise NotFoundError(
                msg=f"Archive not found: {archive_path}",
                details={"path": str(archive_path)},
            )

        return self._archiver.info(archive_path)

    def verify(self, archive_path: Path) -> ArchiveVerifyResult:
        """Verify archive integrity.

        Args:
            archive_path: Path to archive.

        Returns:
            ArchiveVerifyResult with verification status.
        """
        if not archive_path.exists():
            from ..errors import NotFoundError

            raise NotFoundError(
                msg=f"Archive not found: {archive_path}",
                details={"path": str(archive_path)},
            )

        return self._archiver.verify(archive_path)

