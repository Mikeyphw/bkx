"""JSON boundary used by the embedded BKX Android backend."""

from __future__ import annotations

import hashlib
import json
import os
import stat as stat_module
import sys
import time
from dataclasses import asdict, is_dataclass
from datetime import datetime
from importlib import metadata
from pathlib import Path
from threading import RLock
from typing import Any, Callable, Mapping

from bkx.core.errors import (
    BkxError,
    ExternalToolError,
    NotFoundError,
    PermissionDeniedError,
    ValidationError,
)
from bkx.core.results.execution_plan import plan_for_spec
from bkx.core.services.factory import (
    get_archive_service,
    get_backup_service,
    get_restore_service,
    get_run_service,
    get_transfer_service,
)
from bkx.core.specs.archive import ArchiveBrowseSpec, ArchiveInfoSpec, ArchiveVerifySpec
from bkx.core.specs.backup import BackupSpec, CompressionProfile
from bkx.core.specs.restore import RestoreSpec
from bkx.core.specs.transfer import TransferSpec, TransferSyncSpec

PROTOCOL_VERSION = 1
_RUNTIME = "embedded-cpython"
_CAPABILITIES = frozenset(
    {
        "readiness.probe",
        "backup.plan",
        "backup.create",
        "archive.list",
        "archive.info",
        "archive.browse",
        "archive.verify",
        "restore.preview",
        "restore.extract",
        "transfer.plan",
        "transfer.run",
        "runs.list",
        "runs.show",
        "run.cancel.cooperative",
        "run.progress",
        "storage.locations",
        "filesystem.list",
        "filesystem.stat",
    }
)

_cancelled_requests: set[str] = set()
_progress_documents: dict[str, dict[str, Any]] = {}
_progress_sequences: dict[str, int] = {}
_cancel_lock = RLock()
_progress_lock = RLock()


class AndroidOperationCancelled(BkxError):
    """Internal typed cancellation signal raised only at cooperative checkpoints."""


def _version() -> str:
    try:
        return metadata.version("bkx")
    except metadata.PackageNotFoundError:
        return "0.0.1-dev"


def _is_root_runtime() -> bool:
    geteuid = getattr(os, "geteuid", None)
    if not callable(geteuid):
        return False
    try:
        return int(geteuid()) == 0
    except OSError:
        return False


def _runtime_capabilities() -> set[str]:
    capabilities = set(_CAPABILITIES)
    capabilities.add("filesystem.direct")
    if _is_root_runtime():
        capabilities.update({"root.filesystem", "storage.termux.private", "storage.app_private"})
    return capabilities


def supported_operations() -> tuple[str, ...]:
    """Return the immutable operation contract advertised to Android."""
    return tuple(sorted(_CAPABILITIES - {"readiness.probe", "run.cancel.cooperative", "run.progress"}))


def readiness_json() -> str:
    return _json(
        {
            "protocol_version": PROTOCOL_VERSION,
            "backend_version": _version(),
            "runtime": _RUNTIME,
            "python_version": f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}",
            "capabilities": sorted(_runtime_capabilities()),
        }
    )


def progress_json(request_id: str) -> str:
    request_id = _require_request_id(request_id)
    with _progress_lock:
        document = _progress_documents.get(request_id)
        return "null" if document is None else _json(document)


def cancel_json(request_id: str) -> str:
    request_id = _require_request_id(request_id)
    with _cancel_lock:
        _cancelled_requests.add(request_id)
    return _json(
        _success(
            request_id,
            "Cancellation recorded for the embedded operation.",
        )
    )


def execute_json(raw_request: str) -> str:
    """Execute one bridge-shaped request synchronously inside the current process."""
    request_id = "unknown"
    result: dict[str, Any]
    started = time.monotonic()
    try:
        request = _parse_request(raw_request)
        request_id = request["request_id"]
        _emit_progress(request_id, "preparing", "Preparing embedded BKX operation.", 0.0)
        _cancellation_checkpoint(request_id, "preparing")
        operation = request["operation"]
        handler = _HANDLERS.get(operation)
        if handler is None:
            raise ValidationError(
                msg=f"Unsupported embedded Android operation: {operation}",
                details={"operation": operation, "supported": sorted(_HANDLERS)},
            )
        result = handler(request_id, request["payload"])
    except AndroidOperationCancelled as exc:
        result = _cancelled(request_id, str(exc) or "Operation cancelled.")
    except BkxError as exc:
        result = _failure(request_id, exc)
    except (TypeError, ValueError, KeyError, json.JSONDecodeError) as exc:
        error = ValidationError(msg=f"Invalid embedded Android request: {exc}")
        result = _failure(request_id, error)
    except Exception as exc:  # boundary: never leak a Python traceback through the app protocol
        error = ExternalToolError(
            msg=f"Embedded BKX runtime failed: {type(exc).__name__}: {exc}",
            tool_name="embedded-cpython",
        )
        result = _failure(request_id, error, code="embedded_runtime_error")

    duration_ms = max(0, int((time.monotonic() - started) * 1000))
    result["metrics"] = {"duration_ms": duration_ms}
    if request_id != "unknown":
        status = str(result.get("status") or "failure")
        final_progress = 1.0 if status == "success" else None
        _emit_progress(request_id, status, str(result.get("summary") or status), final_progress)
        _discard_cancel(request_id)
        _trim_runtime_maps()
    return _json(result)



