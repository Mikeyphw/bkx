"""ArchiveService - Spec-first archive use cases."""

from __future__ import annotations

from typing import TYPE_CHECKING

from ..errors import expected_error_boundary
from ..results.archive import ArchiveBrowseResult, ArchiveCreateResult, ArchiveInfoResult, ArchiveListResult, ArchiveVerifyResult
from ..specs.archive import ArchiveListSpec

if TYPE_CHECKING:
    from ..ports.archiver import ArchiverPort
    from ..specs.archive import ArchiveBrowseSpec, ArchiveCreateSpec, ArchiveInfoSpec, ArchiveVerifySpec


class ArchiveService:
    def __init__(self, *, archiver: ArchiverPort) -> None:
        self._archiver = archiver

    def create(self, spec: ArchiveCreateSpec) -> ArchiveCreateResult:
        spec = spec.normalize()
        if spec.dry_run:
            import time
            from ..results.backup import Timing
            now = int(time.time() * 1000)
            return ArchiveCreateResult(success=True, dry_run=True, timing=Timing(now, now), archive_path=spec.effective_output_path, warnings=("Dry run - archive not created",))
        with expected_error_boundary("creating archive", resource=str(spec.effective_output_path) if spec.effective_output_path else None, backend=self._archiver.name):
            return self._archiver.create(spec)

    def list(self, spec: ArchiveListSpec) -> ArchiveListResult:
        spec = spec.normalize()
        with expected_error_boundary("listing archive", resource=str(spec.archive_path), backend=self._archiver.name):
            entries = self._archiver.list(spec)
        return ArchiveListResult(
            success=True,
            archive_path=spec.archive_path,
            entries=entries,
            total_entries=len(entries),
            page=spec.page,
            page_size=spec.page_size,
            filter_pattern=spec.filter_pattern,
        )

    def info(self, spec: ArchiveInfoSpec) -> ArchiveInfoResult:
        spec = spec.normalize()
        with expected_error_boundary("reading archive info", resource=str(spec.archive_path), backend=self._archiver.name):
            return self._archiver.info(spec)

    def verify(self, spec: ArchiveVerifySpec) -> ArchiveVerifyResult:
        spec = spec.normalize()
        with expected_error_boundary("verifying archive", resource=str(spec.archive_path), backend=self._archiver.name):
            return self._archiver.verify(spec)

    def browse(self, spec: ArchiveBrowseSpec) -> ArchiveBrowseResult:
        spec = spec.normalize()
        # RM-023 owns complete pagination. OV-02 still routes the operation through
        # a canonical list Spec so the service/port boundary is consistent.
        list_spec = ArchiveListSpec(archive_path=spec.archive_path, page=0, page_size=1000).normalize()
        with expected_error_boundary("browsing archive", resource=str(spec.archive_path), backend=self._archiver.name):
            filtered = list(self._archiver.list(list_spec))
        if spec.query:
            query = spec.query.lower()
            filtered = [entry for entry in filtered if query in entry.path.lower()]
        if spec.file_type == "file":
            filtered = [entry for entry in filtered if not entry.is_dir]
        elif spec.file_type == "dir":
            filtered = [entry for entry in filtered if entry.is_dir]
        if spec.min_size is not None:
            filtered = [entry for entry in filtered if entry.size >= spec.min_size]
        if spec.max_size is not None:
            filtered = [entry for entry in filtered if entry.size <= spec.max_size]
        total_matches = len(filtered)
        reverse = spec.sort_order == "desc"
        key = {"path": lambda e: e.path, "size": lambda e: e.size, "mtime": lambda e: e.mtime_ns}[spec.sort_by]
        filtered.sort(key=key, reverse=reverse)
        entries = tuple(filtered[spec.offset : spec.offset + spec.limit])
        return ArchiveBrowseResult(
            success=True,
            archive_path=spec.archive_path,
            query=spec.query,
            file_type=spec.file_type,
            min_size=spec.min_size,
            max_size=spec.max_size,
            entries=entries,
            total_matches=total_matches,
            limit=spec.limit,
            offset=spec.offset,
            sort_by=spec.sort_by,
            sort_order=spec.sort_order,
            total_size=sum(entry.size for entry in entries),
            directories_count=sum(entry.is_dir for entry in entries),
            files_count=sum(not entry.is_dir for entry in entries),
        )
