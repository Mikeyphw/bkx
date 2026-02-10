"""RestoreService - handles restore operations.

Contract rule:
- Services accept Specs only.
- Services return Results only.
- Services raise typed errors only.
- No rendering, no parsing.
"""

from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

from ..results.restore import RestorePreviewResult, RestoreResult, Timing

if TYPE_CHECKING:
    from ..ports.archiver import ArchiverPort
    from ..specs.restore import RestoreSpec


class RestoreService:
    """Handles restore operations from archives.

    Operations:
    - extract: Restore full archive or subset
    - preview: Show what would be restored
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

    def extract(self, spec: RestoreSpec) -> RestoreResult:
        """Extract files from archive.

        Args:
            spec: Normalized restore specification.

        Returns:
            RestoreResult with metrics and restored paths.

        Raises:
            ValidationError: If spec is invalid.
            NotFoundError: If archive doesn't exist.
        """
        import time

        start_ms = int(time.time() * 1000)

        if spec.dry_run:
            return self._dry_run_extract(spec)

        # Verify archive integrity if requested
        if spec.verify:
            verify_result = self._archiver.verify(spec.archive)
            if not verify_result.success:
                from ..errors import ExternalToolError

                raise ExternalToolError(
                    msg=f"Archive verification failed: {spec.archive}",
                    details={"failures": list(verify_result.failed_entries)},
                )

        # Extract files
        restored_paths = self._archiver.extract(spec)

        return RestoreResult(
            success=True,
            dry_run=False,
            timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
            archive_path=spec.archive,
            restored_paths=restored_paths,
            files_restored=len(restored_paths),
        )

    def preview(self, spec: RestoreSpec) -> RestorePreviewResult:
        """Preview what would be restored.

        Args:
            spec: Normalized restore specification.

        Returns:
            RestorePreviewResult showing what would happen.
        """
        # List archive contents
        entries = self._archiver.list(spec.archive)

        # Filter to requested paths if partial restore
        if spec.paths:
            entries = tuple(e for e in entries if any(p in e.path for p in spec.paths))

        total_size = sum(e.size for e in entries)

        return RestorePreviewResult(
            success=True,
            archive_path=spec.archive,
            total_files=len(entries),
            total_size=total_size,
            files=tuple(
                RestoreFileInfo(
                    archive_path=e.path,
                    disk_path=(spec.destination or Path.cwd()) / e.path,
                    size=e.size,
                    mtime_ns=e.mtime_ns,
                )
                for e in entries
            ),
        )

    def pick(
        self,
        spec: RestoreSpec,
    ) -> RestoreResult:
        """Extract specific paths from archive (partial restore).

        This is an alias for extract() when paths are specified,
        making the partial restore workflow clearer.

        Args:
            spec: Normalized restore specification with paths set.

        Returns:
            RestoreResult with metrics and restored paths.

        Raises:
            ValidationError: If spec is invalid or no paths specified.
            NotFoundError: If archive doesn't exist.
        """
        from ..errors import ValidationError

        # Validate that paths are specified for pick operation
        if not spec.paths:
            raise ValidationError(
                msg="pick() requires specific paths to restore",
                details={"paths_provided": False},
            )

        # Use extract which already handles partial restore
        return self.extract(spec)

    def _dry_run_extract(self, spec: RestoreSpec) -> RestoreResult:
        """Execute dry run showing what would happen."""
        import time

        start_ms = int(time.time() * 1000)

        return RestoreResult(
            success=True,
            dry_run=True,
            timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
            archive_path=spec.archive,
            warnings=("Dry run - files not extracted",),
        )


# Import RestoreFileInfo at module level for type checking
if TYPE_CHECKING:
    from ..results.restore import RestoreFileInfo
