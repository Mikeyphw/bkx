"""FallbackArchiver - pure Python archive operations."""

from __future__ import annotations

import fnmatch
import os
import shutil
import tarfile
import tempfile
import time
import zipfile
from collections.abc import Callable, Iterable
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, Literal

from bkx.core.errors import NotFoundError, ValidationError, expected_error_boundary
from bkx.core.specs.common import absolute_local_path, contained_path

from ..results.archive import ArchiveCreateResult, ArchiveEntry, ArchiveInfoResult, ArchiveVerifyResult
from ..results.backup import Timing
from ..specs.archive import ArchiveCreateSpec, ArchiveInfoSpec, ArchiveListSpec, ArchiveVerifySpec
from ..specs.backup import DEFAULT_COMPRESSION, CompressionProfile
from ..specs.common import normalize_archive_member
from ..specs.restore import RestoreSpec
from .traversal import SourceTraversalManifest, TraversalEntry, build_source_manifest

ArchiveFormat = Literal["zip", "tar"]


class FallbackArchiver:
    """Pure Python archiver using tarfile and zipfile.

    Creation is driven by a shared traversal manifest, publishes through a
    temporary file in the destination directory, and verifies before replacing
    the final path when requested.
    """

    def __init__(self, *, selected_tool: str | None = None) -> None:
        self._name = f"python-builtin/{selected_tool}" if selected_tool else "python-builtin"
        self._supported_formats = ("tar", "zip")

    @property
    def name(self) -> str:
        return self._name

    @property
    def supported_formats(self) -> tuple[str, ...]:
        return self._supported_formats

    def create(self, spec: ArchiveCreateSpec, *, progress_callback: Callable[[int, int], Any] | None = None) -> ArchiveCreateResult:
        start_ms = int(time.time() * 1000)
        spec = spec.normalize() if not spec.effective_output_path else spec
        output_path = spec.effective_output_path
        if output_path is None:
            raise ValueError("output_path is required")
        if spec.split_size is not None:
            raise ValidationError(msg="Split archives are not supported by the builtin archiver", details={"split_size": spec.split_size})
        if output_path.exists():
            raise ValidationError(msg="Archive output already exists", details={"output_path": str(output_path)})
        manifest = build_source_manifest(
            spec.sources,
            includes=spec.includes,
            excludes=spec.excludes,
            output_path=output_path,
            symlink_policy="reject",
        )
        archive_format = _detect_format_for_create(output_path)
        spec, warnings = _resolve_builtin_compression_compat(spec, archive_format)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path: Path | None = None
        try:
            fd, raw_tmp = tempfile.mkstemp(prefix=f".{output_path.name}.", suffix=".tmp", dir=str(output_path.parent))
            os.close(fd)
            tmp_path = Path(raw_tmp)
            if archive_format == "zip":
                warnings.extend(self._write_zip(tmp_path, manifest, spec))
            else:
                warnings.extend(self._write_tar(tmp_path, manifest, spec))
            if spec.verify:
                verify_result = self.verify(ArchiveVerifySpec(archive_path=tmp_path))
                if not verify_result.success:
                    raise ValidationError(
                        msg="Archive verification failed before publication",
                        details={"output_path": str(output_path), "failed_entries": list(verify_result.failed_entries)},
                    )
            _fsync_file(tmp_path)
            os.replace(tmp_path, output_path)
            tmp_path = None
            _fsync_dir(output_path.parent)
        except Exception:
            if tmp_path is not None:
                try:
                    tmp_path.unlink(missing_ok=True)
                except OSError:
                    pass
            raise

        bytes_written = output_path.stat().st_size if output_path.exists() else 0
        created = ArchiveCreateResult(
            success=True,
            dry_run=False,
            timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
            files_archived=len(manifest.files),
            bytes_written=bytes_written,
            original_size=manifest.total_size,
            archive_path=output_path,
            verification_passed=True if spec.verify else None,
            compression_ratio=(bytes_written / manifest.total_size) if manifest.total_size else 1.0,
            warnings=tuple(warnings),
        )
        if progress_callback is not None:
            progress_callback(created.files_archived, created.bytes_written)
        return created

    def _write_tar(self, tmp_path: Path, manifest: SourceTraversalManifest, spec: ArchiveCreateSpec) -> list[str]:
        mode, tar_kwargs = _tar_mode_for(spec)
        with tarfile.open(tmp_path, mode, **tar_kwargs) as tar:
            for entry in manifest.entries:
                if entry.is_dir:
                    info = tarfile.TarInfo(entry.archive_name.rstrip("/") + "/")
                    info.type = tarfile.DIRTYPE
                    info.mtime = entry.mtime_ns / 1_000_000_000
                    tar.addfile(info)
                else:
                    tar.add(entry.path, arcname=entry.archive_name, recursive=False, filter=_tar_filter)
        return []

    def _write_zip(self, tmp_path: Path, manifest: SourceTraversalManifest, spec: ArchiveCreateSpec) -> list[str]:
        compression, compresslevel = _zip_compression_for(spec)
        with zipfile.ZipFile(tmp_path, "w", compression=compression, compresslevel=compresslevel) as zf:
            for entry in manifest.entries:
                if entry.is_dir:
                    name = entry.archive_name.rstrip("/") + "/"
                    info = zipfile.ZipInfo(name)
                    info.external_attr = 0o40755 << 16
                    zf.writestr(info, b"")
                else:
                    zf.write(entry.path, arcname=entry.archive_name)
        return []

    def list(self, spec: ArchiveListSpec) -> tuple[ArchiveEntry, ...]:
        entries = self._read_all_entries(spec.archive_path)
        if spec.filter_pattern:
            entries = [e for e in entries if fnmatch.fnmatch(e.path, spec.filter_pattern)]
        start = spec.page * spec.page_size
        return tuple(entries[start : start + spec.page_size])

    def list_with_total(self, spec: ArchiveListSpec) -> tuple[tuple[ArchiveEntry, ...], int]:
        entries = self._read_all_entries(spec.archive_path)
        if spec.filter_pattern:
            entries = [e for e in entries if fnmatch.fnmatch(e.path, spec.filter_pattern)]
        total = len(entries)
        start = spec.page * spec.page_size
        return tuple(entries[start : start + spec.page_size]), total

    def _read_all_entries(self, archive_path: Path) -> list[ArchiveEntry]:
        archive_format = _detect_format_for_read(archive_path)
        if archive_format == "zip":
            return self._list_zip(archive_path)
        return self._list_tar(archive_path)

    def _list_tar(self, archive_path: Path) -> list[ArchiveEntry]:
        entries: list[ArchiveEntry] = []
        with tarfile.open(archive_path, "r:*") as tar:
            for member in tar.getmembers():
                entries.append(ArchiveEntry(path=member.name, is_dir=member.isdir(), size=member.size, mtime_ns=int(member.mtime * 1e9)))
        return entries

    def _list_zip(self, archive_path: Path) -> list[ArchiveEntry]:
        entries: list[ArchiveEntry] = []
        with zipfile.ZipFile(archive_path, "r") as zf:
            for info in zf.infolist():
                # Convert the DOS timestamp to a stable integer representation.
                mtime_ns = int(time.mktime((*info.date_time, 0, 0, -1)) * 1_000_000_000)
                entries.append(ArchiveEntry(path=info.filename, is_dir=info.is_dir(), size=info.file_size, mtime_ns=mtime_ns))
        return entries

    def info(self, spec: ArchiveInfoSpec) -> ArchiveInfoResult:
        entries = self._read_all_entries(spec.archive_path)
        total_size = spec.archive_path.stat().st_size
        total_entries = len(entries)
        uncompressed_size = sum(e.size for e in entries if not e.is_dir)
        compression_ratio = total_size / uncompressed_size if uncompressed_size > 0 else 1.0
        return ArchiveInfoResult(
            success=True,
            archive_path=spec.archive_path,
            format=_detect_format_for_read(spec.archive_path),
            created_at=int(spec.archive_path.stat().st_mtime),
            total_entries=total_entries,
            total_size=total_size,
            uncompressed_size=uncompressed_size,
            compression_ratio=compression_ratio,
        )

    def verify(self, spec: ArchiveVerifySpec) -> ArchiveVerifyResult:
        archive_path = spec.archive_path.expanduser().resolve(strict=False)
        if not archive_path.exists():
            raise NotFoundError(msg=f"Archive not found: {archive_path}", details={"path": str(archive_path)})
        archive_format = _detect_format_for_read(archive_path)
        checksums = _archive_checksums(archive_path, spec.hash_algorithms)
        result = self._verify_zip(archive_path) if archive_format == "zip" else self._verify_tar(archive_path)
        return ArchiveVerifyResult(
            success=result.success,
            archive_path=result.archive_path,
            checksums_valid=result.checksums_valid,
            structure_valid=result.structure_valid,
            entries_checked=result.entries_checked,
            entries_failed=result.entries_failed,
            failed_entries=result.failed_entries,
            archive_checksums=checksums,
            warnings=result.warnings,
            errors=result.errors,
        )

    def _verify_tar(self, archive_path: Path) -> ArchiveVerifyResult:
        checksums_valid = True
        structure_valid = True
        failed_entries: list[str] = []
        entries_checked = 0
        errors: list[str] = []
        try:
            with tarfile.open(archive_path, "r:*") as tar:
                for member in tar.getmembers():
                    entries_checked += 1
                    try:
                        normalize_archive_member(member.name)
                        if member.islnk() or member.issym():
                            raise ValidationError(msg="Archive link members are unsafe in this verification policy", details={"member": member.name})
                        if member.isfile():
                            stream = tar.extractfile(member)
                            if stream is None:
                                raise tarfile.TarError("regular file had no payload stream")
                            while stream.read(1024 * 1024):
                                pass
                    except Exception as exc:
                        checksums_valid = False
                        failed_entries.append(member.name)
                        errors.append(f"{member.name}: {exc}")
        except Exception as exc:
            structure_valid = False
            errors.append(str(exc))
        return ArchiveVerifyResult(
            success=checksums_valid and structure_valid,
            archive_path=archive_path,
            checksums_valid=checksums_valid,
            structure_valid=structure_valid,
            entries_checked=entries_checked,
            entries_failed=len(failed_entries),
            failed_entries=tuple(failed_entries),
            errors=tuple(errors),
        )

    def _verify_zip(self, archive_path: Path) -> ArchiveVerifyResult:
        checksums_valid = True
        structure_valid = True
        failed_entries: list[str] = []
        errors: list[str] = []
        entries_checked = 0
        try:
            with zipfile.ZipFile(archive_path, "r") as zf:
                infos = zf.infolist()
                bad = zf.testzip()
                if bad is not None:
                    checksums_valid = False
                    failed_entries.append(bad)
                    errors.append(f"CRC/read failed for {bad}")
                for info in infos:
                    entries_checked += 1
                    try:
                        normalize_archive_member(info.filename)
                        if not info.is_dir():
                            with zf.open(info, "r") as fh:
                                while fh.read(1024 * 1024):
                                    pass
                    except Exception as exc:
                        checksums_valid = False
                        if info.filename not in failed_entries:
                            failed_entries.append(info.filename)
                        errors.append(f"{info.filename}: {exc}")
        except Exception as exc:
            structure_valid = False
            errors.append(str(exc))
        return ArchiveVerifyResult(
            success=checksums_valid and structure_valid,
            archive_path=archive_path,
            checksums_valid=checksums_valid,
            structure_valid=structure_valid,
            entries_checked=entries_checked,
            entries_failed=len(failed_entries),
            failed_entries=tuple(failed_entries),
            errors=tuple(errors),
        )

    def extract(self, spec: RestoreSpec) -> tuple[Path, ...]:
        """Extract an archive through BKX's safe restore policy.

        Never delegates path creation to ``tarfile.extract`` or
        ``ZipFile.extract``. Every member is normalized, joined under the
        restore root, rejected when it is a link/symlink/special entry, and
        written into a staging tree before publication.
        """

        spec = spec.normalize() if spec.effective_destination is None else spec
        archive_path = spec.archive
        destination = absolute_local_path(spec.effective_destination or Path.cwd())
        _ensure_no_symlink_destination_root(destination)
        destination.parent.mkdir(parents=True, exist_ok=True)
        archive_format = _detect_format_for_read(archive_path)
        members = self._safe_members(archive_path, archive_format, spec.paths)
        _ensure_all_requested_paths_matched(spec.paths, [member.name for member in members])

        stage_path: Path | None = None
        try:
            stage_path = Path(tempfile.mkdtemp(prefix=f".{destination.name}.bkx-restore.", dir=str(destination.parent)))
            restored_rel_paths: list[str] = []
            if archive_format == "zip":
                self._extract_zip_members(archive_path, stage_path, members, restored_rel_paths)
            else:
                self._extract_tar_members(archive_path, stage_path, members, restored_rel_paths)
            final_paths = _publish_staged_restore(stage_path, destination, restored_rel_paths, overwrite=spec.overwrite)
            stage_path = None
            return tuple(final_paths)
        finally:
            if stage_path is not None:
                shutil.rmtree(stage_path, ignore_errors=True)

    def _safe_members(self, archive_path: Path, archive_format: ArchiveFormat, selectors: tuple[str, ...]) -> list[SafeArchiveMember]:
        selected: list[SafeArchiveMember] = []
        if archive_format == "zip":
            with zipfile.ZipFile(archive_path, "r") as zf:
                for info in zf.infolist():
                    member = _safe_zip_member(info)
                    if member is not None and _member_selected(member.name, selectors):
                        selected.append(member)
            return selected
        with tarfile.open(archive_path, "r:*") as tar:
            for member in tar.getmembers():
                safe = _safe_tar_member(member)
                if safe is not None and _member_selected(safe.name, selectors):
                    selected.append(safe)
        return selected

    def _extract_zip_members(self, archive_path: Path, root: Path, members: list[SafeArchiveMember], restored_rel_paths: list[str]) -> None:
        with zipfile.ZipFile(archive_path, "r") as zf:
            by_name = {info.filename: info for info in zf.infolist()}
            for member in members:
                target = contained_path(root, member.name)
                _ensure_no_existing_symlink_path(root, target)
                if member.is_dir:
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(by_name[member.raw_name], "r") as src, open(target, "xb") as dst:
                    shutil.copyfileobj(src, dst, length=1024 * 1024)
                restored_rel_paths.append(member.name)

    def _extract_tar_members(self, archive_path: Path, root: Path, members: list[SafeArchiveMember], restored_rel_paths: list[str]) -> None:
        wanted = {member.raw_name: member for member in members}
        with tarfile.open(archive_path, "r:*") as tar:
            for tar_member in tar.getmembers():
                member = wanted.get(tar_member.name)
                if member is None:
                    continue
                target = contained_path(root, member.name)
                _ensure_no_existing_symlink_path(root, target)
                if member.is_dir:
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                stream = tar.extractfile(tar_member)
                if stream is None:
                    raise ValidationError(msg="Archive file member has no readable payload", details={"member": tar_member.name})
                target.parent.mkdir(parents=True, exist_ok=True)
                with open(target, "xb") as dst:
                    shutil.copyfileobj(stream, dst, length=1024 * 1024)
                restored_rel_paths.append(member.name)


