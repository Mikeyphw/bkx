"""Shared interface intent-to-Spec adapters.

Optional interfaces must not reimplement business logic. They may collect
values in different ways, but they normalize those values through this thin
adapter and the canonical CLI Spec builders.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from bkx.cli.spec_builders import build_archive_spec, build_backup_spec, build_restore_spec, build_transfer_spec
from bkx.core.errors import ValidationError
from bkx.core.specs.archive import ArchiveCreateSpec
from bkx.core.specs.backup import BackupSpec
from bkx.core.specs.restore import RestoreSpec
from bkx.core.specs.transfer import TransferSpec

InterfaceSpec = BackupSpec | ArchiveCreateSpec | RestoreSpec | TransferSpec

_BACKUP_COMMANDS = {"backup", "backup.create", "backup create"}
_ARCHIVE_COMMANDS = {"archive", "archive.create", "archive create"}
_RESTORE_COMMANDS = {"restore", "restore.extract", "restore extract", "restore.pick", "restore pick"}
_TRANSFER_COMMANDS = {"transfer", "transfer.run", "transfer push", "transfer.pull", "transfer pull", "transfer.push"}


def _is_absent(value: Any) -> bool:
    return value is None or value == "" or value == () or value == []


def coerce_path(value: Any, *, default: Path | None = None) -> Path | None:
    if _is_absent(value):
        return default
    if isinstance(value, Path):
        return value.expanduser()
    return Path(str(value)).expanduser()


def coerce_paths(value: Any, *, default: Sequence[Path] = ()) -> list[Path]:
    if _is_absent(value):
        return [Path(item).expanduser() for item in default]
    if isinstance(value, Path):
        return [value.expanduser()]
    if isinstance(value, str):
        parts = value.split()
    else:
        parts = [str(item) for item in value]
    return [Path(part).expanduser() for part in parts if part]


def coerce_words(value: Any) -> list[str]:
    if _is_absent(value):
        return []
    if isinstance(value, str):
        return [part for part in value.split() if part]
    return [str(item) for item in value if str(item)]


def coerce_bool(value: Any, *, default: bool = False) -> bool:
    if _is_absent(value):
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"1", "true", "yes", "y", "on"}:
            return True
        if normalized in {"0", "false", "no", "n", "off"}:
            return False
    raise ValidationError(msg="Expected a boolean interface value", details={"value": value})


def coerce_int(value: Any, *, default: int | None = None) -> int | None:
    if _is_absent(value):
        return default
    if isinstance(value, bool):
        raise ValidationError(msg="Expected an integer interface value", details={"value": value})
    try:
        return int(value)
    except (TypeError, ValueError) as exc:
        raise ValidationError(msg="Expected an integer interface value", details={"value": value}) from exc


def coerce_transfer_location(value: Any) -> Path | str | None:
    if _is_absent(value):
        return None
    if isinstance(value, Path):
        return value.expanduser()
    text = str(value).strip()
    if "://" in text or (":" in text and not text.startswith(("/", "./", "../", "~"))):
        return text
    return Path(text).expanduser()


def build_spec_from_interface_values(command: str, values: Mapping[str, Any]) -> InterfaceSpec:
    """Build the same normalized Spec from CLI, Interactive, and TUI values.

    The adapter intentionally accepts only strict command identifiers used by
    the public noun/verb grammar and optional UI dispatch table.
    """
    normalized_command = command.strip().lower().replace("_", ".")
    if normalized_command in _BACKUP_COMMANDS:
        sources = coerce_paths(values.get("sources"), default=(Path("."),))
        return build_backup_spec(
            sources=sources,
            destination=coerce_path(values.get("destination")),
            archive_name=(str(values.get("archive_name")).strip() if values.get("archive_name") not in (None, "") else None),
            excludes=coerce_words(values.get("excludes")),
            includes=coerce_words(values.get("includes")),
            split_size=coerce_int(values.get("split_size")),
            algorithm=(str(values.get("algorithm")).strip() if values.get("algorithm") not in (None, "") else None),
            level=coerce_int(values.get("level")),
            verify=coerce_bool(values.get("verify"), default=True) if "verify" in values else None,
            dry_run=coerce_bool(values.get("dry_run"), default=False),
        )
    if normalized_command in _ARCHIVE_COMMANDS:
        output = coerce_path(values.get("output") or values.get("output_path"))
        if output is None:
            raise ValidationError(msg="Archive output is required")
        return build_archive_spec(
            sources=coerce_paths(values.get("sources"), default=(Path("."),)),
            output=output,
            excludes=coerce_words(values.get("excludes")),
            includes=coerce_words(values.get("includes")),
            split_size=coerce_int(values.get("split_size")),
            algorithm=(str(values.get("algorithm")).strip() if values.get("algorithm") not in (None, "") else None),
            level=coerce_int(values.get("level")),
            verify=coerce_bool(values.get("verify"), default=True) if "verify" in values else None,
            dry_run=coerce_bool(values.get("dry_run"), default=False),
        )
    if normalized_command in _RESTORE_COMMANDS:
        archive = coerce_path(values.get("archive"))
        if archive is None:
            raise ValidationError(msg="Archive path is required")
        return build_restore_spec(
            archive=archive,
            destination=coerce_path(values.get("destination")),
            paths=coerce_words(values.get("paths")),
            overwrite=coerce_bool(values.get("overwrite"), default=False) if "overwrite" in values else None,
            verify=coerce_bool(values.get("verify"), default=True) if "verify" in values else None,
            dry_run=coerce_bool(values.get("dry_run"), default=False),
            fresh_dir=coerce_bool(values.get("fresh_dir"), default=True) if "fresh_dir" in values else None,
            confirm_overwrite=coerce_bool(values.get("confirm_overwrite"), default=True) if "confirm_overwrite" in values else None,
        )
    if normalized_command in _TRANSFER_COMMANDS:
        source = coerce_transfer_location(values.get("source"))
        destination = coerce_transfer_location(values.get("destination"))
        if source is None or destination is None:
            raise ValidationError(msg="Source and destination are required")
        direction = str(values.get("direction") or "push").strip().lower()
        return build_transfer_spec(
            source=source,
            destination=destination,
            direction=direction,
            verify=coerce_bool(values.get("verify"), default=True) if "verify" in values else None,
            checksum=(str(values.get("checksum")).strip() if values.get("checksum") not in (None, "") else None),
            dry_run=coerce_bool(values.get("dry_run"), default=False),
            overwrite=coerce_bool(values.get("overwrite"), default=False),
        )
    raise ValidationError(msg="Unsupported interface command", details={"command": command})
