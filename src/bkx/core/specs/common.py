"""Shared canonical validation and path/location normalization for Specs."""

from __future__ import annotations

import hashlib
import os
import re
from pathlib import Path, PurePosixPath
from urllib.parse import urlsplit

from bkx.core.errors import ValidationError

_WINDOWS_DRIVE_RE = re.compile(r"^[A-Za-z]:")


def require_tuple(value: object, *, field_name: str) -> tuple[object, ...]:
    if not isinstance(value, tuple):
        raise ValidationError(msg=f"{field_name} must be a tuple", details={"field": field_name, "type": type(value).__name__})
    return value


def require_path(value: object, *, field_name: str) -> Path:
    if not isinstance(value, Path):
        raise ValidationError(msg=f"{field_name} must be a Path", details={"field": field_name, "type": type(value).__name__})
    return value


def canonical_local_path(path: Path) -> Path:
    """Return an absolute normalized local path without requiring existence."""
    require_path(path, field_name="path")
    try:
        return path.expanduser().resolve(strict=False)
    except (OSError, RuntimeError) as exc:
        raise ValidationError(msg=f"Cannot normalize path: {path}", details={"path": str(path), "reason": str(exc)}) from exc


def normalize_patterns(patterns: tuple[str, ...], *, field_name: str) -> tuple[str, ...]:
    require_tuple(patterns, field_name=field_name)
    normalized: list[str] = []
    seen: set[str] = set()
    for value in patterns:
        if not isinstance(value, str):
            raise ValidationError(msg=f"{field_name} entries must be strings", details={"field": field_name, "type": type(value).__name__})
        item = value.strip()
        if not item:
            raise ValidationError(msg=f"{field_name} entries must not be empty", details={"field": field_name})
        if "\x00" in item:
            raise ValidationError(msg=f"{field_name} entries must not contain NUL", details={"field": field_name})
        if item not in seen:
            normalized.append(item)
            seen.add(item)
    return tuple(normalized)


def validate_pattern_sets(includes: tuple[str, ...], excludes: tuple[str, ...]) -> tuple[tuple[str, ...], tuple[str, ...]]:
    inc = normalize_patterns(includes, field_name="includes")
    exc = normalize_patterns(excludes, field_name="excludes")
    conflict = sorted(set(inc) & set(exc))
    if conflict:
        raise ValidationError(msg="Include and exclude patterns conflict", details={"patterns": conflict})
    return inc, exc


def normalize_archive_name(name: str) -> str:
    if not isinstance(name, str):
        raise ValidationError(msg="archive_name must be a string", details={"type": type(name).__name__})
    value = name.strip()
    if not value or value in {".", ".."} or "\x00" in value:
        raise ValidationError(msg="archive_name must be a non-empty file name", details={"archive_name": name})
    if "/" in value or "\\" in value or _WINDOWS_DRIVE_RE.match(value):
        raise ValidationError(msg="archive_name must not contain path components", details={"archive_name": name})
    return value


def normalize_archive_member(path: str) -> str:
    """Normalize a member selector and reject absolute/traversal spellings."""
    if not isinstance(path, str):
        raise ValidationError(msg="Archive member paths must be strings", details={"type": type(path).__name__})
    raw = path.strip().replace("\\", "/")
    directory_selector = raw.endswith("/")
    if not raw or "\x00" in raw or raw.startswith("/") or _WINDOWS_DRIVE_RE.match(raw):
        raise ValidationError(msg="Archive member path must be relative and non-empty", details={"path": path})
    while raw.startswith("./"):
        raw = raw[2:]
    parts = PurePosixPath(raw).parts
    if not parts or any(part in {"", ".", ".."} for part in parts):
        raise ValidationError(msg="Archive member path contains traversal or empty components", details={"path": path})
    normalized = "/".join(parts)
    return f"{normalized}/" if directory_selector else normalized


def contained_path(root: Path, member: str) -> Path:
    """Join a normalized archive member beneath root and prove lexical containment."""
    base = canonical_local_path(root)
    child = canonical_local_path(base.joinpath(*PurePosixPath(normalize_archive_member(member)).parts))
    if child != base and base not in child.parents:
        raise ValidationError(msg="Path escapes destination root", details={"root": str(base), "path": member})
    return child


def paths_overlap(first: Path, second: Path) -> bool:
    a = canonical_local_path(first)
    b = canonical_local_path(second)
    return a == b or a in b.parents or b in a.parents


def reject_output_inside_sources(output: Path, sources: tuple[Path, ...]) -> None:
    target = canonical_local_path(output)
    for source in sources:
        src = canonical_local_path(source)
        source_is_dir = src.exists() and src.is_dir()
        if src == target or (source_is_dir and src in target.parents):
            raise ValidationError(
                msg="Output/destination must not overlap a source tree",
                details={"source": str(src), "output": str(target)},
            )


def deterministic_name(prefix: str, *semantic_parts: str) -> str:
    safe = "".join(ch if ch.isalnum() or ch in "-_" else "_" for ch in prefix).strip("_") or "archive"
    material = "\x00".join(semantic_parts).encode("utf-8")
    digest = hashlib.sha256(material).hexdigest()[:12]
    return f"{safe}_{digest}"


def is_remote_location(value: str) -> bool:
    if not isinstance(value, str) or "://" not in value:
        return False
    parsed = urlsplit(value)
    return bool(parsed.scheme and (parsed.netloc or parsed.path))


TransferLocation = Path | str


def normalize_transfer_location(value: TransferLocation, *, field_name: str) -> TransferLocation:
    if isinstance(value, Path):
        return canonical_local_path(value)
    if not isinstance(value, str):
        raise ValidationError(msg=f"{field_name} must be a Path or URI string", details={"field": field_name, "type": type(value).__name__})
    raw = value.strip()
    if not raw or "\x00" in raw:
        raise ValidationError(msg=f"{field_name} must not be empty", details={"field": field_name})
    if is_remote_location(raw):
        parsed = urlsplit(raw)
        if not parsed.scheme:
            raise ValidationError(msg=f"{field_name} has an invalid URI", details={"field": field_name, "value": value})
        return raw
    return canonical_local_path(Path(raw))


def location_to_string(value: TransferLocation) -> str:
    return str(value)


def normalize_source_paths(sources: tuple[Path, ...], *, require_exists: bool = True) -> tuple[Path, ...]:
    require_tuple(sources, field_name="sources")
    if not sources:
        raise ValidationError(msg="At least one source path is required")
    normalized: list[Path] = []
    for source in sources:
        require_path(source, field_name="sources[]")
        src = canonical_local_path(source)
        if require_exists:
            if not src.exists():
                from bkx.core.errors import NotFoundError

                raise NotFoundError(msg=f"Source path does not exist: {src}", details={"path": str(src)})
            if not (src.is_file() or src.is_dir()):
                raise ValidationError(msg=f"Source path must be a regular file or directory: {src}", details={"path": str(src)})
        normalized.append(src)
    if len(set(normalized)) != len(normalized):
        raise ValidationError(msg="Duplicate source paths are not allowed", details={"sources": [str(p) for p in normalized]})
    return tuple(normalized)


def validate_split_size(value: int | None) -> int | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValidationError(msg="split_size must be a positive integer", details={"split_size": value})
    return value
