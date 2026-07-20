"""Rendering layer (stdout only).

Contract rule:
- No print() scattered.
- All user output goes through renderer.
- Logs -> stderr.

Theme integration:
- Uses mikomn's unified theme system for consistent styling
- Rich markup styles applied based on active theme
"""

from __future__ import annotations

import json
import sys
from abc import ABC, abstractmethod
from dataclasses import asdict
from typing import Any

from bkx.core.results import (
    ArchiveCreateResult,
    BackupResult,
    RestoreResult,
    TransferResult,
)
from bkx.ui.theme import (
    error_color,
    error_style,
    info_style,
    primary_color,
    success_color,
    success_style,
    warning_style,
)


class Renderer(ABC):
    """Base renderer interface."""

    @abstractmethod
    def render_backup_result(self, result: BackupResult) -> None:
        """Render a backup result."""
        ...

    @abstractmethod
    def render_archive_result(self, result: ArchiveCreateResult) -> None:
        """Render an archive creation result."""
        ...

    @abstractmethod
    def render_transfer_result(self, result: TransferResult) -> None:
        """Render a transfer result."""
        ...

    @abstractmethod
    def render_restore_result(self, result: RestoreResult) -> None:
        """Render a restore result."""
        ...

    @abstractmethod
    def render_message(self, message: str, level: str = "info") -> None:
        """Render a message."""
        ...

    @abstractmethod
    def render_error(self, message: str, details: dict[str, Any] | None = None) -> None:
        """Render an error."""
        ...

    @abstractmethod
    def render_plan(self, spec_type: str, spec_data: dict[str, Any]) -> None:
        """Render a dry-run plan."""
        ...


class JSONRenderer(Renderer):
    """Render results as JSON."""

    def render_backup_result(self, result: BackupResult) -> None:
        output: dict[str, Any] = {
            "type": "backup",
            "success": result.success,
            "archive_path": str(result.archive_paths[0]) if result.archive_paths else None,
            "files_count": result.files_processed,
            "bytes_total": result.bytes_written,
            "duration_seconds": result.duration_ms / 1000,
            "verification_passed": result.verification_passed,
            "checksum": result.step_results.get("checksum"),
        }
        json.dump(output, sys.stdout, indent=2)
        sys.stdout.write("\n")

    def render_archive_result(self, result: ArchiveCreateResult) -> None:
        output: dict[str, Any] = {
            "type": "archive",
            "success": result.success,
            "output_path": str(result.output_path) if result.output_path else None,
            "files_count": result.files_archived,
            "bytes_total": result.bytes_written,
            "duration_seconds": result.duration_ms / 1000,
            "verification_passed": result.verification_passed,
        }
        json.dump(output, sys.stdout, indent=2)
        sys.stdout.write("\n")

    def render_transfer_result(self, result: TransferResult) -> None:
        output: dict[str, Any] = {
            "type": "transfer",
            "success": result.success,
            "source": str(result.source) if result.source else None,
            "destination": str(result.destination) if result.destination else None,
            "bytes_transferred": result.bytes_transferred,
            "duration_seconds": result.duration_ms / 1000,
            "verification_passed": result.verification_passed,
        }
        json.dump(output, sys.stdout, indent=2)
        sys.stdout.write("\n")

    def render_restore_result(self, result: RestoreResult) -> None:
        output: dict[str, Any] = {
            "type": "restore",
            "success": result.success,
            "archive": str(result.archive_path) if result.archive_path else None,
            "destination": str(result.destination) if result.destination else None,
            "files_restored": result.files_restored,
            "bytes_restored": result.bytes_restored,
            "duration_seconds": result.duration_ms / 1000,
        }
        json.dump(output, sys.stdout, indent=2)
        sys.stdout.write("\n")

    def render_message(self, message: str, level: str = "info") -> None:
        output: dict[str, Any] = {"type": "message", "level": level, "message": message}
        json.dump(output, sys.stdout, indent=2)
        sys.stdout.write("\n")

    def render_error(self, message: str, details: dict[str, Any] | None = None) -> None:
        output: dict[str, Any] = {"type": "error", "message": message}
        if details:
            output["details"] = details
        json.dump(output, sys.stdout, indent=2)
        sys.stdout.write("\n")

    def render_plan(self, spec_type: str, spec_data: dict[str, Any]) -> None:
        output: dict[str, Any] = {"type": "plan", "spec_type": spec_type, "spec": spec_data}
        json.dump(output, sys.stdout, indent=2)
        sys.stdout.write("\n")


