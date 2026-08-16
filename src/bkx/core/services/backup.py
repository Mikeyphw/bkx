"""BackupService - truthful backup orchestration.

Contract rule:
- Services accept Specs only.
- Services return Results only.
- Services raise typed errors only.
- No rendering, no parsing.
"""

from __future__ import annotations

import shutil
import tempfile
import time
from pathlib import Path
from typing import TYPE_CHECKING

from ..errors import ExternalToolError, ValidationError, expected_error_boundary
from ..results.archive import ArchiveCreateResult
from ..results.backup import BackupResult, Timing
from ..results.transfer import TransferResult
from ..specs.archive import ArchiveCreateSpec, ArchiveVerifySpec
from ..specs.backup import BackupSpec
from ..specs.transfer import TransferSpec
from .run_tracking import snapshot_spec, track_run

if TYPE_CHECKING:
    from ..ports.archiver import ArchiverPort
    from ..ports.hasher import HasherPort
    from ..ports.run_store import RunStorePort
    from ..ports.tool_detector import ToolDetectorPort


class BackupService:
    """Orchestrate archive creation, optional transfer, and verification."""

    def __init__(
        self,
        *,
        archiver: ArchiverPort,
        transfer: TransferPort,
        hasher: HasherPort,
        tool_detector: ToolDetectorPort,
        run_store: RunStorePort | None = None,
    ) -> None:
        self._archiver = archiver
        self._transfer = transfer
        self._hasher = hasher
        self._tool_detector = tool_detector
        self._run_store = run_store

    def create(self, spec: BackupSpec) -> BackupResult:
        spec = spec.normalize()
        return track_run(
            self._run_store,
            operation="backup",
            source_paths=tuple(str(source) for source in spec.sources),
            destination_path=str(spec.effective_destination) if spec.effective_destination else None,
            archive_path=str(spec.archive_path) if spec.archive_path else None,
            spec_snapshot=snapshot_spec(spec),
            execute=lambda: self._create_untracked(spec),
            files_count=lambda result: result.files_processed,
            bytes_processed=lambda result: result.bytes_written,
        )

    def _create_untracked(self, spec: BackupSpec) -> BackupResult:
        if spec.dry_run:
            return self._execute_dry_run(spec)

        start_ms = int(time.time() * 1000)
        warnings: list[str] = []
        checksums: dict[str, str] = {}
        step_results: dict[str, bool] = {}

        with _backup_staging_dir(spec) as staging_dir:
            archive_result = self._create_archive(spec, staging_dir=staging_dir)
            warnings.extend(archive_result.warnings)
            step_results["archive"] = archive_result.success
            if not archive_result.success:
                return _failed_backup(
                    start_ms,
                    step_results=step_results,
                    warnings=warnings,
                    errors=archive_result.errors or ("Archive creation failed",),
                )
            staged_artifacts = _archive_artifacts(archive_result)
            if spec.verify:
                if archive_result.verification_passed is False:
                    step_results["verify_archive"] = False
                    return _failed_backup(
                        start_ms,
                        step_results=step_results,
                        warnings=warnings,
                        errors=("Archive verification did not pass",),
                        files_processed=archive_result.files_archived,
                        bytes_written=archive_result.bytes_written,
                        archive_paths=staged_artifacts,
                    )
                archive_verified = True
                if archive_result.verification_passed is not True:
                    archive_verified = self._verify_archive_artifacts(staged_artifacts)
                step_results["verify_archive"] = archive_verified
                if not archive_verified:
                    return _failed_backup(
                        start_ms,
                        step_results=step_results,
                        warnings=warnings,
                        errors=("Archive verification did not pass",),
                        files_processed=archive_result.files_archived,
                        bytes_written=archive_result.bytes_written,
                        archive_paths=staged_artifacts,
                    )

            for index, artifact in enumerate(staged_artifacts):
                checksum = self._hash_artifact(artifact)
                checksums[artifact.name] = checksum
                if index == 0:
                    checksums["archive"] = checksum

            final_artifacts = staged_artifacts
            transfer_result = self._transfer_artifacts(spec, staged_artifacts)
            warnings.extend(transfer_result.warnings)
            step_results["transfer"] = transfer_result.success
            if not transfer_result.success:
                return _failed_backup(
                    start_ms,
                    step_results=step_results,
                    warnings=warnings,
                    errors=transfer_result.errors or ("Archive transfer failed",),
                    files_processed=archive_result.files_archived,
                    bytes_written=archive_result.bytes_written,
                    archive_paths=staged_artifacts,
                )
            if spec.effective_destination is not None:
                final_artifacts = _final_artifact_paths(spec, staged_artifacts)

            if spec.verify:
                transfer_ok = spec.effective_destination is None or _transfer_verification_passed(transfer_result)
                step_results["verify_transfer"] = transfer_ok
                if not transfer_ok:
                    return _failed_backup(
                        start_ms,
                        step_results=step_results,
                        warnings=warnings,
                        errors=("Transfer verification did not pass",),
                        files_processed=archive_result.files_archived,
                        bytes_written=archive_result.bytes_written,
                        archive_paths=final_artifacts,
                    )

            return BackupResult(
                success=True,
                dry_run=False,
                timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
                files_processed=archive_result.files_archived,
                bytes_written=archive_result.bytes_written + transfer_result.bytes_transferred,
                archives_created=len(final_artifacts),
                archive_paths=final_artifacts,
                verification_passed=True if spec.verify else None,
                warnings=tuple(warnings),
                step_results=step_results,
                checksums=checksums,
            )

    def _create_archive(self, spec: BackupSpec, *, staging_dir: Path | None = None) -> ArchiveCreateResult:
        output_path = _staged_archive_path(spec, staging_dir)
        archive_spec = ArchiveCreateSpec(
            sources=spec.sources,
            output_path=output_path,
            excludes=spec.excludes,
            includes=spec.includes,
            split_size=spec.split_size,
            compression=spec.compression,
            verify=spec.verify,
            dry_run=False,
        ).normalize(auto_name=True)
        with expected_error_boundary("creating backup archive", resource=str(archive_spec.output_path) if archive_spec.output_path else None, backend=self._archiver.name):
            return self._archiver.create(archive_spec)

    def _transfer_artifacts(self, spec: BackupSpec, artifacts: tuple[Path, ...]) -> TransferResult:
        if spec.effective_destination is None:
            now = int(time.time() * 1000)
            return TransferResult(success=True, direction="none", timing=Timing(start_ms=now, end_ms=now), files_transferred=0, bytes_transferred=0, verification_passed=True if spec.verify else None, checksum_verified=True if spec.verify else None)
        destination = _transfer_destination(spec, artifacts)
        if len(artifacts) > 1 and isinstance(destination, Path):
            destination.mkdir(parents=True, exist_ok=True)
        transfer_spec = TransferSpec(sources=artifacts, destination=destination, direction="push", verify=spec.verify, dry_run=False, overwrite=False).normalize()
        with expected_error_boundary("transferring backup archive", resource=", ".join(str(path) for path in artifacts), backend=self._transfer.name):
            return self._transfer.push(transfer_spec)

    def _hash_artifact(self, archive_path: Path) -> str:
        # Hashes are recorded as evidence only. Verification truth comes from
        # archiver/transfer verification results and cannot be inferred from a
        # non-empty checksum string.
        with expected_error_boundary("hashing backup artifact", resource=str(archive_path), backend=self._hasher.name):
            return self._hasher.hash_file(archive_path).checksum

    def _verify_archive_artifacts(self, artifacts: tuple[Path, ...]) -> bool:
        for artifact in artifacts:
            with expected_error_boundary("verifying backup archive", resource=str(artifact), backend=self._archiver.name):
                result = self._archiver.verify(ArchiveVerifySpec(archive_path=artifact).normalize())
            if not result.success:
                return False
        return True

    def _execute_dry_run(self, spec: BackupSpec) -> BackupResult:
        start_ms = int(time.time() * 1000)
        warnings = [] if spec.archive_name else ["Archive name would be auto-generated"]
        return BackupResult(success=True, dry_run=True, timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)), warnings=tuple(warnings), step_results={"archive": True, "transfer": spec.effective_destination is not None, "verify": spec.verify})