@dataclass(frozen=True, kw_only=True)
class SafeArchiveMember:
    raw_name: str
    name: str
    is_dir: bool
    size: int


def _safe_zip_member(info: zipfile.ZipInfo) -> SafeArchiveMember | None:
    name = normalize_archive_member(info.filename)
    mode = (info.external_attr >> 16) & 0o170000
    # ZIP has no single authoritative link bit across platforms. Reject Unix
    # symlink encodings and extract only regular files/directories.
    if mode == 0o120000:
        raise ValidationError(msg="Archive symlink members are unsafe for restore", details={"member": info.filename})
    if info.is_dir():
        return SafeArchiveMember(raw_name=info.filename, name=name.rstrip("/") + "/", is_dir=True, size=0)
    if mode not in {0, 0o100000}:
        raise ValidationError(msg="Unsupported ZIP member type", details={"member": info.filename, "mode": oct(mode)})
    return SafeArchiveMember(raw_name=info.filename, name=name, is_dir=False, size=info.file_size)


def _safe_tar_member(member: tarfile.TarInfo) -> SafeArchiveMember | None:
    name = normalize_archive_member(member.name)
    if member.isdir():
        return SafeArchiveMember(raw_name=member.name, name=name.rstrip("/") + "/", is_dir=True, size=0)
    if member.isfile():
        return SafeArchiveMember(raw_name=member.name, name=name, is_dir=False, size=member.size)
    if member.issym() or member.islnk():
        raise ValidationError(msg="Archive link members are unsafe for restore", details={"member": member.name})
    raise ValidationError(msg="Unsupported TAR member type", details={"member": member.name, "type": member.type.decode(errors="replace") if isinstance(member.type, bytes) else str(member.type)})


