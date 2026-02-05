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
    ArchiveBrowseResult,
    Timing,
)

if TYPE_CHECKING:
    from ..specs.archive import ArchiveCreateSpec, ArchiveBrowseSpec
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

    def browse(self, spec: ArchiveBrowseSpec) -> ArchiveBrowseResult:
        """Browse archive with filters and search.

        Args:
            spec: Normalized browse specification with filters.

        Returns:
            ArchiveBrowseResult with matching entries and statistics.
        """
        # Normalize spec
        spec = spec.normalize()

        # Get all entries from archive
        all_entries = self._archiver.list(spec.archive_path)

        # Apply filters
        filtered = list(all_entries)

        # Filter by query (path contains)
        if spec.query:
            query_lower = spec.query.lower()
            filtered = [e for e in filtered if spec.query in e.path.lower()]

        # Filter by file type
        if spec.file_type:
            if spec.file_type == "file":
                filtered = [e for e in filtered if not e.is_dir]
            elif spec.file_type == "dir":
                filtered = [e for e in filtered if e.is_dir]

        # Filter by size
        if spec.min_size is not None:
            filtered = [e for e in filtered if e.size >= spec.min_size]
        if spec.max_size is not None:
            filtered = [e for e in filtered if e.size <= spec.max_size]

        total_matches = len(filtered)

        # Sort entries
        sort_key = spec.sort_by
        reverse = spec.sort_order == "desc"

        if sort_key == "path":
            filtered.sort(key=lambda e: e.path, reverse=reverse)
        elif sort_key == "size":
            filtered.sort(key=lambda e: e.size, reverse=reverse)
        elif sort_key == "mtime":
            filtered.sort(key=lambda e: e.mtime_ns, reverse=reverse)

        # Apply pagination
        paginated = filtered[spec.offset : spec.offset + spec.limit]

        # Calculate statistics
        total_size = sum(e.size for e in paginated)
        directories_count = sum(1 for e in paginated if e.is_dir)
        files_count = sum(1 for e in paginated if not e.is_dir)

        return ArchiveBrowseResult(
            success=True,
            archive_path=spec.archive_path,
            query=spec.query,
            file_type=spec.file_type,
            min_size=spec.min_size,
            max_size=spec.max_size,
            entries=tuple(paginated),
            total_matches=total_matches,
            limit=spec.limit,
            offset=spec.offset,
            sort_by=spec.sort_by,
            sort_order=spec.sort_order,
            total_size=total_size,
            directories_count=directories_count,
            files_count=files_count,
        )
