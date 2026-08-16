"""RestoreService - handles restore operations."""

from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

from ..errors import ValidationError, expected_error_boundary
from ..results.restore import RestoreFileInfo, RestorePreviewResult, RestoreResult, Timing
from ..specs.archive import ArchiveListSpec, ArchiveVerifySpec
from ..specs.common import absolute_local_path, contained_path, normalize_archive_member
from .run_tracking import snapshot_spec, track_run

if TYPE_CHECKING:
    from ..ports.archiver import ArchiverPort
    from ..ports.run_store import RunStorePort
    from ..specs.restore import RestoreSpec


class RestoreService:
    def __init__(self, *, archiver: ArchiverPort, run_store: RunStorePort | None = None) -> None:
        self._archiver = archiver
        self._run_store = run_store

    def extract(self, spec: RestoreSpec) -> RestoreResult:
        import time

        spec = spec.normalize()

        def _execute() -> RestoreResult:
            start_ms = int(time.time() * 1000)
            if spec.requires_confirmation and spec.confirm_overwrite:
                raise ValidationError(
                    msg="Restore destination already exists and requires explicit confirmation",
                    details={"destination": str(spec.effective_destination), "confirm_overwrite": spec.confirm_overwrite},
                )
            if spec.dry_run:
                return self._dry_run_extract(spec)
            if spec.paths:
                # Validate service-level selectors before delegating to a port.
                # Ports must still enforce their own extraction policy, but the
                # service contract cannot report success for a missing pick just
                # because a backend silently ignores unknown members.
                self.preview(spec)

            verification_passed = None
            warnings: list[str] = []
            if spec.verify:
                with expected_error_boundary("verifying archive before restore", resource=str(spec.archive), backend=self._archiver.name):
                    verify_result = self._archiver.verify(ArchiveVerifySpec(archive_path=spec.archive).normalize())
                warnings.extend(verify_result.warnings)
                if not verify_result.success:
                    from ..errors import ExternalToolError

                    raise ExternalToolError(
                        msg=f"Archive verification failed: {spec.archive}",
                        details={"failures": list(verify_result.failed_entries), "errors": list(verify_result.errors)},
                    )
                verification_passed = True

            with expected_error_boundary("extracting archive", resource=str(spec.archive), backend=self._archiver.name):
                restored_paths = self._archiver.extract(spec)
            bytes_restored = _sum_existing_file_bytes(restored_paths)
            return RestoreResult(
                success=True,
                dry_run=False,
                timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
                archive_path=spec.archive,
                destination_path=spec.effective_destination,
                restored_paths=restored_paths,
                files_restored=len(restored_paths),
                bytes_restored=bytes_restored,
                verification_passed=verification_passed,
                warnings=tuple(warnings),
            )

        return track_run(
            self._run_store,
            operation="restore",
            source_paths=(str(spec.archive),),
            destination_path=str(spec.effective_destination) if spec.effective_destination else None,
            archive_path=str(spec.archive),
            spec_snapshot=snapshot_spec(spec),
            execute=_execute,
            files_count=lambda result: result.files_restored,
            bytes_processed=lambda result: result.bytes_restored,
        )

    def preview(self, spec: RestoreSpec) -> RestorePreviewResult:
        spec = spec.normalize()
        all_entries = []
        page = 0
        page_size = 1000
        with expected_error_boundary("previewing archive restore", resource=str(spec.archive), backend=self._archiver.name):
            while True:
                batch = self._archiver.list(ArchiveListSpec(archive_path=spec.archive, page=page, page_size=page_size).normalize())
                all_entries.extend(batch)
                if len(batch) < page_size:
                    break
                page += 1

        safe_entries = tuple((_normalize_preview_entry(e), e) for e in all_entries)
        if spec.paths:
            entries = tuple((name, e) for name, e in safe_entries if any(_archive_path_matches(name, requested) for requested in spec.paths))
            _ensure_restore_selectors_found(spec.paths, tuple(name for name, _e in entries))
        else:
            entries = safe_entries
        file_entries = tuple((name, e) for name, e in entries if not e.is_dir)
        destination = absolute_local_path(spec.effective_destination or Path.cwd())
        total_size = sum(e.size for _name, e in file_entries)
        disk_paths = tuple((name, contained_path(destination, name), e) for name, e in file_entries)
        existing_files = sum(1 for _name, disk_path, _e in disk_paths if disk_path.exists())

        return RestorePreviewResult(
            success=True,
            archive_path=spec.archive,
            total_files=len(file_entries),
            total_size=total_size,
            existing_files=existing_files,
            warnings=("Restore destination already exists and requires confirmation",) if spec.requires_confirmation and spec.confirm_overwrite else (),
            files=tuple(
                RestoreFileInfo(
                    archive_path=name,
                    disk_path=disk_path,
                    size=e.size,
                    mtime_ns=e.mtime_ns,
                    is_partial=bool(spec.paths),
                )
                for name, disk_path, e in disk_paths
            ),
        )

    def pick(self, spec: RestoreSpec) -> RestoreResult:
        if not spec.paths:
            raise ValidationError(msg="pick() requires specific paths to restore", details={"paths_provided": False})
        return self.extract(spec)

    def _dry_run_extract(self, spec: RestoreSpec) -> RestoreResult:
        import time

        start_ms = int(time.time() * 1000)
        preview = self.preview(spec)
        return RestoreResult(
            success=True,
            dry_run=True,
            timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
            archive_path=spec.archive,
            destination_path=spec.effective_destination,
            files_restored=preview.total_files,
            bytes_restored=preview.total_size,
            warnings=("Dry run - files not extracted", *preview.warnings),
        )


def _normalize_preview_entry(entry: object) -> str:
    path = getattr(entry, "path")
    is_dir = bool(getattr(entry, "is_dir"))
    normalized = normalize_archive_member(path)
    return normalized.rstrip("/") + "/" if is_dir else normalized


def _archive_path_matches(entry_path: str, requested_path: str) -> bool:
    entry = normalize_archive_member(entry_path).rstrip("/")
    requested = normalize_archive_member(requested_path).rstrip("/")
    if entry == requested:
        return True
    return entry.startswith(f"{requested}/")


def _ensure_restore_selectors_found(selectors: tuple[str, ...], matched_entries: tuple[str, ...]) -> None:
    from ..errors import NotFoundError

    missing: list[str] = []
    for selector in selectors:
        requested = normalize_archive_member(selector).rstrip("/")
        if not any(_archive_path_matches(entry, requested) for entry in matched_entries):
            missing.append(requested)
    if missing:
        raise NotFoundError(msg="Requested restore paths were not found in archive", details={"missing_paths": missing})


def _sum_existing_file_bytes(paths: tuple[Path, ...]) -> int:
    total = 0
    for path in paths:
        try:
            if path.is_file():
                total += path.stat().st_size
        except OSError:
            continue
    return total