def _member_selected(member: str, selectors: tuple[str, ...]) -> bool:
    if not selectors:
        return True
    normalized_member = member.rstrip("/")
    for selector in selectors:
        requested = normalize_archive_member(selector).rstrip("/")
        if normalized_member == requested or normalized_member.startswith(f"{requested}/"):
            return True
    return False


def _ensure_all_requested_paths_matched(selectors: tuple[str, ...], member_names: list[str]) -> None:
    missing: list[str] = []
    for selector in selectors:
        requested = normalize_archive_member(selector).rstrip("/")
        if not any(name.rstrip("/") == requested or name.rstrip("/").startswith(f"{requested}/") for name in member_names):
            missing.append(requested)
    if missing:
        raise NotFoundError(msg="Requested restore paths were not found in archive", details={"missing_paths": missing})


def _ensure_no_existing_symlink_path(root: Path, target: Path) -> None:
    root = absolute_local_path(root)
    target = absolute_local_path(target)
    try:
        relative = target.relative_to(root)
    except ValueError as exc:
        raise ValidationError(msg="Restore target escaped staging root", details={"root": str(root), "target": str(target)}) from exc
    current = root
    if current.is_symlink():
        raise ValidationError(msg="Restore refuses to write through an existing symlink", details={"path": str(current)})
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise ValidationError(msg="Restore refuses to write through an existing symlink", details={"path": str(current)})