def _emit_progress(request_id: str, phase: str, message: str, progress: float | None = None) -> None:
    with _progress_lock:
        seq = _progress_sequences.get(request_id, 0) + 1
        _progress_sequences[request_id] = seq
        _progress_documents[request_id] = {
            "protocol_version": PROTOCOL_VERSION,
            "request_id": request_id,
            "seq": seq,
            "phase": phase,
            "message": message,
            **({"progress": max(0.0, min(float(progress), 1.0))} if progress is not None else {}),
        }


def _is_cancelled(request_id: str) -> bool:
    with _cancel_lock:
        return request_id in _cancelled_requests


def _cancellation_checkpoint(request_id: str, phase: str) -> None:
    if _is_cancelled(request_id):
        raise AndroidOperationCancelled(msg=f"Operation cancelled during {phase}.")


def _discard_cancel(request_id: str) -> None:
    with _cancel_lock:
        _cancelled_requests.discard(request_id)


def _trim_runtime_maps(limit: int = 128) -> None:
    with _progress_lock:
        while len(_progress_documents) > limit:
            oldest = next(iter(_progress_documents))
            _progress_documents.pop(oldest, None)
            _progress_sequences.pop(oldest, None)

def _parse_request(raw_request: str) -> dict[str, Any]:
    if not isinstance(raw_request, str) or not raw_request.strip():
        raise ValidationError(msg="Embedded request JSON must be a non-empty string")
    document = json.loads(raw_request)
    if not isinstance(document, dict):
        raise ValidationError(msg="Embedded request root must be an object")
    protocol = document.get("protocol_version")
    if protocol != PROTOCOL_VERSION:
        raise ValidationError(
            msg=f"Unsupported bridge protocol version {protocol}; expected {PROTOCOL_VERSION}",
            details={"expected": PROTOCOL_VERSION, "actual": protocol},
        )
    request_id = _require_request_id(document.get("request_id"))
    operation = document.get("operation")
    if not isinstance(operation, str) or not operation.strip():
        raise ValidationError(msg="operation must be a non-empty string")
    payload = document.get("payload", {})
    if not isinstance(payload, dict):
        raise ValidationError(msg="payload must be an object")
    return {
        "protocol_version": protocol,
        "request_id": request_id,
        "operation": operation.strip(),
        "payload": payload,
    }


