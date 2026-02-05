"""RestoreSpec and normalization.

Defines the normalized intent for restore operations.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
import time


@dataclass(frozen=True, kw_only=True)
class RestoreSpec:
    """Normalized specification for restoring from archive.

    This is the deterministic input to RestoreService.
    """

    # Required fields
    archive: Path  # Archive to restore from

    # Optional fields
    destination: Path | None = None  # Where to restore (default: fresh dir if fresh_dir=True)
    paths: tuple[str, ...] = field(default_factory=tuple)  # Specific paths to restore
    overwrite: bool = False  # Overwrite existing files
    verify: bool = True  # Verify archive integrity before restore
    dry_run: bool = False  # Just show plan, don't execute

    # Safety options
    fresh_dir: bool = True  # Create fresh dir by default (safer default)
    confirm_overwrite: bool = True  # Require confirmation for overwrite

    # Internal derived fields
    _resolved_destination: Path | None = field(default=None, compare=False)

    def normalize(
        self,
        *,
        default_destination: Path | None = None,
    ) -> RestoreSpec:
        """Normalize and validate the spec.

        Args:
            default_destination: Fallback destination if not specified.

        Returns:
            Validated and normalized RestoreSpec.

        Raises:
            ValidationError: If validation fails.
            NotFoundError: If archive doesn't exist.
        """
        from bkx.core.errors import NotFoundError, ValidationError

        # Validate archive exists
        if not self.archive.exists():
            raise NotFoundError(
                msg=f"Archive not found: {self.archive}",
                details={"archive": str(self.archive)},
            )

        # Validate destination if specified
        if self.destination:
            # Don't validate yet - might be creating parent dirs
            pass

        # Resolve destination with fresh_dir safety feature
        if self.fresh_dir and not self.destination and not default_destination:
            # Create a fresh directory based on archive name and timestamp
            archive_name = self.archive.stem
            timestamp = int(time.time())
            fresh_path = Path(f"restored_{archive_name}_{timestamp}")
            destination = fresh_path
        else:
            destination = self.destination or default_destination

        # Validate overwrite safety
        if self.overwrite and self.confirm_overwrite:
            raise ValidationError(
                msg="Cannot use --overwrite with --confirm (use --yes to skip confirmation)",
                details={
                    "overwrite": self.overwrite,
                    "confirm_overwrite": self.confirm_overwrite,
                },
            )

        return RestoreSpec(
            archive=self.archive,
            destination=destination,
            paths=self.paths,
            overwrite=self.overwrite,
            verify=self.verify,
            dry_run=self.dry_run,
            fresh_dir=self.fresh_dir,
            confirm_overwrite=self.confirm_overwrite,
            _resolved_destination=destination,
        )

    @property
    def effective_destination(self) -> Path | None:
        """Get the resolved destination path."""
        return self._resolved_destination

    @property
    def is_partial_restore(self) -> bool:
        """Check if this is a partial restore (specific paths)."""
        return len(self.paths) > 0

    @property
    def is_full_restore(self) -> bool:
        """Check if this is a full restore."""
        return len(self.paths) == 0

    @property
    def requires_confirmation(self) -> bool:
        """Check if restore requires user confirmation."""
        # If destination exists and would be overwritten, needs confirmation
        if self.effective_destination and self.effective_destination.exists():
            return True
        return False