class TextRenderer(Renderer):
    """Render results as human-readable text with mikomn theme integration."""

    def render_backup_result(self, result: BackupResult) -> None:
        if result.success:
            sys.stdout.write(f"[{success_color()}]✅ Backup completed successfully[/]\n")
            if result.archive_paths:
                sys.stdout.write(f"   Archive: {result.archive_paths[0]}\n")
            sys.stdout.write(f"   Files: {result.files_processed}\n")
            sys.stdout.write(f"   Size: {self._format_bytes(result.bytes_written)}\n")
            sys.stdout.write(f"   Duration: {result.duration_ms / 1000:.2f}s\n")
            if result.verification_passed:
                sys.stdout.write(f"   [{success_color()}]Verification: ✅ Passed[/]\n")
            if result.step_results.get("checksum"):
                sys.stdout.write(f"   Checksum: {result.step_results['checksum']}\n")
        else:
            sys.stdout.write(f"[{error_color()}]❌ Backup failed[/]\n")
            if result.errors:
                for error in result.errors:
                    sys.stdout.write(f"   Error: {error}\n")

    def render_archive_result(self, result: ArchiveCreateResult) -> None:
        if result.success:
            sys.stdout.write(f"[{success_color()}]✅ Archive created successfully[/]\n")
            if result.output_path:
                sys.stdout.write(f"   Output: {result.output_path}\n")
            sys.stdout.write(f"   Files: {result.files_archived}\n")
            sys.stdout.write(f"   Size: {self._format_bytes(result.bytes_written)}\n")
            sys.stdout.write(f"   Duration: {result.duration_ms / 1000:.2f}s\n")
            if result.verification_passed:
                sys.stdout.write(f"   [{success_color()}]Verification: ✅ Passed[/]\n")
        else:
            sys.stdout.write(f"[{error_color()}]❌ Archive creation failed[/]\n")
            if result.warnings:
                for warning in result.warnings:
                    sys.stdout.write(f"   Warning: {warning}\n")

    def render_transfer_result(self, result: TransferResult) -> None:
        if result.success:
            sys.stdout.write(f"[{success_color()}]✅ Transfer completed successfully[/]\n")
            if result.source and result.destination:
                sys.stdout.write(f"   From: {result.source}\n")
                sys.stdout.write(f"   To: {result.destination}\n")
            sys.stdout.write(f"   Size: {self._format_bytes(result.bytes_transferred)}\n")
            sys.stdout.write(f"   Duration: {result.duration_ms / 1000:.2f}s\n")
            if result.verification_passed:
                sys.stdout.write(f"   [{success_color()}]Verification: ✅ Passed[/]\n")
        else:
            sys.stdout.write(f"[{error_color()}]❌ Transfer failed[/]\n")
            if result.errors:
                for error in result.errors:
                    sys.stdout.write(f"   Error: {error}\n")

    def render_restore_result(self, result: RestoreResult) -> None:
        if result.success:
            sys.stdout.write(f"[{success_color()}]✅ Restore completed successfully[/]\n")
            if result.archive_path:
                sys.stdout.write(f"   Archive: {result.archive_path}\n")
            if result.destination:
                sys.stdout.write(f"   Destination: {result.destination}\n")
            sys.stdout.write(f"   Files: {result.files_restored}\n")
            sys.stdout.write(f"   Size: {self._format_bytes(result.bytes_restored)}\n")
            sys.stdout.write(f"   Duration: {result.duration_ms / 1000:.2f}s\n")
        else:
            sys.stdout.write(f"[{error_color()}]❌ Restore failed[/]\n")
            if result.errors:
                for error in result.errors:
                    sys.stdout.write(f"   Error: {error}\n")

    def render_message(self, message: str, level: str = "info") -> None:
        """Render a message with theme-aware styling."""
        style_map = {
            "info": info_style(),
            "success": success_style(),
            "warning": warning_style(),
            "error": error_style(),
        }
        icon_map = {
            "info": "ℹ️",
            "success": "✅",
            "warning": "⚠️",
            "error": "❌",
        }
        style = style_map.get(level, info_style())
        icon = icon_map.get(level, "ℹ️")
        sys.stdout.write(f"{icon} [{style}]{message}[/]\n")

    def render_error(self, message: str, details: dict[str, Any] | None = None) -> None:
        """Render an error with theme-aware styling."""
        sys.stdout.write(f"❌ [{error_style()}]{message}[/]\n")
        if details:
            for key, value in details.items():
                sys.stdout.write(f"   {key}: {value}\n")

    def render_plan(self, spec_type: str, spec_data: dict[str, Any]) -> None:
        """Render a dry-run plan with theme-aware styling."""
        sys.stdout.write(f"📋 [{primary_color()}]Dry-run plan for {spec_type}[/]:\n")
        for key, value in spec_data.items():
            if isinstance(value, list):
                sys.stdout.write(f"   {key}:\n")
                for item in value:
                    sys.stdout.write(f"     - {item}\n")
            elif isinstance(value, dict):
                sys.stdout.write(f"   {key}:\n")
                for k, v in value.items():
                    sys.stdout.write(f"     - {k}: {v}\n")
            else:
                sys.stdout.write(f"   {key}: {value}\n")

    def _format_bytes(self, bytes_val: float) -> str:
        """Format bytes to human-readable string."""
        for unit in ["B", "KB", "MB", "GB", "TB"]:
            if bytes_val < 1024:
                return f"{bytes_val:.1f}{unit}"
            bytes_val /= 1024
        return f"{bytes_val:.1f}PB"


def get_renderer(json_output: bool = False) -> Renderer:
    """Get the appropriate renderer.

    Args:
        json_output: Whether to use JSON renderer

    Returns:
        Renderer instance
    """
    if json_output:
        return JSONRenderer()
    return TextRenderer()


def render_result(result: Any, json_output: bool = False) -> None:
    """Render a result using the appropriate renderer.

    Args:
        result: The result to render
        json_output: Whether to use JSON output
    """
    renderer = get_renderer(json_output)

    if isinstance(result, BackupResult):
        renderer.render_backup_result(result)
    elif isinstance(result, ArchiveCreateResult):
        renderer.render_archive_result(result)
    elif isinstance(result, TransferResult):
        renderer.render_transfer_result(result)
    elif isinstance(result, RestoreResult):
        renderer.render_restore_result(result)
    else:
        # Fallback: try to serialize as dict
        if hasattr(result, "__dict__"):
            data = asdict(result)
            if json_output:
                json.dump(data, sys.stdout, indent=2, default=str)
            else:
                sys.stdout.write(str(data))


__all__ = [
    "Renderer",
    "JSONRenderer",
    "TextRenderer",
    "get_renderer",
    "render_result",
]
