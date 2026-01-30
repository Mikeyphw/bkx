"""ArchiveCreateSpec and normalization.

Defines the normalized intent for archive creation.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import NamedTuple

from .backup import CompressionProfile, DEFAULT_COMPRESSION


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