class _backup_staging_dir:
    def __init__(self, spec: BackupSpec) -> None:
        self._spec = spec
        self.path: Path | None = None

    def __enter__(self) -> Path | None:
        if self._spec.effective_destination is None:
            return None
        self.path = Path(tempfile.mkdtemp(prefix="bkx-backup-stage-"))
        return self.path

    def __exit__(self, exc_type, exc, tb) -> None:
        if self.path is not None:
            shutil.rmtree(self.path, ignore_errors=True)


def _staged_archive_path(spec: BackupSpec, staging_dir: Path | None) -> Path | None:
    name = spec.archive_name or "backup"
    if staging_dir is None:
        return spec.archive_path or (Path(f"{name}.bakery") if spec.archive_name else None)
    return staging_dir / f"{name}.bakery"


def _archive_artifacts(result: ArchiveCreateResult) -> tuple[Path, ...]:
    artifacts = tuple(result.split_parts) if result.split_parts else ((result.archive_path,) if result.archive_path else tuple())
    if not artifacts:
        raise ExternalToolError(msg="Archive backend reported success without an artifact path", tool_name="archive")
    return artifacts


def _transfer_destination(spec: BackupSpec, artifacts: tuple[Path, ...]) -> Path:
    destination = spec.effective_destination
    if destination is None:
        raise ValidationError(msg="Backup transfer requires a destination")
    if len(artifacts) == 1:
        return spec.archive_path or (destination / artifacts[0].name)
    return destination


def _final_artifact_paths(spec: BackupSpec, artifacts: tuple[Path, ...]) -> tuple[Path, ...]:
    destination = spec.effective_destination
    if destination is None:
        return artifacts
    if len(artifacts) == 1:
        return (spec.archive_path or (destination / artifacts[0].name),)
    return tuple(destination / artifact.name for artifact in artifacts)


def _transfer_verification_passed(result: TransferResult) -> bool:
    explicit_checks = (result.verification_passed, result.checksum_verified)
    if any(value is False for value in explicit_checks):
        return False
    return any(value is True for value in explicit_checks)


def _failed_backup(start_ms: int, *, step_results: dict[str, bool], warnings: list[str], errors: tuple[str, ...], files_processed: int = 0, bytes_written: int = 0, archive_paths: tuple[Path, ...] = ()) -> BackupResult:
    return BackupResult(success=False, dry_run=False, timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)), files_processed=files_processed, bytes_written=bytes_written, archives_created=len(archive_paths), archive_paths=archive_paths, verification_passed=False if "verify" in " ".join(step_results) else None, warnings=tuple(warnings), errors=tuple(errors), step_results=step_results)
