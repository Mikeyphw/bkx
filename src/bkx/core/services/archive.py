"""ArchiveService - Spec-first archive use cases."""

from __future__ import annotations

from typing import TYPE_CHECKING

from ..errors import expected_error_boundary
from ..results.archive import ArchiveBrowseResult, ArchiveCreateResult, ArchiveEntry, ArchiveInfoResult, ArchiveListResult, ArchiveVerifyResult
from ..specs.archive import ArchiveListSpec
from .run_tracking import snapshot_spec, track_run

if TYPE_CHECKING:
    from ..ports.archiver import ArchiverPort
    from ..ports.run_store import RunStorePort
    from ..specs.archive import ArchiveBrowseSpec, ArchiveCreateSpec, ArchiveInfoSpec, ArchiveVerifySpec


class ArchiveService:
    def __init__(self, *, archiver: ArchiverPort, run_store: RunStorePort | None = None) -> None:
        self._archiver = archiver
        self._run_store = run_store

    def create(self, spec: ArchiveCreateSpec) -> ArchiveCreateResult:
        spec = spec.normalize()

        def _execute() -> ArchiveCreateResult:
            if spec.dry_run:
                import time
                from ..results.backup import Timing

                now = int(time.time() * 1000)
                return ArchiveCreateResult(
                    success=True,
                    dry_run=True,
                    timing=Timing(now, now),
                    archive_path=spec.effective_output_path,
                    warnings=("Dry run - archive not created",),
                )
            with expected_error_boundary(
                "creating archive",
                resource=str(spec.effective_output_path) if spec.effective_output_path else None,
                backend=self._archiver.name,
            ):
                return self._archiver.create(spec)

        return track_run(
            self._run_store,
            operation="archive",
            source_paths=tuple(str(source) for source in spec.sources),
            destination_path=None,
            archive_path=str(spec.effective_output_path) if spec.effective_output_path else None,
            spec_snapshot=snapshot_spec(spec),
            execute=_execute,
            files_count=lambda result: result.files_archived,
            bytes_processed=lambda result: result.bytes_written,
        )

    def list(self, spec: ArchiveListSpec) -> ArchiveListResult:
        spec = spec.normalize()
        with expected_error_boundary("listing archive", resource=str(spec.archive_path), backend=self._archiver.name):
            entries, total_entries = _list_page_with_total(self._archiver, spec)
        return ArchiveListResult(
            success=True,
            archive_path=spec.archive_path,
            entries=entries,
            total_entries=total_entries,
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
        with expected_error_boundary("browsing archive", resource=str(spec.archive_path), backend=self._archiver.name):
            filtered = list(_read_all_entries(self._archiver, spec.archive_path))
        if spec.query:
            query = spec.query.strip().lower()
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


def _list_page_with_total(archiver: ArchiverPort, spec: ArchiveListSpec) -> tuple[tuple[ArchiveEntry, ...], int]:
    list_with_total = getattr(archiver, "list_with_total", None)
    if callable(list_with_total):
        return list_with_total(spec)
    all_entries = tuple(_read_all_entries(archiver, spec.archive_path, filter_pattern=spec.filter_pattern))
    start = spec.page * spec.page_size
    return all_entries[start : start + spec.page_size], len(all_entries)


def _read_all_entries(archiver: ArchiverPort, archive_path, *, filter_pattern: str | None = None) -> tuple[ArchiveEntry, ...]:
    read_all = getattr(archiver, "_read_all_entries", None)
    if callable(read_all):
        entries = tuple(read_all(archive_path))
    else:
        entries_list: list[ArchiveEntry] = []
        page = 0
        page_size = 1000
        while True:
            batch = tuple(archiver.list(ArchiveListSpec(archive_path=archive_path, page=page, page_size=page_size).normalize()))
            entries_list.extend(batch)
            if len(batch) < page_size:
                break
            page += 1
        entries = tuple(entries_list)
    if filter_pattern:
        import fnmatch

        entries = tuple(entry for entry in entries if fnmatch.fnmatch(entry.path, filter_pattern))
    return entries