def _ensure_no_symlink_destination_root(destination: Path) -> None:
    destination = absolute_local_path(destination)
    current = Path(destination.anchor) if destination.is_absolute() else Path.cwd()
    parts = destination.parts[1:] if destination.is_absolute() else destination.parts
    for part in parts:
        current = current / part
        if current.is_symlink():
            raise ValidationError(msg="Restore refuses to write through a destination symlink", details={"path": str(current)})


def _publish_staged_restore(stage: Path, destination: Path, restored_rel_paths: list[str], *, overwrite: bool) -> list[Path]:
    destination = absolute_local_path(destination)
    _ensure_no_symlink_destination_root(destination)
    final_paths: list[Path] = []
    if not destination.exists():
        destination.parent.mkdir(parents=True, exist_ok=True)
        os.replace(stage, destination)
        for rel in restored_rel_paths:
            final_paths.append(contained_path(destination, rel))
        _fsync_dir(destination.parent)
        return final_paths

    if destination.is_symlink() or not destination.is_dir():
        raise ValidationError(msg="Restore destination must be a real directory when it already exists", details={"destination": str(destination)})

    # Merge staged files into the existing tree. Each file is published through a
    # temporary sibling and os.replace when overwrite is allowed, so failed copies
    # do not leave partial final file contents.
    for source in sorted((p for p in stage.rglob("*") if p.is_dir()), key=lambda p: len(p.parts)):
        rel = source.relative_to(stage).as_posix()
        target_dir = contained_path(destination, rel)
        _ensure_no_existing_symlink_path(destination, target_dir)
        target_dir.mkdir(parents=True, exist_ok=True)
    for source in sorted((p for p in stage.rglob("*") if p.is_file()), key=lambda p: p.relative_to(stage).as_posix()):
        rel = source.relative_to(stage).as_posix()
        target = contained_path(destination, rel)
        _ensure_no_existing_symlink_path(destination, target)
        if target.exists() and not overwrite:
            raise ValidationError(msg="Restore target already exists and overwrite is disabled", details={"path": str(target)})
        target.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp_name = tempfile.mkstemp(prefix=f".{target.name}.", suffix=".tmp", dir=str(target.parent))
        os.close(fd)
        tmp = Path(tmp_name)
        try:
            shutil.copy2(source, tmp)
            _fsync_file(tmp)
            if target.exists() and not overwrite:
                raise ValidationError(msg="Restore target already exists and overwrite is disabled", details={"path": str(target)})
            os.replace(tmp, target)
            final_paths.append(target)
        finally:
            tmp.unlink(missing_ok=True)
    _fsync_dir(destination)
    return final_paths


