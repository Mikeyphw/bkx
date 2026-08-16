"""FallbackTransfer - pure Python safe local transfer operations."""

from __future__ import annotations

import fnmatch
import hashlib
import os
import shutil
import stat
import tempfile
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

from bkx.core.errors import NotFoundError, ValidationError
from bkx.core.specs.common import absolute_local_path, paths_overlap

from ..ports.transfer import TransferProgress
from ..results.transfer import Timing, TransferResult
from ..specs.transfer import TransferSpec, TransferSyncSpec


class FallbackTransfer:
    """Pure Python transfer using safe local filesystem publication."""

    def __init__(self) -> None:
        self._name = "python-shutil"
        self._supports_push = True
        self._supports_pull = True

    @property
    def name(self) -> str:
        return self._name

    @property
    def supports_push(self) -> bool:
        return self._supports_push

    @property
    def supports_pull(self) -> bool:
        return self._supports_pull

    def push(
        self,
        spec: TransferSpec,
        *,
        progress_callback: Callable[[TransferProgress], Any] | None = None,
    ) -> TransferResult:
        start_ms = int(time.time() * 1000)
        if spec.dry_run:
            mappings = _plan_transfer_mappings(spec.normalize())
            return TransferResult(
                success=True,
                dry_run=True,
                direction="push",
                timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
                source=spec.effective_source,
                destination=spec.effective_destination,
                files_transferred=len(mappings),
                bytes_transferred=sum(src.stat().st_size for src, _dst in mappings),
                warnings=("Dry run - transfer not executed",),
            )
        spec = spec.normalize()
        mappings = _plan_transfer_mappings(spec)
        copied_files, copied_bytes = _copy_mappings(mappings, overwrite=spec.overwrite, verify=spec.verify, checksum=spec.checksum, progress_callback=progress_callback)
        return TransferResult(
            success=True,
            direction="push",
            timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
            source=spec.effective_source,
            destination=spec.effective_destination,
            files_transferred=copied_files,
            bytes_transferred=copied_bytes,
            verification_passed=True if spec.verify else None,
            checksum_verified=True if spec.verify or spec.checksum else None,
        )

    def pull(
        self,
        spec: TransferSpec,
        *,
        progress_callback: Callable[[TransferProgress], Any] | None = None,
    ) -> TransferResult:
        start_ms = int(time.time() * 1000)
        if any(not isinstance(source, Path) for source in spec.sources) or not isinstance(spec.destination, Path):
            raise NotImplementedError("python-shutil pull supports local Path sources and destination only")
        if spec.dry_run:
            mappings = _plan_transfer_mappings(spec.normalize())
            return TransferResult(
                success=True,
                dry_run=True,
                direction="pull",
                timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
                source=spec.effective_source,
                destination=spec.effective_destination,
                files_transferred=len(mappings),
                bytes_transferred=sum(src.stat().st_size for src, _dst in mappings),
                warnings=("Dry run - transfer not executed",),
            )
        spec = spec.normalize()
        mappings = _plan_transfer_mappings(spec)
        copied_files, copied_bytes = _copy_mappings(mappings, overwrite=spec.overwrite, verify=spec.verify, checksum=spec.checksum, progress_callback=progress_callback)
        return TransferResult(
            success=True,
            direction="pull",
            timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
            source=spec.effective_source,
            destination=spec.effective_destination,
            files_transferred=copied_files,
            bytes_transferred=copied_bytes,
            verification_passed=True if spec.verify else None,
            checksum_verified=True if spec.verify or spec.checksum else None,
        )

    def sync(
        self,
        spec: TransferSyncSpec,
        *,
        progress_callback: Callable[[TransferProgress], Any] | None = None,
    ) -> TransferResult:
        start_ms = int(time.time() * 1000)
        spec = spec.normalize()
        source = spec.source
        destination = spec.destination
        mappings = _plan_sync_mappings(source, destination, excludes=spec.excludes)
        # Only copy files whose content differs or is missing.
        needed = [(src, dst) for src, dst in mappings if not dst.exists() or not _same_file_content(src, dst)]
        if spec.dry_run:
            return TransferResult(
                success=True,
                dry_run=True,
                direction="sync",
                timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
                source=source,
                destination=destination,
                files_transferred=len(needed),
                bytes_transferred=sum(src.stat().st_size for src, _dst in needed),
                warnings=("Dry run - sync not executed",),
            )
        copied_files, copied_bytes = _copy_mappings(needed, overwrite=True, verify=spec.verify, checksum=spec.checksum, progress_callback=progress_callback)
        if spec.delete:
            _delete_destination_only(source, destination, excludes=spec.excludes)
        return TransferResult(
            success=True,
            direction="sync",
            timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
            source=source,
            destination=destination,
            files_transferred=copied_files,
            bytes_transferred=copied_bytes,
            verification_passed=True if spec.verify else None,
            checksum_verified=True if spec.verify or spec.checksum else None,
        )


