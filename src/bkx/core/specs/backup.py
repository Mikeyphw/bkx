"""BackupSpec and normalization.

Defines the normalized intent for backup operations.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import NamedTuple


class CompressionProfile(NamedTuple):
    """Compression configuration for archives."""

    algorithm: str  # e.g., "zstd", "gzip", "none"
    level: int = 3  # compression level 1-22 for zstd, 1-9 for gzip


# Default profiles
DEFAULT_COMPRESSION = CompressionProfile(algorithm="zstd", level=3)


@dataclass(frozen=True, kw_only=True)
class BackupSpec:
    """Normalized specification for a backup operation.

    This is the deterministic input to BackupService.
    All validation and normalization happens in normalize().
    """

    # Required fields
    sources: tuple[Path, ...]  # What to back up

    # Optional fields with defaults
    destination: Path | None = None  # Where to store (computed if not set)
    archive_name: str | None = None  # Explicit archive name
    excludes: tuple[str, ...] = field(default_factory=tuple)  # Glob patterns to exclude
    includes: tuple[str, ...] = field(default_factory=tuple)  # Glob patterns to include
    split_size: int | None = None  # Split archives into chunks (bytes)
    compression: CompressionProfile = DEFAULT_COMPRESSION
    verify: bool = True  # Verify after creation
    dry_run: bool = False  # Just show plan, don't execute

    # Internal derived fields (set by normalize)
    _resolved_destination: Path | None = field(default=None, compare=False)
    _archive_path: Path | None = field(default=None, compare=False)

    def normalize(
        self,
        *,
        default_destination: Path | None = None,
        auto_name: bool = True,
    ) -> BackupSpec:
        """Normalize and validate the spec.

        Args:
            default_destination: Fallback destination if not specified.
            auto_name: Whether to auto-generate archive name.

        Returns:
            Validated and normalized BackupSpec.

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
            if not src.is_dir() and not src.is_file():
                raise ValidationError(
                    msg=f"Source path is not valid: {src}",
                    details={"path": str(src)},
                )

        # Validate excludes/includes don't overlap
        # (simplified check - in production would parse glob patterns)

        # Resolve destination
        destination = self.destination or default_destination

        # Auto-generate archive name if needed
        archive_name = self.archive_name
        if auto_name and not archive_name and destination:
            archive_name = self._generate_archive_name()

        # Create normalized copy with derived fields
        normalized = BackupSpec(
            sources=self.sources,
            destination=destination,
            archive_name=archive_name,
            excludes=self.excludes,
            includes=self.includes,
            split_size=self.split_size,
            compression=self.compression,
            verify=self.verify,
            dry_run=self.dry_run,
            _resolved_destination=destination,
            _archive_path=destination / f"{archive_name}.bakery" if archive_name and destination else None,
        )

        return normalized

    def _generate_archive_name(self) -> str:
        """Generate archive name from sources and timestamp."""
        import time

        timestamp = int(time.time())
        # Use first source basename as prefix
        prefix = self.sources[0].name if self.sources else "backup"
        safe_prefix = "".join(c if c.isalnum() or c in "-_" else "_" for c in prefix)
        return f"{safe_prefix}_{timestamp}"

    @property
    def effective_destination(self) -> Path | None:
        """Get the resolved destination path."""
        return self._resolved_destination

    @property
    def archive_path(self) -> Path | None:
        """Get the full archive path if available."""
        return self._archive_path