@dataclass(frozen=True)
class CreateResult:
    files: int
    bytes: int
    original_size: int


def _detect_format_for_create(path: Path) -> ArchiveFormat:
    suffixes = [s.lower() for s in path.suffixes]
    if ".zip" in suffixes:
        return "zip"
    return "tar"


def _detect_format_for_read(path: Path) -> ArchiveFormat:
    if zipfile.is_zipfile(path):
        return "zip"
    if tarfile.is_tarfile(path):
        return "tar"
    suffixes = [s.lower() for s in path.suffixes]
    if ".zip" in suffixes:
        return "zip"
    raise ValidationError(msg="Unsupported or unrecognized archive format", details={"archive_path": str(path)})


def _resolve_builtin_compression_compat(spec: ArchiveCreateSpec, archive_format: ArchiveFormat) -> tuple[ArchiveCreateSpec, list[str]]:
    """Preserve legacy default calls while rejecting explicit unsupported modes.

    Existing callers historically omitted ``compression`` and expected a plain TAR
    or ZIP to be created even though the dataclass default is the global zstd
    profile. Treat only that shared default object as an implicit compatibility
    default; explicit ``CompressionProfile("zstd", 3)`` remains a real request
    and is rejected by the format-specific helpers below when no external zstd
    adapter is in use.
    """

    if spec.compression is not DEFAULT_COMPRESSION or spec.compression.algorithm != "zstd":
        return spec, []
    output_path = spec.effective_output_path or spec.output_path
    suffixes = {suffix.lower() for suffix in (output_path.suffixes if output_path is not None else ())}
    if suffixes & {".zst", ".zstd"}:
        return spec, []
    replacement = CompressionProfile("none", 0) if archive_format == "tar" else CompressionProfile("gzip", 6)
    return (
        replace(spec, compression=replacement),
        [
            "Implicit default zstd compression requires an external adapter; "
            f"builtin {archive_format} creation used {replacement.algorithm} compatibility instead"
        ],
    )