def _plan_transfer_mappings(spec: TransferSpec) -> list[tuple[Path, Path]]:
    if any(not isinstance(source, Path) for source in spec.sources) or not isinstance(spec.destination, Path):
        raise NotImplementedError("python-shutil transfer supports only local Path sources and destinations")
    destination = absolute_local_path(spec.destination)
    sources = tuple(absolute_local_path(source) for source in spec.sources if isinstance(source, Path))
    mappings: list[tuple[Path, Path]] = []
    multi = len(sources) > 1
    for source in sources:
        _validate_transfer_source(source)
        if source.is_file():
            if multi or destination.is_dir():
                target = destination / source.name
            else:
                target = destination
            mappings.append((source, target))
        elif source.is_dir():
            target_root = destination / source.name if (multi or destination.exists()) else destination
            for root, dirnames, filenames in os.walk(source, topdown=True, followlinks=False):
                current = Path(root)
                _validate_transfer_source(current)
                kept_dirs: list[str] = []
                for dirname in sorted(dirnames):
                    child = current / dirname
                    _validate_transfer_source(child)
                    kept_dirs.append(dirname)
                dirnames[:] = kept_dirs
                rel_dir = current.relative_to(source)
                for filename in sorted(filenames):
                    child = current / filename
                    _validate_transfer_source(child)
                    mappings.append((child, target_root / rel_dir / filename))
        else:  # pragma: no cover - guarded by _validate_transfer_source
            raise ValidationError(msg="Unsupported transfer source type", details={"source": str(source)})
    _validate_mapping_set(mappings, overwrite=spec.overwrite)
    return mappings


def _plan_sync_mappings(source: Path, destination: Path, *, excludes: tuple[str, ...]) -> list[tuple[Path, Path]]:
    _validate_transfer_source(source)
    destination = absolute_local_path(destination)
    mappings: list[tuple[Path, Path]] = []
    if source.is_file():
        if not _excluded(source.name, is_dir=False, patterns=excludes):
            mappings.append((source, destination / source.name if destination.exists() and destination.is_dir() else destination))
    elif source.is_dir():
        for root, dirnames, filenames in os.walk(source, topdown=True, followlinks=False):
            current = Path(root)
            rel_dir = current.relative_to(source)
            kept_dirs: list[str] = []
            for dirname in sorted(dirnames):
                rel = (rel_dir / dirname).as_posix()
                if _excluded(rel, is_dir=True, patterns=excludes):
                    continue
                child = current / dirname
                _validate_transfer_source(child)
                kept_dirs.append(dirname)
            dirnames[:] = kept_dirs
            for filename in sorted(filenames):
                rel = (rel_dir / filename).as_posix()
                if _excluded(rel, is_dir=False, patterns=excludes):
                    continue
                child = current / filename
                _validate_transfer_source(child)
                mappings.append((child, destination / rel))
    _validate_mapping_set(mappings, overwrite=True)
    return mappings


def _validate_transfer_source(path: Path) -> None:
    try:
        st = path.lstat()
    except FileNotFoundError as exc:
        raise NotFoundError(msg=f"Transfer source does not exist: {path}", details={"path": str(path)}) from exc
    mode = st.st_mode
    if stat.S_ISLNK(mode):
        raise ValidationError(msg="Transfer refuses symlink sources", details={"path": str(path)})
    if stat.S_ISREG(mode) or stat.S_ISDIR(mode):
        return
    kind = "fifo" if stat.S_ISFIFO(mode) else "socket" if stat.S_ISSOCK(mode) else "device" if (stat.S_ISCHR(mode) or stat.S_ISBLK(mode)) else "special"
    raise ValidationError(msg="Transfer refuses unsupported special files", details={"path": str(path), "kind": kind})


def _validate_mapping_set(mappings: list[tuple[Path, Path]], *, overwrite: bool) -> None:
    seen: dict[Path, Path] = {}
    for source, raw_target in mappings:
        target = absolute_local_path(raw_target)
        for other_source, other_target in mappings:
            if source != other_source and paths_overlap(source, other_target):
                raise ValidationError(msg="Transfer source overlaps a destination", details={"source": str(source), "destination": str(other_target)})
        previous = seen.get(target)
        if previous is not None and previous != source:
            raise ValidationError(msg="Multiple transfer sources map to the same destination", details={"destination": str(target), "first": str(previous), "second": str(source)})
        seen[target] = source
        _ensure_no_symlink_destination_path(target)
        if target.exists() and not overwrite:
            raise ValidationError(msg="Transfer destination already exists and overwrite is disabled", details={"destination": str(target)})


