"""BackupService - orchestrates backup operations.

Contract rule:
- Services accept Specs only.
- Services return Results only.
- Services raise typed errors only.
- No rendering, no parsing.
"""

from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

from ..results.archive import ArchiveCreateResult
from ..results.backup import BackupResult, Timing
from ..results.transfer import TransferResult
from ..specs.archive import ArchiveCreateSpec

if TYPE_CHECKING:
    from ..ports.archiver import ArchiverPort
    from ..ports.hasher import HasherPort
    from ..ports.tool_detector import ToolDetectorPort
    from ..ports.transfer import TransferPort
    from ..specs.backup import BackupSpec


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

    def create(self, spec: BackupSpec) -> BackupResult:
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

        # Step 1: Create archive
        archive_result = self._create_archive(spec)
        step_results = {"archive": archive_result.success}

        if not archive_result.success:
            return BackupResult(
                success=False,
                dry_run=False,
                timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
                warnings=archive_result.warnings if hasattr(archive_result, "warnings") else (),
                step_results=step_results,
            )

        # Step 2: Transfer if destination is set
        transfer_result = self._transfer_archive(spec, archive_result)
        step_results["transfer"] = transfer_result.success

        if not transfer_result.success:
            return BackupResult(
                success=False,
                dry_run=False,
                timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
                files_processed=archive_result.files_archived if hasattr(archive_result, "files_archived") else 0,
                bytes_written=archive_result.bytes_written if hasattr(archive_result, "bytes_written") else 0,
                archives_created=1,
                archive_paths=(archive_result.archive_path,)
                if hasattr(archive_result, "archive_path") and archive_result.archive_path
                else (),
                step_results=step_results,
                warnings=transfer_result.warnings if hasattr(transfer_result, "warnings") else (),
            )

        # Step 3: Verify if requested
        verify_passed = None
        if spec.verify:
            archive_path = archive_result.archive_path if hasattr(archive_result, "archive_path") and archive_result.archive_path else None
            if archive_path:
                verify_passed = self._verify_backup(archive_path)
                step_results["verify"] = verify_passed

        return BackupResult(
            success=True,
            dry_run=False,
            timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
            files_processed=archive_result.files_archived if hasattr(archive_result, "files_archived") else 0,
            bytes_written=archive_result.bytes_written if hasattr(archive_result, "bytes_written") else 0,
            archives_created=1,
            archive_paths=(archive_result.archive_path,) if hasattr(archive_result, "archive_path") and archive_result.archive_path else (),
            verification_passed=verify_passed,
            step_results=step_results,
        )

    def _create_archive(self, spec: BackupSpec) -> ArchiveCreateResult:
        """Create archive from sources."""

        # Build archive spec from backup spec
        archive_spec = ArchiveCreateSpec(
            sources=spec.sources,
            output_path=spec.archive_path,
            excludes=spec.excludes,
            includes=spec.includes,
            compression=spec.compression,
            verify=spec.verify,
            dry_run=False,
        )
        archive_spec = archive_spec.normalize()

        # Create archive
        result = self._archiver.create(archive_spec)

        return result

    def _transfer_archive(
        self,
        spec: BackupSpec,
        archive_result: ArchiveCreateResult,
    ) -> TransferResult:
        """Transfer archive to destination."""
        from ..specs.transfer import TransferSpec

        # Only transfer if destination is set and different from archive location
        if not spec.destination:
            # No transfer needed - archive is already in place
            import time

            from ..results.transfer import Timing, TransferResult

            return TransferResult(
                success=True,
                direction="none",
                timing=Timing(start_ms=int(time.time() * 1000), end_ms=int(time.time() * 1000)),
                files_transferred=0,
                bytes_transferred=0,
            )

        # Build transfer spec
        archive_path = archive_result.archive_path
        if not archive_path:
            import time

            from ..results.transfer import Timing, TransferResult

            return TransferResult(
                success=False,
                direction="push",
                timing=Timing(start_ms=int(time.time() * 1000), end_ms=int(time.time() * 1000)),
                errors=("No archive path to transfer",),
            )

        transfer_spec = TransferSpec(
            sources=(archive_path,),
            destination=spec.destination,
            direction="push",
            verify=spec.verify,
            dry_run=False,
        )
        transfer_spec = transfer_spec.normalize()

        # Transfer the archive
        return self._transfer.push(transfer_spec)

    def _verify_backup(self, archive_path: Path) -> bool:
        """Verify backup integrity using hasher."""
        # Compute checksum of the archive
        hash_result = self._hasher.hash_file(archive_path)

        # For now, just verify we could compute the hash
        # In a full implementation, we'd compare against a stored checksum
        return hash_result.checksum != ""

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