def _tar_mode_for(spec: ArchiveCreateSpec) -> tuple[str, dict[str, int]]:
    algorithm = spec.compression.algorithm
    level = spec.compression.level
    if algorithm == "none":
        return "w", {}
    if algorithm == "gzip":
        return "w:gz", {"compresslevel": level}
    if algorithm == "bzip2":
        return "w:bz2", {"compresslevel": level}
    if algorithm == "xz":
        return "w:xz", {"preset": level}
    raise ValidationError(
        msg="Compression algorithm is unsupported for builtin tar creation",
        details={"algorithm": algorithm, "supported": ["none", "gzip", "bzip2", "xz"]},
    )


def _zip_compression_for(spec: ArchiveCreateSpec) -> tuple[int, int | None]:
    algorithm = spec.compression.algorithm
    level = spec.compression.level
    if algorithm == "none":
        return zipfile.ZIP_STORED, None
    if algorithm == "gzip":
        return zipfile.ZIP_DEFLATED, level
    if algorithm == "bzip2":
        return zipfile.ZIP_BZIP2, level
    if algorithm == "xz":
        return zipfile.ZIP_LZMA, None
    raise ValidationError(
        msg="Compression algorithm is unsupported for builtin zip creation",
        details={"algorithm": algorithm, "supported": ["none", "gzip", "bzip2", "xz"]},
    )


def _tar_filter(info: tarfile.TarInfo) -> tarfile.TarInfo | None:
    info.uid = info.gid = 0
    info.uname = info.gname = ""
    return info


def _fsync_file(path: Path) -> None:
    with open(path, "rb") as fh:
        os.fsync(fh.fileno())


def _fsync_dir(path: Path) -> None:
    try:
        fd = os.open(path, os.O_DIRECTORY)
    except (AttributeError, OSError):
        return
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _archive_checksums(path: Path, algorithms: tuple[Any, ...]) -> dict[str, str]:
    from .hasher import FallbackHasher

    hasher = FallbackHasher()
    return {str(algorithm): hasher.hash_file(path, algorithm).checksum for algorithm in algorithms}


def get_archiver() -> FallbackArchiver:
    from .tool_detector import get_tool_detector

    detector = get_tool_detector()
    selected = detector.get_preferred("archiver") or detector.get_preferred("compression")
    return FallbackArchiver(selected_tool=selected)