def _copy_mappings(
    mappings: list[tuple[Path, Path]],
    *,
    overwrite: bool,
    verify: bool,
    checksum: str | None,
    progress_callback: Callable[[TransferProgress], Any] | None,
) -> tuple[int, int]:
    total_files = len(mappings)
    total_bytes = sum(src.stat().st_size for src, _dst in mappings)
    done_files = 0
    done_bytes = 0
    for source, destination in mappings:
        destination = absolute_local_path(destination)
        _ensure_no_symlink_destination_path(destination)
        if destination.exists() and not overwrite:
            raise ValidationError(msg="Transfer destination already exists and overwrite is disabled", details={"destination": str(destination)})
        destination.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp_name = tempfile.mkstemp(prefix=f".{destination.name}.", suffix=".bkx-copy", dir=str(destination.parent))
        os.close(fd)
        tmp = Path(tmp_name)
        try:
            shutil.copy2(source, tmp, follow_symlinks=False)
            _fsync_file(tmp)
            if verify or checksum:
                _verify_copied_file(source, tmp, checksum=checksum)
            if destination.exists() and not overwrite:
                raise ValidationError(msg="Transfer destination already exists and overwrite is disabled", details={"destination": str(destination)})
            os.replace(tmp, destination)
        finally:
            tmp.unlink(missing_ok=True)
        done_files += 1
        done_bytes += source.stat().st_size
        if progress_callback:
            progress_callback(TransferProgress(current_file=source, files_transferred=done_files, bytes_transferred=done_bytes, total_files=total_files, total_bytes=total_bytes))
    return done_files, done_bytes


def _verify_copied_file(source: Path, destination: Path, *, checksum: str | None) -> None:
    algorithm = "sha256"
    expected: str | None = None
    if checksum:
        candidate = checksum.lower()
        if candidate in hashlib.algorithms_available:
            algorithm = candidate
        else:
            expected = candidate
    source_hash = _file_digest(source, algorithm)
    destination_hash = _file_digest(destination, algorithm)
    if source_hash != destination_hash:
        raise ValidationError(msg="Transfer verification failed", details={"source": str(source), "destination": str(destination), "algorithm": algorithm})
    if expected is not None and destination_hash.lower() != expected:
        raise ValidationError(msg="Transfer checksum did not match expected value", details={"destination": str(destination), "algorithm": algorithm})


def _same_file_content(first: Path, second: Path) -> bool:
    try:
        if first.stat().st_size != second.stat().st_size:
            return False
        return _file_digest(first, "sha256") == _file_digest(second, "sha256")
    except OSError:
        return False


def _file_digest(path: Path, algorithm: str) -> str:
    h = hashlib.new(algorithm)
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _ensure_no_symlink_destination_path(target: Path) -> None:
    current = target.anchor and Path(target.anchor) or Path("/")
    parts = target.parts[1:] if target.is_absolute() else target.parts
    if not target.is_absolute():
        current = Path.cwd()
    for part in parts:
        current = current / part
        if current.is_symlink():
            raise ValidationError(msg="Transfer refuses to write through a destination symlink", details={"path": str(current)})


def _delete_destination_only(source: Path, destination: Path, *, excludes: tuple[str, ...]) -> None:
    if not destination.exists():
        return
    if destination.is_symlink():
        raise ValidationError(msg="Sync refuses symlink destination", details={"destination": str(destination)})
    source_files = {dst for _src, dst in _plan_sync_mappings(source, destination, excludes=excludes)}
    for path in sorted(destination.rglob("*"), key=lambda p: len(p.parts), reverse=True):
        rel = path.relative_to(destination).as_posix()
        if _excluded(rel, is_dir=path.is_dir(), patterns=excludes):
            continue
        if path.is_symlink():
            raise ValidationError(msg="Sync refuses destination symlink", details={"path": str(path)})
        if path.is_file() and path not in source_files:
            path.unlink()
        elif path.is_dir():
            try:
                path.rmdir()
            except OSError:
                pass


def _excluded(rel: str, *, is_dir: bool, patterns: tuple[str, ...]) -> bool:
    target = rel.rstrip("/") + ("/" if is_dir else "")
    file_target = target.rstrip("/")
    return any(fnmatch.fnmatch(file_target, pattern) or fnmatch.fnmatch(target, pattern) for pattern in patterns)


def _fsync_file(path: Path) -> None:
    with open(path, "rb") as fh:
        os.fsync(fh.fileno())


def get_transfer() -> FallbackTransfer:
    return FallbackTransfer()
