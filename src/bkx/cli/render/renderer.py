"""Shared CLI rendering layer.

All commands route through this module.  JSON mode emits exactly one JSON
object per command.  Text mode emits plain text only; no Rich markup tokens are
printed literally by the base CLI.
"""

from __future__ import annotations

import json
import sys
from abc import ABC, abstractmethod
from dataclasses import asdict, is_dataclass
from datetime import date, datetime
from pathlib import Path
from typing import Any

from bkx.core.ports.tool_detector import ToolInfo
from bkx.core.results import ArchiveCreateResult, BackupResult, RestoreResult, TransferResult


def _json_safe(value: Any) -> Any:
    """Convert BKX domain objects into JSON-safe builtin values."""
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, ToolInfo):
        return {
            "name": value.name,
            "path": str(value.path) if value.path is not None else None,
            "version": value.version,
            "available": value.available,
            "capabilities": list(value.capabilities),
        }
    if is_dataclass(value) and not isinstance(value, type):
        return _json_safe(asdict(value))
    if isinstance(value, dict):
        return {str(_json_safe(k)): _json_safe(v) for k, v in value.items()}
    if isinstance(value, (tuple, list, set, frozenset)):
        return [_json_safe(item) for item in value]
    return value


def _result_type(result: Any) -> str:
    name = type(result).__name__
    for suffix in ("Result",):
        if name.endswith(suffix):
            name = name[: -len(suffix)]
    out: list[str] = []
    for i, char in enumerate(name):
        if i and char.isupper():
            out.append("_")
        out.append(char.lower())
    return "".join(out)


def _result_payload(result: Any, *, kind: str | None = None) -> dict[str, Any]:
    payload = _json_safe(result)
    if not isinstance(payload, dict):
        payload = {"value": payload}
    payload.setdefault("type", kind or _result_type(result))
    return payload


class Renderer(ABC):
    """Base renderer interface."""

    @abstractmethod
    def render_backup_result(self, result: BackupResult) -> None: ...

    @abstractmethod
    def render_archive_result(self, result: ArchiveCreateResult) -> None: ...

    @abstractmethod
    def render_transfer_result(self, result: TransferResult) -> None: ...

    @abstractmethod
    def render_restore_result(self, result: RestoreResult) -> None: ...

    @abstractmethod
    def render_result(self, result: Any, *, kind: str | None = None) -> None: ...

    @abstractmethod
    def render_message(self, message: str, level: str = "info") -> None: ...

    @abstractmethod
    def render_error(self, message: str, details: dict[str, Any] | None = None) -> None: ...

    @abstractmethod
    def render_plan(self, spec_type: str, spec_data: dict[str, Any]) -> None: ...

    @abstractmethod
    def render_verbose(self, message: str) -> None: ...


class JSONRenderer(Renderer):
    """Render exactly one structured JSON document for each public output."""

    def _write(self, payload: dict[str, Any]) -> None:
        json.dump(_json_safe(payload), sys.stdout, indent=2, sort_keys=True)
        sys.stdout.write("\n")

    def render_result(self, result: Any, *, kind: str | None = None) -> None:
        self._write(_result_payload(result, kind=kind))

    def render_backup_result(self, result: BackupResult) -> None:
        payload = _result_payload(result, kind="backup")
        checksums = payload.get("checksums")
        if isinstance(checksums, dict):
            payload.setdefault("checksum", checksums.get("archive"))
        self._write(payload)

    def render_archive_result(self, result: ArchiveCreateResult) -> None:
        self.render_result(result, kind="archive")

    def render_transfer_result(self, result: TransferResult) -> None:
        self.render_result(result, kind="transfer")

    def render_restore_result(self, result: RestoreResult) -> None:
        self.render_result(result, kind="restore")

    def render_message(self, message: str, level: str = "info") -> None:
        self._write({"type": "message", "level": level, "message": message})

    def render_error(self, message: str, details: dict[str, Any] | None = None) -> None:
        payload: dict[str, Any] = {"type": "error", "success": False, "message": message}
        if details:
            payload["details"] = details
        self._write(payload)

    def render_plan(self, spec_type: str, spec_data: dict[str, Any]) -> None:
        self._write({"type": "plan", "success": True, "spec_type": spec_type, "spec": spec_data})

    def render_verbose(self, message: str) -> None:
        # JSON stdout must stay a single result document. Verbose diagnostics go
        # to stderr so they cannot corrupt machine-readable output.
        sys.stderr.write(f"verbose: {message}\n")


class TextRenderer(Renderer):
    """Render plain human-readable output without Rich markup syntax."""

    def render_result(self, result: Any, *, kind: str | None = None) -> None:
        payload = _result_payload(result, kind=kind)
        title = payload.get("type", "result")
        success = payload.get("success")
        if success is True:
            sys.stdout.write(f"OK {title}\n")
        elif success is False:
            sys.stdout.write(f"FAILED {title}\n")
        else:
            sys.stdout.write(f"{title}\n")
        for key, value in payload.items():
            if key in {"type", "success"} or value in (None, (), [], {}):
                continue
            sys.stdout.write(f"  {key}: {self._format_value(value)}\n")

    def render_backup_result(self, result: BackupResult) -> None:
        self.render_result(result, kind="backup")

    def render_archive_result(self, result: ArchiveCreateResult) -> None:
        self.render_result(result, kind="archive")

    def render_transfer_result(self, result: TransferResult) -> None:
        self.render_result(result, kind="transfer")

    def render_restore_result(self, result: RestoreResult) -> None:
        self.render_result(result, kind="restore")

    def render_message(self, message: str, level: str = "info") -> None:
        prefix = {"success": "OK", "warning": "WARNING", "error": "ERROR"}.get(level, "INFO")
        sys.stdout.write(f"{prefix}: {message}\n")

    def render_error(self, message: str, details: dict[str, Any] | None = None) -> None:
        sys.stdout.write(f"ERROR: {message}\n")
        if details:
            for key, value in details.items():
                sys.stdout.write(f"  {key}: {self._format_value(value)}\n")

    def render_plan(self, spec_type: str, spec_data: dict[str, Any]) -> None:
        sys.stdout.write(f"Plan for {spec_type}:\n")
        for key, value in spec_data.items():
            sys.stdout.write(f"  {key}: {self._format_value(value)}\n")

    def render_verbose(self, message: str) -> None:
        sys.stderr.write(f"verbose: {message}\n")

    def _format_value(self, value: Any) -> str:
        value = _json_safe(value)
        if isinstance(value, (dict, list)):
            return json.dumps(value, sort_keys=True)
        return str(value)


def get_renderer(json_output: bool = False) -> Renderer:
    return JSONRenderer() if json_output else TextRenderer()


def render_result(result: Any, json_output: bool = False, *, kind: str | None = None) -> None:
    get_renderer(json_output).render_result(result, kind=kind)


__all__ = ["Renderer", "JSONRenderer", "TextRenderer", "get_renderer", "render_result"]
