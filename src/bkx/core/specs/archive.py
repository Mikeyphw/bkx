"""ArchiveCreateSpec and normalization.

Defines the normalized intent for archive creation.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from .backup import DEFAULT_COMPRESSION, CompressionProfile


@dataclass(frozen=True, kw_only=True)
class ArchiveCreateSpec:
    """Normalized specification for creating an archive.

    This is the deterministic input to ArchiveService.create().
    """

    # Required fields
    sources: tuple[Path, ...]  # What to archive

    # Optional fields with defaults
    output_path: Path | None = None  # Explicit output path
    excludes: tuple[str, ...] = field(default_factory=tuple)  # Glob patterns to exclude
    includes: tuple[str, ...] = field(default_factory=tuple)  # Glob patterns to include
    split_size: int | None = None  # Split archives into chunks (bytes)
    compression: CompressionProfile = DEFAULT_COMPRESSION
    verify: bool = True  # Verify after creation
    dry_run: bool = False  # Just show plan, don't execute

    # Internal derived fields
    _resolved_output: Path | None = field(default=None, compare=False)

    def normalize(
        self,
        *,
        default_tmp_dir: Path | None = None,
        auto_name: bool = True,
    ) -> ArchiveCreateSpec:
        """Normalize and validate the spec.

        Args:
            default_tmp_dir: Temp directory for intermediate files.
            auto_name: Whether to auto-generate output name.

        Returns:
            Validated and normalized ArchiveCreateSpec.

        Raises:
            ValidationError: If validation fails.
        """
        from bkx.core.errors import NotFoundError, ValidationError

        # Validate sources exist
        if not self.sources:
            raise ValidationError(msg="At least one source path is required")

        for src in self.sources:
            if not src.exists():
                raise NotFoundError(
                    msg=f"Source path does not exist: {src}",
                    details={"path": str(src)},
                )

        # Resolve output path
        output_path = self.output_path
        if auto_name and not output_path:
            output_path = self._generate_output_path()

        return ArchiveCreateSpec(
            sources=self.sources,
            output_path=output_path,
            excludes=self.excludes,
            includes=self.includes,
            split_size=self.split_size,
            compression=self.compression,
            verify=self.verify,
            dry_run=self.dry_run,
            _resolved_output=output_path,
        )

    def _generate_output_path(self) -> Path:
        """Generate output path from sources and timestamp."""
        import time
        from pathlib import Path

        timestamp = int(time.time())
        prefix = self.sources[0].name if self.sources else "archive"
        safe_prefix = "".join(c if c.isalnum() or c in "-_" else "_" for c in prefix)
        return Path(f"{safe_prefix}_{timestamp}.archive")

    @property
    def effective_output_path(self) -> Path | None:
        """Get the resolved output path."""
        return self._resolved_output


@dataclass(frozen=True, kw_only=True)
class ArchiveBrowseSpec:
    """Normalized specification for browsing an archive.

    This is the deterministic input to ArchiveService.browse().
    """

    # Required fields
    archive_path: Path  # Archive to browse

    # Optional fields with defaults
    query: str | None = None  # Search query (matches path)
    file_type: str | None = None  # Filter by type: "file", "dir"
    min_size: int | None = None  # Minimum file size (bytes)
    max_size: int | None = None  # Maximum file size (bytes)
    limit: int = 50  # Maximum results to return
    offset: int = 0  # Offset for pagination
    sort_by: str = "path"  # Sort field: "path", "size", "mtime"
    sort_order: str = "asc"  # Sort order: "asc", "desc"

    def normalize(self) -> ArchiveBrowseSpec:
        """Normalize and validate the spec.

        Returns:
            Validated and normalized ArchiveBrowseSpec.

        Raises:
            ValidationError: If validation fails.
        """
        from bkx.core.errors import NotFoundError, ValidationError

        # Validate archive exists
        if not self.archive_path.exists():
            raise NotFoundError(
                msg=f"Archive not found: {self.archive_path}",
                details={"path": str(self.archive_path)},
            )

        # Validate limit
        if self.limit < 1 or self.limit > 1000:
            raise ValidationError(
                msg="Limit must be between 1 and 1000",
                details={"limit": self.limit},
            )

        # Validate sort options
        valid_sort_by = ("path", "size", "mtime")
        if self.sort_by not in valid_sort_by:
            raise ValidationError(
                msg=f"sort_by must be one of {valid_sort_by}",
                details={"sort_by": self.sort_by},
            )

        valid_sort_order = ("asc", "desc")
        if self.sort_order not in valid_sort_order:
            raise ValidationError(
                msg=f"sort_order must be one of {valid_sort_order}",
                details={"sort_order": self.sort_order},
            )

        # Validate file_type
        if self.file_type is not None and self.file_type not in ("file", "dir"):
            raise ValidationError(
                msg="file_type must be 'file' or 'dir'",
                details={"file_type": self.file_type},
            )

        return ArchiveBrowseSpec(
            archive_path=self.archive_path,
            query=self.query,
            file_type=self.file_type,
            min_size=self.min_size,
            max_size=self.max_size,
            limit=self.limit,
            offset=self.offset,
            sort_by=self.sort_by,
            sort_order=self.sort_order,
        )
