"""Shared source traversal manifest for hashing, archive creation, and transfers."""

from __future__ import annotations

import fnmatch
import os
import stat
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from bkx.core.errors import NotFoundError, ValidationError
from bkx.core.specs.common import canonical_local_path, normalize_archive_member, reject_output_inside_sources

SymlinkPolicy = Literal["reject", "skip", "preserve"]


@dataclass(frozen=True, kw_only=True)
class TraversalEntry:
    """One normalized source-tree entry."""

    source_root: Path
    path: Path
    archive_name: str
    is_dir: bool
    size: int
    mtime_ns: int
    is_symlink: bool = False


@dataclass(frozen=True, kw_only=True)
class SourceTraversalManifest:
    """Deterministic manifest shared by operations that walk user sources."""

    roots: tuple[Path, ...]
    entries: tuple[TraversalEntry, ...]

    @property
    def files(self) -> tuple[TraversalEntry, ...]:
        return tuple(entry for entry in self.entries if not entry.is_dir)

    @property
    def dirs(self) -> tuple[TraversalEntry, ...]:
        return tuple(entry for entry in self.entries if entry.is_dir)

    @property
    def total_size(self) -> int:
        return sum(entry.size for entry in self.files)


def build_source_manifest(
    sources: tuple[Path, ...],
    *,
    includes: tuple[str, ...] = (),
    excludes: tuple[str, ...] = (),
    output_path: Path | None = None,
    symlink_policy: SymlinkPolicy = "reject",
) -> SourceTraversalManifest:
    """Walk source roots once and return validated archive/member names.

    The manifest is intentionally conservative: symlinks and special files are
    rejected by default so hashing/archive/transfer cannot block on FIFOs or
    dereference links outside the selected tree. Callers that need to preserve
    links must opt into and implement that representation explicitly.
    """

    if symlink_policy not in {"reject", "skip", "preserve"}:
        raise ValidationError(msg="Unsupported symlink policy", details={"policy": symlink_policy})

    roots = tuple(canonical_local_path(src) for src in sources)
    if not roots:
        raise ValidationError(msg="At least one source path is required")
    if len(set(roots)) != len(roots):
        raise ValidationError(msg="Duplicate source paths are not allowed", details={"sources": [str(src) for src in roots]})
    for src in roots:
        if not src.exists():
            raise NotFoundError(msg=f"Source path does not exist: {src}", details={"path": str(src)})
        _validate_supported_source(src, symlink_policy="reject" if symlink_policy == "skip" else symlink_policy)
    if output_path is not None:
        reject_output_inside_sources(output_path, roots)

    entries: list[TraversalEntry] = []
    seen_names: dict[str, Path] = {}
    multiple_roots = len(roots) > 1

    for root in roots:
        if root.is_file():
            archive_name = _member_name(root.name)
            if _pattern_selected(archive_name, is_dir=False, includes=includes, excludes=excludes):
                _append_entry(entries, seen_names, _entry(root, root, archive_name, symlink_policy=symlink_policy))
            continue

        base_parent = root.parent if multiple_roots else root.parent
        # Include the root directory only when multiple roots could otherwise
        # collide; empty subdirs are included below.
        for dirpath, dirnames, filenames in os.walk(root, topdown=True, followlinks=False):
            current = Path(dirpath)
            _validate_supported_source(current, symlink_policy=symlink_policy)
            valid_dirs: list[str] = []
            for dirname in sorted(dirnames):
                child = current / dirname
                if symlink_policy == "skip" and child.is_symlink():
                    continue
                _validate_supported_source(child, symlink_policy=symlink_policy)
                valid_dirs.append(dirname)
            dirnames[:] = valid_dirs

            rel_dir = current.relative_to(base_parent)
            if rel_dir.parts and rel_dir != Path("."):
                dirname_member = _member_name(rel_dir.as_posix())
                if _pattern_selected(dirname_member + "/", is_dir=True, includes=includes, excludes=excludes):
                    _append_entry(entries, seen_names, _entry(root, current, dirname_member, is_dir=True, symlink_policy=symlink_policy))

            for fname in sorted(filenames):
                fpath = current / fname
                if symlink_policy == "skip" and fpath.is_symlink():
                    continue
                _validate_supported_source(fpath, symlink_policy=symlink_policy)
                rel = fpath.relative_to(base_parent)
                archive_name = _member_name(rel.as_posix())
                if _pattern_selected(archive_name, is_dir=False, includes=includes, excludes=excludes):
                    _append_entry(entries, seen_names, _entry(root, fpath, archive_name, symlink_policy=symlink_policy))

    return SourceTraversalManifest(roots=roots, entries=tuple(entries))


def _member_name(value: str) -> str:
    return normalize_archive_member(value).rstrip("/")


def _pattern_selected(member: str, *, is_dir: bool, includes: tuple[str, ...], excludes: tuple[str, ...]) -> bool:
    target = member.rstrip("/") + ("/" if is_dir else "")
    file_target = target.rstrip("/")
    if includes and not any(fnmatch.fnmatch(file_target, pat) or fnmatch.fnmatch(target, pat) for pat in includes):
        return False
    return not any(fnmatch.fnmatch(file_target, pat) or fnmatch.fnmatch(target, pat) for pat in excludes)


def _append_entry(entries: list[TraversalEntry], seen_names: dict[str, Path], entry: TraversalEntry) -> None:
    key = entry.archive_name.rstrip("/") + ("/" if entry.is_dir else "")
    previous = seen_names.get(key)
    if previous is not None and previous != entry.path:
        raise ValidationError(
            msg="Archive member name collision",
            details={"archive_name": key, "first": str(previous), "second": str(entry.path)},
        )
    seen_names[key] = entry.path
    entries.append(entry)


def _entry(root: Path, path: Path, archive_name: str, *, symlink_policy: SymlinkPolicy, is_dir: bool | None = None) -> TraversalEntry:
    st = path.lstat()
    if is_dir is None:
        is_dir = stat.S_ISDIR(st.st_mode)
    return TraversalEntry(
        source_root=root,
        path=path,
        archive_name=archive_name,
        is_dir=is_dir,
        size=0 if is_dir else st.st_size,
        mtime_ns=st.st_mtime_ns,
        is_symlink=path.is_symlink(),
    )


def _validate_supported_source(path: Path, *, symlink_policy: SymlinkPolicy) -> None:
    try:
        st = path.lstat()
    except FileNotFoundError as exc:
        raise NotFoundError(msg=f"Source path disappeared during traversal: {path}", details={"path": str(path)}) from exc
    mode = st.st_mode
    if stat.S_ISLNK(mode):
        if symlink_policy == "reject":
            raise ValidationError(msg="Symlink sources are not supported by this operation", details={"path": str(path)})
        return
    if stat.S_ISREG(mode) or stat.S_ISDIR(mode):
        return
    kind = "fifo" if stat.S_ISFIFO(mode) else "socket" if stat.S_ISSOCK(mode) else "device" if (stat.S_ISCHR(mode) or stat.S_ISBLK(mode)) else "special"
    raise ValidationError(msg="Unsupported special file in source tree", details={"path": str(path), "kind": kind})