def _require_request_id(value: Any) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValidationError(msg="request_id must be a non-empty string")
    request_id = value.strip()
    if any(ch not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-" for ch in request_id):
        raise ValidationError(msg="request_id must be file-name safe")
    return request_id


def _backup_spec(payload: Mapping[str, Any], *, dry_run: bool) -> BackupSpec:
    source = _mapping(payload, "source")
    destination = _mapping(payload, "destination")
    filters = _mapping(payload, "filters", default={})
    archive = _mapping(payload, "archive", default={})
    split = _mapping(archive, "split", default={})
    verification = _mapping(archive, "verification", default={})
    compression = str(archive.get("compression") or "gzip").strip().lower()
    # Android's first embedded lane intentionally uses a stdlib-supported default.
    # zstd/lz4 remain valid BKX values, but the pure fallback archiver reports the
    # typed unsupported-backend error if execution requires unavailable tooling.
    level = int(archive.get("compression_level", 3))
    split_size = int(split["chunk_size_bytes"]) if split.get("enabled") and split.get("chunk_size_bytes") is not None else None
    if bool(filters.get("follow_symlinks", False)):
        raise ValidationError(msg="BKX Android does not follow symbolic links; root backups skip links to stay inside the selected source tree")
    return BackupSpec(
        sources=(Path(_string_alias(source, "path", "staged_path")),),
        destination=Path(_string_alias(destination, "path", "staged_directory_path")),
        archive_name=_optional_string(archive.get("archive_name")),
        excludes=_string_tuple(filters.get("exclude_patterns", [])),
        includes=_string_tuple(filters.get("include_patterns", [])),
        split_size=split_size,
        compression=CompressionProfile(compression, level),
        verify=bool(verification.get("verify_after_create", True)),
        dry_run=dry_run,
        skip_symlinks=True,
    )


def _handle_backup_plan(request_id: str, payload: Mapping[str, Any]) -> dict[str, Any]:
    _emit_progress(request_id, "planning", "Reviewing backup plan.", 0.15)
    _cancellation_checkpoint(request_id, "backup planning")
    spec = _backup_spec(payload, dry_run=True)
    plan = plan_for_spec(spec, command="backup.plan")
    token = _token(plan)
    warnings = [str(item) for item in plan.warnings]
    return _success(
        request_id,
        f"Backup plan ready: {plan.total_files if plan.total_files is not None else '?'} files, {plan.step_count} steps.",
        errors=[_warning(code="plan_warning", message=w) for w in warnings],
        artifacts=[_artifact("backup_plan", f"embedded://plan/{request_id}", token)],
    )


def _handle_backup_create(request_id: str, payload: Mapping[str, Any]) -> dict[str, Any]:
    spec = _backup_spec(payload, dry_run=False)
    plan = plan_for_spec(spec, command="backup.create")
    total_files = max(0, int(plan.total_files or 0))

    def progress(phase: str, files_done: int, bytes_done: int) -> None:
        _cancellation_checkpoint(request_id, phase)
        fraction = (files_done / total_files) if total_files > 0 else None
        _emit_progress(
            request_id,
            phase,
            f"{phase.replace('_', ' ').title()}: {files_done} files, {bytes_done} bytes.",
            fraction,
        )

    _emit_progress(request_id, "archiving", "Creating backup archive.", 0.05)
    result = get_backup_service().create(spec, progress_callback=progress)
    _cancellation_checkpoint(request_id, "backup finalization")
    errors = [_error("backup_error", message) for message in result.errors]
    errors.extend(_warning("backup_warning", message) for message in result.warnings)
    artifacts = []
    for path in result.archive_paths:
        digest = result.checksums.get(path.name) or result.checksums.get("archive")
        artifacts.append(_artifact("backup_archive", str(path), digest))
    status = "success" if result.success else "failure"
    return _result(
        request_id,
        status,
        f"Backup {'completed' if result.success else 'failed'}: {result.files_processed} files, {result.bytes_written} bytes written.",
        errors=errors,
        artifacts=artifacts,
    )


def _archive_path(payload: Mapping[str, Any]) -> Path:
    archive = _mapping(payload, "archive")
    return Path(_string_alias(archive, "path", "staged_path"))


def _handle_archive_list(request_id: str, payload: Mapping[str, Any]) -> dict[str, Any]:
    """Build a bounded direct-filesystem archive index.

    Android supplies user-visible storage roots (internal/removable volumes and
    the BKX backup directory). Canonical run history is merged as a second
    source so archives created in arbitrary explicit destinations remain
    discoverable. Symlinks are never followed while indexing.
    """
    roots_raw = payload.get("roots", [])
    if roots_raw is None:
        roots_raw = []
    if not isinstance(roots_raw, list) or any(not isinstance(item, str) for item in roots_raw):
        raise ValidationError(msg="archive.list roots must be a list of absolute path strings")
    max_depth = max(0, min(int(payload.get("max_depth", 4)), 8))
    limit = max(1, min(int(payload.get("limit", 500)), 2000))

    seen: set[str] = set()
    artifacts: list[dict[str, Any]] = []

    def add_archive(path: Path) -> None:
        if len(artifacts) >= limit:
            return
        try:
            if path.is_symlink() or not path.is_file() or not _looks_like_archive(path):
                return
            resolved = str(path.absolute())
            if resolved in seen:
                return
            stat = path.stat()
        except (OSError, PermissionError):
            return
        seen.add(resolved)
        artifacts.append(
            _artifact(
                "archive",
                resolved,
                size_bytes=stat.st_size,
                modified_at_epoch_millis=max(0, int(stat.st_mtime * 1000)),
            )
        )

    for raw_root in roots_raw:
        if len(artifacts) >= limit:
            break
        root = Path(raw_root).expanduser()
        if not root.is_absolute():
            continue
        _scan_archive_root(root, max_depth=max_depth, limit=limit, add_archive=add_archive)

    # Always merge canonical run history. This catches backups written to
    # arbitrary direct paths outside the standard BKX volume roots.
    run_result = get_run_service().list(limit=min(limit, 200))
    for run in run_result.runs:
        if len(artifacts) >= limit:
            break
        try:
            detail = get_run_service().show(run.run_id)
        except BkxError:
            continue
        if detail.archive_path:
            add_archive(Path(detail.archive_path))

    artifacts.sort(
        key=lambda item: (
            -int(item.get("modified_at_epoch_millis", 0)),
            str(item.get("path", "")).casefold(),
        )
    )
    return _success(
        request_id,
        f"Indexed {len(artifacts)} BKX-compatible archives from direct storage and run history.",
        artifacts=artifacts,
    )


def _looks_like_archive(path: Path) -> bool:
    name = path.name.casefold()
    return name.endswith((
        ".bakery", ".zip", ".tar", ".tar.gz", ".tgz",
        ".tar.bz2", ".tbz2", ".tar.xz", ".txz",
    ))


def _scan_archive_root(
    root: Path,
    *,
    max_depth: int,
    limit: int,
    add_archive: Callable[[Path], None],
) -> None:
    try:
        if root.is_symlink() or not root.exists():
            return
        if root.is_file():
            add_archive(root)
            return
    except (OSError, PermissionError):
        return

    stack: list[tuple[Path, int]] = [(root, 0)]
    visited = 0
    while stack and visited < max(limit * 20, 1000):
        directory, depth = stack.pop()
        try:
            entries = list(os.scandir(directory))
        except (OSError, PermissionError):
            continue
        for entry in entries:
            visited += 1
            if visited >= max(limit * 20, 1000):
                break
            try:
                if entry.is_symlink():
                    continue
                candidate = Path(entry.path)
                if entry.is_file(follow_symlinks=False):
                    add_archive(candidate)
                elif depth < max_depth and entry.is_dir(follow_symlinks=False):
                    stack.append((candidate, depth + 1))
            except (OSError, PermissionError):
                continue


def _handle_archive_info(request_id: str, payload: Mapping[str, Any]) -> dict[str, Any]:
    result = get_archive_service().info(ArchiveInfoSpec(archive_path=_archive_path(payload)))
    errors = [_warning("archive_warning", item) for item in result.warnings]
    errors.extend(_error("archive_error", item) for item in result.errors)
    return _result(
        request_id,
        "success" if result.success else "failure",
        f"Archive: {result.total_entries} entries, {result.uncompressed_size} uncompressed bytes, format {result.format}.",
        errors=errors,
        artifacts=[_artifact("archive", str(result.archive_path))],
    )


def _handle_archive_browse(request_id: str, payload: Mapping[str, Any]) -> dict[str, Any]:
    page = _mapping(payload, "page", default={})
    page_size = int(page.get("page_size", 100))
    token = page.get("page_token")
    offset = int(token) if token not in (None, "") else 0
    result = get_archive_service().browse(
        ArchiveBrowseSpec(archive_path=_archive_path(payload), limit=page_size, offset=offset)
    )
    artifacts = [
        _artifact(
            {
                "directory": "archive_entry_dir",
                "symlink": "archive_entry_symlink",
                "special": "archive_entry_special",
            }.get(entry.entry_type or ("directory" if entry.is_dir else "file"), "archive_entry"),
            entry.path,
            size_bytes=entry.size,
            modified_at_epoch_millis=max(0, entry.mtime_ns // 1_000_000),
        )
        for entry in result.entries
    ]
    next_offset = result.offset + len(result.entries)
    if next_offset < result.total_matches:
        artifacts.append(_artifact("archive_next_page", f"embedded://archive/page/{next_offset}"))
    return _success(
        request_id,
        f"Archive page contains {len(result.entries)} of {result.total_matches} matching entries ({result.total_size} bytes).",
        artifacts=artifacts,
    )


def _handle_archive_verify(request_id: str, payload: Mapping[str, Any]) -> dict[str, Any]:
    archive_path = _archive_path(payload)
    info = get_archive_service().info(ArchiveInfoSpec(archive_path=archive_path))
    total_entries = max(0, int(info.total_entries))

    def progress(entries_done: int, bytes_done: int) -> None:
        _cancellation_checkpoint(request_id, "archive verification")
        fraction = (entries_done / total_entries) if total_entries > 0 else None
        _emit_progress(request_id, "verifying", f"Verified {entries_done} of {total_entries or '?'} entries ({bytes_done} bytes).", fraction)

    _emit_progress(request_id, "verifying", "Verifying archive structure and contents.", 0.1)
    _cancellation_checkpoint(request_id, "archive verification")
    result = get_archive_service().verify(ArchiveVerifySpec(archive_path=archive_path), progress_callback=progress)
    errors = [_warning("archive_verify_warning", item) for item in result.warnings]
    errors.extend(_error("archive_verify_error", item) for item in result.errors)
    for entry in result.failed_entries:
        errors.append(_error("archive_verification_entry_failed", entry))
    digest = result.archive_checksums.get("sha256")
    status = "success" if result.success and result.structure_valid is not False and result.checksums_valid is not False else "failure"
    return _result(
        request_id,
        status,
        f"Archive verification {'passed' if status == 'success' else 'failed'}; checked {result.entries_checked} entries.",
        errors=errors,
        artifacts=[_artifact("archive_verification", str(result.archive_path), digest)],
    )


def _restore_spec(payload: Mapping[str, Any], *, dry_run: bool) -> RestoreSpec:
    destination_path = payload.get("destination_path")
    if destination_path is None:
        destination = payload.get("destination")
        if isinstance(destination, dict):
            destination_path = destination.get("staged_directory_path")
    destination = Path(destination_path) if isinstance(destination_path, str) and destination_path else None
    if destination is not None:
        _validate_android_restore_destination(destination, payload, dry_run=dry_run)
    return RestoreSpec(
        archive=Path(_string(payload, "archive")),
        destination=destination,
        paths=_string_tuple(payload.get("paths", [])),
        overwrite=bool(payload.get("overwrite", False)),
        verify=bool(payload.get("verify", True)),
        dry_run=dry_run,
        fresh_dir=bool(payload.get("fresh_dir", True)),
        confirm_overwrite=bool(payload.get("confirm_overwrite", False)),
    )



def _restore_destination_writable_by_current_process(destination: Path) -> bool:
    """Return whether this process can restore there without elevating to root.

    For a fresh destination, walk upward until an existing ancestor is found;
    creating the requested directory only requires write/search permission on
    that ancestor. Existing restore targets themselves must be writable and
    searchable. Permission probing is intentionally capability-based rather
    than package-name-based so this works for BKX's own app-private directory
    and for host parity execution under Termux without weakening access to
    other apps' private trees.
    """

    candidate = destination
    while not candidate.exists():
        parent = candidate.parent
        if parent == candidate:
            return False
        candidate = parent
    return candidate.is_dir() and os.access(candidate, os.W_OK | os.X_OK)


def _validate_android_restore_destination(
    destination: Path,
    payload: Mapping[str, Any],
    *,
    dry_run: bool,
) -> None:
    normalized = os.path.normpath(str(destination))
    fresh_dir = bool(payload.get("fresh_dir", True))
    if fresh_dir and destination.exists():
        raise ValidationError(
            msg="New-folder restore requires a destination path which does not already exist.",
            details={"destination": normalized},
        )
    if not fresh_dir and (not destination.exists() or not destination.is_dir()):
        raise ValidationError(
            msg="Existing-folder restore requires an existing directory destination.",
            details={"destination": normalized},
        )
    blocked = (
        "/", "/proc", "/sys", "/dev", "/system", "/vendor", "/product", "/apex", "/metadata",
        "/data/system", "/data/misc", "/data/vendor", "/data/property",
    )
    for root in blocked:
        if normalized == root or (root != "/" and normalized.startswith(root + "/")):
            raise ValidationError(
                msg=f"BKX refuses restore writes into protected Android system root {root}",
                details={"destination": normalized, "blocked_root": root},
            )

    privileged = ("/data/data", "/data/user", "/data/adb")
    root_sensitive = any(normalized == root or normalized.startswith(root + "/") for root in privileged)
    if not root_sensitive:
        return
    # Android app-private paths are generally root-sensitive, but the current
    # process may legitimately own one of them. This matters both for BKX's
    # own private files and for the host-side parity seal when it runs inside
    # Termux, whose temporary directory lives below /data/data/com.termux.
    # Require root only when the current process cannot already traverse and
    # write the destination (or the nearest existing parent for a new folder).
    is_root_runtime = _is_root_runtime()
    writable_without_root = (
        not is_root_runtime and _restore_destination_writable_by_current_process(destination)
    )
    if not is_root_runtime and not writable_without_root:
        raise PermissionDeniedError(
            msg="This restore destination requires BKX root execution.",
            details={"destination": normalized},
        )
    if is_root_runtime and not dry_run and payload.get("root_confirmation") != "RESTORE ROOT":
        raise ValidationError(
            msg="Root-sensitive restore requires the exact RESTORE ROOT confirmation.",
            details={"destination": normalized},
        )

def _handle_restore_preview(request_id: str, payload: Mapping[str, Any]) -> dict[str, Any]:
    _emit_progress(request_id, "planning", "Reviewing restore safety and collisions.", 0.1)
    _cancellation_checkpoint(request_id, "restore preview")
    spec = _restore_spec(payload, dry_run=True)
    result = get_restore_service().preview(spec)
    plan = plan_for_spec(spec, command="restore.preview")
    token = _token(plan)
    errors = [_warning("restore_warning", item) for item in result.warnings]
    if result.existing_files:
        errors.append(
            _warning(
                "overwrite_collision",
                f"{result.existing_files} selected restore targets already exist.",
            )
        )
    artifacts = [_artifact("restore_plan", f"embedded://plan/{request_id}", token)]
    if spec.verify:
        verify = get_archive_service().verify(ArchiveVerifySpec(archive_path=spec.archive))
        digest = verify.archive_checksums.get("sha256")
        artifacts.append(_artifact("archive_verification", str(spec.archive), digest))
        if not verify.success:
            errors.append(_error("archive_verification_failed", "Archive verification failed during restore preview."))
    status = "success" if result.success and not any(e["code"] == "archive_verification_failed" for e in errors) else "failure"
    return _result(
        request_id,
        status,
        f"Restore preview: {result.total_files} files, {result.total_size} bytes; {result.existing_files} existing targets.",
        errors=errors,
        artifacts=artifacts,
    )


def _handle_restore_extract(request_id: str, payload: Mapping[str, Any]) -> dict[str, Any]:
    spec = _restore_spec(payload, dry_run=False)
    preview = get_restore_service().preview(spec)
    total_files = max(0, int(preview.total_files))

    def progress(phase: str, files_done: int, bytes_done: int) -> None:
        _cancellation_checkpoint(request_id, phase)
        fraction = (files_done / total_files) if total_files > 0 else None
        if phase == "verifying" and fraction is not None:
            fraction *= 0.4
        elif phase == "extracting" and fraction is not None:
            fraction = 0.4 + (fraction * 0.55)
        _emit_progress(request_id, phase, f"{phase.title()}: {files_done} of {total_files or '?'} files ({bytes_done} bytes).", fraction)

    _emit_progress(request_id, "verifying", "Checking restore archive before writing files.", 0.05)
    _cancellation_checkpoint(request_id, "restore verification")
    result = get_restore_service().extract(spec, progress_callback=progress)
    _cancellation_checkpoint(request_id, "restore publication")
    _emit_progress(request_id, "publishing", "Restore files published safely.", 0.95)
    errors = [_warning("restore_warning", item) for item in result.warnings]
    errors.extend(_error("restore_error", item) for item in result.errors)
    artifacts = [_artifact("restored_path", str(path)) for path in result.restored_paths]
    return _result(
        request_id,
        "success" if result.success else "failure",
        f"Restore {'completed' if result.success else 'failed'}: {result.files_restored} files, {result.bytes_restored} bytes.",
        errors=errors,
        artifacts=artifacts,
    )


def _transfer_spec(payload: Mapping[str, Any], *, dry_run: bool) -> TransferSpec | TransferSyncSpec:
    sources_raw = payload.get("sources")
    if not isinstance(sources_raw, list) or not sources_raw:
        raise ValidationError(msg="transfer sources must be a non-empty list")
    destination = _mapping(payload, "destination")
    mode = str(payload.get("mode") or payload.get("direction") or "push").strip().lower()
    source_locations = tuple(_location(item, "source") for item in sources_raw)
    destination_location = _location(destination, "destination")
    verify = bool(payload.get("verify", True))
    checksum = _optional_string(payload.get("checksum"))
    overwrite = bool(payload.get("overwrite", False))
    if mode == "sync":
        if len(source_locations) != 1 or not isinstance(source_locations[0], Path) or not isinstance(destination_location, Path):
            raise ValidationError(msg="Embedded sync currently requires one local source and one local destination")
        return TransferSyncSpec(
            source=source_locations[0],
            destination=destination_location,
            delete=bool(payload.get("delete_extraneous_on_sync", False)),
            verify=verify,
            checksum=checksum,
            dry_run=dry_run,
            overwrite=overwrite,
        )
    if mode not in {"push", "pull"}:
        raise ValidationError(msg=f"Invalid transfer mode: {mode}")
    return TransferSpec(
        sources=source_locations,
        destination=destination_location,
        direction=mode,
        verify=verify,
        checksum=checksum,
        dry_run=dry_run,
        overwrite=overwrite,
    )


def _location(document: Any, label: str) -> Path | str:
    if not isinstance(document, dict):
        raise ValidationError(msg=f"{label} endpoint must be an object")
    value = _string(document, "location")
    return value if bool(document.get("remote", False)) else Path(value)


def _handle_transfer_plan(request_id: str, payload: Mapping[str, Any]) -> dict[str, Any]:
    spec = _transfer_spec(payload, dry_run=True)
    plan = plan_for_spec(spec, command="transfer.plan")
    token = _token(plan)
    return _success(
        request_id,
        f"Transfer plan ready: {plan.total_files if plan.total_files is not None else '?'} files, {plan.step_count} steps.",
        errors=[_warning("plan_warning", item) for item in plan.warnings],
        artifacts=[_artifact("transfer_plan", f"embedded://plan/{request_id}", token)],
    )


def _handle_transfer_run(request_id: str, payload: Mapping[str, Any]) -> dict[str, Any]:
    spec = _transfer_spec(payload, dry_run=False)
    service = get_transfer_service()

    def progress(event: Any) -> None:
        _cancellation_checkpoint(request_id, "transfer")
        total = int(getattr(event, "total_files", 0) or 0)
        done = int(getattr(event, "files_transferred", 0) or 0)
        fraction = (done / total) if total > 0 else None
        _emit_progress(request_id, "transferring", f"Transferred {done} of {total or '?'} files.", fraction)

    _emit_progress(request_id, "transferring", "Starting local transfer.", 0.02)
    if isinstance(spec, TransferSyncSpec):
        result = service.sync(spec, progress_callback=progress)
    elif spec.direction == "pull":
        result = service.pull(spec, progress_callback=progress)
    else:
        result = service.push(spec, progress_callback=progress)
    _cancellation_checkpoint(request_id, "transfer finalization")
    errors = [_warning("transfer_warning", item) for item in result.warnings]
    errors.extend(_error("transfer_error", item) for item in result.errors)
    if result.verification_passed is False or result.checksum_verified is False:
        errors.append(_error("transfer_verification_failed", "Transfer verification did not pass."))
    status = "success" if result.success and result.verification_passed is not False and result.checksum_verified is not False else "failure"
    return _result(
        request_id,
        status,
        f"Transfer {'completed' if status == 'success' else 'failed'}: {result.files_transferred} files, {result.bytes_transferred} bytes.",
        errors=errors,
    )


def _handle_storage_locations(request_id: str, payload: Mapping[str, Any]) -> dict[str, Any]:
    candidates = [
        ("shared-storage", Path("/storage/emulated/0")),
        ("termux-files", Path("/data/data/com.termux/files")),
        ("termux-home", Path("/data/data/com.termux/files/home")),
        ("termux-storage", Path("/data/data/com.termux/files/home/storage")),
        ("termux-prefix", Path("/data/data/com.termux/files/usr")),
        ("data-adb", Path("/data/adb")),
        ("app-data", Path("/data/user/0")),
        ("removable-storage", Path("/mnt/media_rw")),
    ]
    artifacts: list[dict[str, Any]] = []
    for label, path in candidates:
        try:
            if path.exists():
                artifacts.append(_artifact(label, str(path)))
        except PermissionError:
            continue
    privilege = "root" if _is_root_runtime() else "app"
    return _success(
        request_id,
        f"Discovered {len(artifacts)} direct filesystem locations with {privilege} privileges.",
        artifacts=artifacts,
    )


def _handle_filesystem_list(request_id: str, payload: Mapping[str, Any]) -> dict[str, Any]:
    path = Path(_string(payload, "path")).expanduser()
    show_hidden = bool(payload.get("show_hidden", True))
    limit = max(1, min(int(payload.get("limit", 250)), 1000))
    offset = max(0, int(payload.get("offset", 0)))
    try:
        if not path.exists():
            raise NotFoundError(msg=f"Path not found: {path}", details={"path": str(path)})
        if not path.is_dir():
            raise ValidationError(msg=f"Path is not a directory: {path}", details={"path": str(path)})
        entries = sorted(path.iterdir(), key=lambda item: (not item.is_dir(), item.name.casefold(), item.name))
    except PermissionError as exc:
        raise PermissionDeniedError(msg=f"Permission denied while listing {path}", details={"path": str(path)}) from exc
    if not show_hidden:
        entries = [entry for entry in entries if not entry.name.startswith(".")]
    page = entries[offset : offset + limit]
    artifacts: list[dict[str, Any]] = []
    for entry in page:
        try:
            mode = entry.lstat().st_mode
            label = (
                "directory" if stat_module.S_ISDIR(mode) else
                "symlink" if stat_module.S_ISLNK(mode) else
                "file" if stat_module.S_ISREG(mode) else
                "special"
            )
        except OSError:
            label = "unreadable"
        try:
            stat = entry.lstat()
            artifacts.append(
                _artifact(
                    label,
                    str(entry),
                    size_bytes=max(0, int(stat.st_size)),
                    modified_at_epoch_millis=max(0, int(stat.st_mtime * 1000)),
                )
            )
        except OSError:
            artifacts.append(_artifact(label, str(entry)))
    more = offset + len(page) < len(entries)
    suffix = " More entries are available." if more else ""
    return _success(
        request_id,
        f"Listed {len(page)} of {len(entries)} entries in {path}.{suffix}",
        artifacts=artifacts,
    )


def _handle_filesystem_stat(request_id: str, payload: Mapping[str, Any]) -> dict[str, Any]:
    path = Path(_string(payload, "path")).expanduser()
    try:
        info = path.lstat()
    except FileNotFoundError as exc:
        raise NotFoundError(msg=f"Path not found: {path}", details={"path": str(path)}) from exc
    except PermissionError as exc:
        raise PermissionDeniedError(msg=f"Permission denied while inspecting {path}", details={"path": str(path)}) from exc
    mode = info.st_mode
    kind = (
        "directory" if stat_module.S_ISDIR(mode) else
        "symlink" if stat_module.S_ISLNK(mode) else
        "file" if stat_module.S_ISREG(mode) else
        "special"
    )
    return _success(
        request_id,
        f"{path}: {kind}, {info.st_size} bytes, mode {stat_module.S_IMODE(mode):04o}, uid {info.st_uid}, gid {info.st_gid}.",
        artifacts=[
            _artifact(
                kind,
                str(path),
                size_bytes=max(0, int(info.st_size)),
                modified_at_epoch_millis=max(0, int(info.st_mtime * 1000)),
            )
        ],
    )


def _handle_runs_list(request_id: str, payload: Mapping[str, Any]) -> dict[str, Any]:
    limit = int(payload.get("limit", 20))
    operation = _optional_string(payload.get("operation"))
    result = get_run_service().list(limit=limit, operation=operation)
    artifacts = [
        _artifact("run", f"embedded://runs/{run.run_id}", _token(run))
        for run in result.runs
    ]
    return _success(request_id, f"{result.total_count} BKX runs available.", artifacts=artifacts)


def _handle_runs_show(request_id: str, payload: Mapping[str, Any]) -> dict[str, Any]:
    run_id = _optional_string(payload.get("run_id")) or _optional_string(payload.get("target_request_id"))
    if run_id is None:
        raise ValidationError(msg="runs.show requires run_id or target_request_id")
    result = get_run_service().show(run_id)
    return _success(
        request_id,
        f"Run {result.run_id}: {result.operation} is {result.status}.",
        artifacts=[_artifact("run", f"embedded://runs/{result.run_id}", _token(result))],
    )


def _handle_cancel(request_id: str, payload: Mapping[str, Any]) -> dict[str, Any]:
    target = _optional_string(payload.get("target_request_id")) or request_id
    with _cancel_lock:
        _cancelled_requests.add(target)
    return _success(request_id, f"Cancellation recorded for {target}.")


_HANDLERS: dict[str, Callable[[str, Mapping[str, Any]], dict[str, Any]]] = {
    "backup.plan": _handle_backup_plan,
    "backup.create": _handle_backup_create,
    "archive.list": _handle_archive_list,
    "archive.info": _handle_archive_info,
    "archive.browse": _handle_archive_browse,
    "archive.verify": _handle_archive_verify,
    "restore.preview": _handle_restore_preview,
    "restore.extract": _handle_restore_extract,
    "transfer.plan": _handle_transfer_plan,
    "transfer.run": _handle_transfer_run,
    "runs.list": _handle_runs_list,
    "runs.show": _handle_runs_show,
    "run.cancel": _handle_cancel,
    "storage.locations": _handle_storage_locations,
    "filesystem.list": _handle_filesystem_list,
    "filesystem.stat": _handle_filesystem_stat,
}


def _mapping(document: Mapping[str, Any], key: str, *, default: Mapping[str, Any] | None = None) -> Mapping[str, Any]:
    value = document.get(key, default)
    if not isinstance(value, dict):
        raise ValidationError(msg=f"{key} must be an object")
    return value


def _string(document: Mapping[str, Any], key: str) -> str:
    value = document.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValidationError(msg=f"{key} must be a non-empty string")
    return value.strip()


def _string_alias(document: Mapping[str, Any], primary: str, legacy: str) -> str:
    value = document.get(primary)
    if isinstance(value, str) and value.strip():
        return value.strip()
    return _string(document, legacy)


def _optional_string(value: Any) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValidationError(msg="Expected string or null")
    stripped = value.strip()
    return stripped or None


def _string_tuple(value: Any) -> tuple[str, ...]:
    if value is None:
        return ()
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise ValidationError(msg="Expected a list of strings")
    return tuple(item for item in value if item)


def _plain(value: Any) -> Any:
    if is_dataclass(value):
        return _plain(asdict(value))
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, Mapping):
        return {str(key): _plain(item) for key, item in sorted(value.items(), key=lambda item: str(item[0]))}
    if isinstance(value, (tuple, list, set, frozenset)):
        return [_plain(item) for item in value]
    return value


def _token(value: Any) -> str:
    raw = json.dumps(_plain(value), sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def _json(value: Mapping[str, Any]) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def _artifact(
    label: str,
    path: str,
    sha256: str | None = None,
    *,
    size_bytes: int | None = None,
    modified_at_epoch_millis: int | None = None,
) -> dict[str, Any]:
    artifact: dict[str, Any] = {"label": label, "path": path}
    if sha256:
        artifact["sha256"] = sha256
    if size_bytes is not None:
        artifact["size_bytes"] = max(0, int(size_bytes))
    if modified_at_epoch_millis is not None:
        artifact["modified_at_epoch_millis"] = max(0, int(modified_at_epoch_millis))
    return artifact


def _error(code: str, message: str, action: str = "Review the operation details and retry.") -> dict[str, str]:
    return {"code": code, "message": str(message), "action": action}


def _warning(code: str, message: str) -> dict[str, str]:
    return _error(code, message, "Review this warning before continuing.")


def _result(
    request_id: str,
    status: str,
    summary: str,
    *,
    errors: list[dict[str, str]] | None = None,
    artifacts: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    return {
        "protocol_version": PROTOCOL_VERSION,
        "request_id": request_id,
        "status": status,
        "summary": summary,
        "errors": errors or [],
        "artifacts": artifacts or [],
    }


def _success(
    request_id: str,
    summary: str,
    *,
    errors: list[dict[str, str]] | None = None,
    artifacts: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    return _result(request_id, "success", summary, errors=errors, artifacts=artifacts)


def _cancelled(request_id: str, summary: str) -> dict[str, Any]:
    return _result(request_id, "cancelled", summary)


def _failure(request_id: str, exc: BkxError, *, code: str | None = None) -> dict[str, Any]:
    error_code = code or {
        ValidationError: "validation_error",
        NotFoundError: "not_found",
        PermissionDeniedError: "permission_denied",
        ExternalToolError: "external_tool_error",
    }.get(type(exc), "bkx_error")
    action = {
        "validation_error": "Review the selected files and operation options.",
        "not_found": "Choose an existing source, archive, or destination.",
        "permission_denied": "Grant access to the selected location and retry.",
        "external_tool_error": "This operation needs a backend capability unavailable in the embedded lane.",
        "embedded_runtime_error": "Review diagnostics and retry the operation.",
    }.get(error_code, "Review the operation details and retry.")
    return _result(
        request_id,
        "failure",
        str(exc),
        errors=[_error(error_code, str(exc), action)],
    )
