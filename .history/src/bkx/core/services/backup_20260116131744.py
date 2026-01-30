"""BackupService - orchestrates backup operations.

Contract rule:
- Services accept Specs only.
- Services return Results only.
- Services raise typed errors only.
- No rendering, no parsing.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from ..errors import NotImplementedError
from ..results.backup import BackupResult, BackupStepResult, Timing

if TYPE_CHECKING:
    from ..specs.backup import BackupSpec
    from ..ports.archiver import ArchiverPort
    from ..ports.transfer import TransferPort
    from ..ports.hasher import HasherPort
    from ..ports.tool_detector import ToolDetectorPort


class BackupService:
    """Orchestrates backup operations (archive -> transfer -> verify).

    This service coordinates:
    1. Archive creation via ArchiverPort
    2. Transfer to destination via TransferPort
    3. Verification via HasherPort
    """

    def __init__(
        self,
        *,
        archiver: ArchiverPort,
        transfer: TransferPort,
        hasher: HasherPort,
        tool_detector: ToolDetectorPort,
    ) -> None:
        """Initialize service with required ports.

        Args:
            archiver: Port for archive operations.
            transfer: Port for transfer operations.
            hasher: Port for checksum operations.
            tool_detector: Port for detecting optional tools.
        """
        self._archiver = archiver
        self._transfer = transfer
        self._hasher = hasher
        self._tool_detector = tool_detector

    def execute(self, spec: BackupSpec) -> BackupResult:
        """Execute a full backup operation.

        Args:
            spec: Normalized backup specification.

        Returns:
            BackupResult with metrics and artifacts.

        Raises:
            ValidationError: If spec is invalid.
            NotFoundError: If source doesn't exist.
            ExternalToolError: If required tool is unavailable.
        """
        import time

        # Validate spec is normalized
        if spec.dry_run:
            return self._execute_dry_run(spec)

        start_ms = int(time.time() * 1000)

        try:
            # Step 1: Create archive
            archive_result = self._create_archive(spec)
            step_results = {"archive": archive_result.success}

            if not archive_result.success:
                return BackupResult(
                    success=False,
                    dry_run=False,
                    timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
                    warnings=archive_result.warnings,
                    step_results=step_results,
                )

            # Step 2: Transfer if destination is remote
            transfer_result = self._transfer_archive(spec, archive_result)
            step_results["transfer"] = transfer_result.success

            if not transfer_result.success:
                return BackupResult(
                    success=False,
                    dry_run=False,
                    timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
                    archives_created=1,
                    archive_paths=archive_result.archive_path,
                    step_results=step_results,
                    warnings=transfer_result.warnings,
                )

            # Step 3: Verify if requested
            verify_passed = None
            if spec.verify:
                verify_passed = self._verify_backup(spec, archive_result)
                step_results["verify"] = verify_passed

            return BackupResult(
                success=True,
                dry_run=False,
                timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
                files_processed=archive_result.files_archived,
                bytes_written=archive_result.bytes_written,
                archives_created=1,
                archive_paths=(archive_result.archive_path,) if archive_result.archive_path else (),
                verification_passed=verify_passed,
                step_results=step_results,
            )

        except NotImplementedError:
            # Re-raise for Phase 2 implementation
            raise

    def _create_archive(self, spec: BackupSpec) -> "BackupStepResult":
        """Create archive from sources."""
        raise NotImplementedError("ArchiveService not yet implemented")

    def _transfer_archive(
        self,
        spec: BackupSpec,
        archive_result: "BackupStepResult",
    ) -> "BackupStepResult":
        """Transfer archive to destination."""
        raise NotImplementedError("TransferService not yet implemented")

    def _verify_backup(
        self,
        spec: BackupSpec,
        archive_result: "BackupStepResult",
    ) -> bool:
        """Verify backup integrity."""
        raise NotImplementedError("Verification not yet implemented")

    def _execute_dry_run(self, spec: BackupSpec) -> BackupResult:
        """Execute dry run showing what would happen."""
        import time

        start_ms = int(time.time() * 1000)

        # Generate preview of what would happen
        warnings = []
        if not spec.archive_name:
            warnings.append("Archive name would be auto-generated")

        return BackupResult(
            success=True,
            dry_run=True,
            timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
            warnings=tuple(warnings),
            step_results={},
        )

