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

from ..errors import expected_error_boundary
from ..results.archive import ArchiveCreateResult
from ..results.backup import BackupResult, Timing
from ..results.transfer import TransferResult
from ..specs.archive import ArchiveCreateSpec
from .run_tracking import snapshot_spec, track_run

if TYPE_CHECKING:
    from ..ports.archiver import ArchiverPort
    from ..ports.hasher import HasherPort
    from ..ports.tool_detector import ToolDetectorPort
    from ..ports.transfer import TransferPort
    from ..ports.run_store import RunStorePort
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
        run_store: RunStorePort | None = None,
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
        self._run_store = run_store

    def create(self, spec: BackupSpec) -> BackupResult:
        """Execute a full backup operation and record run history when configured."""
        return track_run(
            self._run_store,
            operation="backup",
            source_paths=tuple(str(source) for source in spec.sources),
            destination_path=str(spec.destination) if spec.destination else None,
            archive_path=str(spec.archive_path) if spec.archive_path else None,
            spec_snapshot=snapshot_spec(spec),
            execute=lambda: self._create_untracked(spec),
            files_count=lambda result: result.files_processed,
            bytes_processed=lambda result: result.bytes_written,
        )

    def _create_untracked(self, spec: BackupSpec) -> BackupResult:
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
                errors=archive_result.errors if hasattr(archive_result, "errors") and archive_result.errors else ("Archive creation failed",),
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
                errors=transfer_result.errors if transfer_result.errors else ("Archive transfer failed",),
            )

        # Step 3: Verify if requested
        verify_passed = None
        if spec.verify:
            archive_path = archive_result.archive_path if hasattr(archive_result, "archive_path") and archive_result.archive_path else None
            if archive_path:
                verify_passed, checksum = self._verify_backup(archive_path)
                step_results["verify"] = verify_passed
                checksums = {"archive": checksum}
            else:
                checksums = {}
        else:
            checksums = {}

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
            checksums=checksums,
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
        with expected_error_boundary("creating backup archive", resource=str(archive_spec.output_path) if archive_spec.output_path else None, backend=self._archiver.name):
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
        with expected_error_boundary("transferring backup archive", resource=str(archive_path), backend=self._transfer.name):
            return self._transfer.push(transfer_spec)

    def _verify_backup(self, archive_path: Path) -> tuple[bool, str]:
        """Verify backup integrity using hasher."""
        with expected_error_boundary("hashing backup archive", resource=str(archive_path), backend=self._hasher.name):
            hash_result = self._hasher.hash_file(archive_path)
        return hash_result.checksum != "", hash_result.checksum

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
