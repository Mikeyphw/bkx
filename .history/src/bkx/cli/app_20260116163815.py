"""BKX CLI entrypoint.

This is the **required** product interface.

Contract rules:
- Grammar: <tool> <noun> <verb>
- Must remain usable without interactive/TUI extras.
- All output through renderer, no print() in commands.
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Optional

import typer
from typer import Option, Argument

from bkx.cli.spec_builders import (
    build_backup_spec,
    build_archive_spec,
    build_transfer_spec,
    build_restore_spec,
)
from bkx.cli.render import get_renderer, render_result
from bkx.cli.exit_codes import error_to_exit_code, EXIT_OK
from bkx.core.errors import BkxError
from bkx.core.services import get_backup_service, get_archive_service, get_transfer_service, get_restore_service, get_run_service


# Global options state (passed through context)
_state = {
    "json": False,
    "dry_run": False,
    "progress": "auto",
    "verbose": False,
    "trace": False,
}


app = typer.Typer(no_args_is_help=True, add_completion=False, rich_markup_mode="markdown")


def _update_state(ctx: typer.Context) -> None:
    """Update global state from context."""
    _state["json"] = ctx.params.get("json", False)
    _state["dry_run"] = ctx.params.get("dry_run", False)
    _state["progress"] = ctx.params.get("progress", "auto")
    _state["verbose"] = ctx.params.get("verbose", False)
    _state["trace"] = ctx.params.get("trace", False)


@app.callback(context_settings={"allow_extra_args": True, "ignore_unknown_options": True})
def main_callback(
    ctx: typer.Context,
    json: bool = Option(False, "--json", help="Output results as JSON"),
    dry_run: bool = Option(False, "--dry-run", "-n", help="Show what would be done without executing"),
    progress: str = Option("auto", "--progress", help="Progress display: auto|plain|off", case_sensitive=False),
    verbose: bool = Option(False, "--verbose", "-v", help="Enable verbose output"),
    trace: bool = Option(False, "--trace", help="Show full stack traces on error"),
) -> None:
    """BKX - Contract-controlled CLI-first backup/archive tool.
    
    **Nouns:** backup, archive, transfer, restore, tools
    **Verbs:** create, list, info, push, pull, extract, detect, explain, plan
    """
    _update_state(ctx)


# ============================================================================
# backup noun
# ============================================================================

backup_app = typer.Typer(help="Backup operations")
app.add_typer(backup_app, name="backup")


@backup_app.command("create")
def backup_create(
    sources: list[Path] = Argument(..., help="Source paths to back up"),
    destination: Optional[Path] = Option(None, "-d", "--destination", help="Destination path for archive"),
    exclude: list[str] = Option([], "-e", "--exclude", help="Exclude glob patterns"),
    include: list[str] = Option([], "-i", "--include", help="Include glob patterns"),
    name: Optional[str] = Option(None, "-n", "--name", help="Archive name"),
    verify: bool = Option(True, "--verify/--no-verify", help="Verify after creation"),
) -> None:
    """Create a backup archive from sources.
    
    Example: bkx backup create ~/Documents --destination /backup
    """
    renderer = get_renderer(_state["json"])
    
    try:
        # Build spec
        spec = build_backup_spec(
            sources=sources,
            destination=destination,
            archive_name=name,
            excludes=exclude,
            includes=include,
            dry_run=_state["dry_run"],
            verify=verify,
        )
        
        # Render plan if dry-run
        if _state["dry_run"]:
            renderer.render_plan("backup", {
                "sources": [str(s) for s in spec.sources],
                "destination": str(spec.effective_destination) if spec.effective_destination else None,
                "archive_name": spec.archive_name,
                "excludes": list(spec.excludes),
                "compression": spec.compression.algorithm,
            })
            return
        
        # Execute service
        service = get_backup_service()
        result = service.create(spec)
        
        # Render result
        render_result(result, _state["json"])
        
    except BkxError as e:
        if _state["trace"]:
            raise
        renderer.render_error(str(e), e.details)
        sys.exit(error_to_exit_code(e))


@backup_app.command("plan")
def backup_plan(
    sources: list[Path] = Argument(..., help="Source paths to back up"),
    destination: Optional[Path] = Option(None, "-d", "--destination", help="Destination path for archive"),
) -> None:
    """Preview what a backup would do without executing.
    
    Example: bkx backup plan ~/Documents --destination /backup
    """
    renderer = get_renderer(_state["json"])
    
    try:
        # Build spec (always dry-run for plan)
        spec = build_backup_spec(
            sources=sources,
            destination=destination,
            dry_run=True,
        )
        
        # Render plan
        renderer.render_plan("backup", {
            "sources": [str(s) for s in spec.sources],
            "destination": str(spec.effective_destination) if spec.effective_destination else None,
            "archive_name": spec.archive_name,
            "excludes": list(spec.excludes),
            "compression": spec.compression.algorithm,
        })
        
    except BkxError as e:
        if _state["trace"]:
            raise
        renderer.render_error(str(e), e.details)
        sys.exit(error_to_exit_code(e))


# ============================================================================
# archive noun
# ============================================================================

archive_app = typer.Typer(help="Archive operations")
app.add_typer(archive_app, name="archive")


@archive_app.command("create")
def archive_create(
    sources: list[Path] = Argument(..., help="Source paths to archive"),
    output: Path = Option(..., "-o", "--output", help="Output archive path"),
    exclude: list[str] = Option([], "-e", "--exclude", help="Exclude glob patterns"),
    verify: bool = Option(True, "--verify/--no-verify", help="Verify after creation"),
) -> None:
    """Create an archive file from sources.
    
    Example: bkx archive create ~/Downloads -o archive.zip
    """
    renderer = get_renderer(_state["json"])
    
    try:
        # Build spec
        spec = build_archive_spec(
            sources=sources,
            output=output,
            excludes=exclude,
            dry_run=_state["dry_run"],
            verify=verify,
        )
        
        # Render plan if dry-run
        if _state["dry_run"]:
            renderer.render_plan("archive", {
                "sources": [str(s) for s in spec.sources],
                "output": str(spec.effective_output_path) if spec.effective_output_path else None,
                "excludes": list(spec.excludes),
            })
            return
        
        # Execute service
        service = get_archive_service()
        result = service.create(spec)
        
        # Render result
        render_result(result, _state["json"])
        
    except BkxError as e:
        if _state["trace"]:
            raise
        renderer.render_error(str(e), e.details)
        sys.exit(error_to_exit_code(e))


@archive_app.command("list")
def archive_list(
    archive: Path = Argument(..., help="Archive to list contents from"),
) -> None:
    """List contents of an archive.
    
    Example: bkx archive list archive.zip
    """
    renderer = get_renderer(_state["json"])
    
    try:
        service = get_archive_service()
        result = service.list(archive)
        
        # Render as message list
        for entry in result.entries:
            if entry.is_dir:
                renderer.render_message(f"📁 {entry.path}/", level="info")
            else:
                renderer.render_message(f"📄 {entry.path}", level="info")
        
    except BkxError as e:
        if _state["trace"]:
            raise
        renderer.render_error(str(e), e.details)
        sys.exit(error_to_exit_code(e))


@archive_app.command("info")
def archive_info(
    archive: Path = Argument(..., help="Archive to get info about"),
) -> None:
    """Show metadata about an archive.
    
    Example: bkx archive info archive.zip
    """
    renderer = get_renderer(_state["json"])
    
    try:
        service = get_archive_service()
        result = service.info(archive)
        
        if _state["json"]:
            import json
            json.dump({
                "path": str(result.path),
                "files_count": result.files_count,
                "bytes_total": result.bytes_total,
                "created": str(result.created) if result.created else None,
                "compression": result.compression_algorithm,
            }, sys.stdout, indent=2)
        else:
            renderer.render_message(f"Archive: {result.path}")
            renderer.render_message(f"Files: {result.files_count}")
            renderer.render_message(f"Size: {result.bytes_total} bytes")
            if result.created:
                renderer.render_message(f"Created: {result.created}")
        
    except BkxError as e:
        if _state["trace"]:
            raise
        renderer.render_error(str(e), e.details)
        sys.exit(error_to_exit_code(e))


# ============================================================================
# transfer noun
# ============================================================================

transfer_app = typer.Typer(help="Transfer operations")
app.add_typer(transfer_app, name="transfer")


@transfer_app.command("push")
def transfer_push(
    source: Path = Argument(..., help="Source path to transfer"),
    destination: Path = Argument(..., help="Destination path"),
    verify: bool = Option(True, "--verify/--no-verify", help="Verify after transfer"),
) -> None:
    """Push artifacts to a destination.
    
    Example: bkx transfer push /backup/archive.zip s3://bucket/backups/
    """
    renderer = get_renderer(_state["json"])
    
    try:
        spec = build_transfer_spec(
            source=source,
            destination=destination,
            direction="push",
            dry_run=_state["dry_run"],
            verify=verify,
        )
        
        if _state["dry_run"]:
            renderer.render_plan("transfer", {
                "direction": "push",
                "source": str(spec.effective_source) if spec.effective_source else None,
                "destination": str(spec.effective_destination) if spec.effective_destination else None,
            })
            return
        
        service = get_transfer_service()
        result = service.transfer(spec)
        render_result(result, _state["json"])
        
    except BkxError as e:
        if _state["trace"]:
            raise
        renderer.render_error(str(e), e.details)
        sys.exit(error_to_exit_code(e))


@transfer_app.command("pull")
def transfer_pull(
    source: Path = Argument(..., help="Source path to transfer from"),
    destination: Path = Argument(..., help="Local destination path"),
    verify: bool = Option(True, "--verify/--no-verify", help="Verify after transfer"),
) -> None:
    """Pull artifacts to local.
    
    Example: bkx transfer pull s3://bucket/backups/archive.zip ./local/
    """
    renderer = get_renderer(_state["json"])
    
    try:
        spec = build_transfer_spec(
            source=source,
            destination=destination,
            direction="pull",
            dry_run=_state["dry_run"],
            verify=verify,
        )
        
        if _state["dry_run"]:
            renderer.render_plan("transfer", {
                "direction": "pull",
                "source": str(spec.effective_source) if spec.effective_source else None,
                "destination": str(spec.effective_destination) if spec.effective_destination else None,
            })
            return
        
        service = get_transfer_service()
        result = service.transfer(spec)
        render_result(result, _state["json"])
        
    except BkxError as e:
        if _state["trace"]:
            raise
        renderer.render_error(str(e), e.details)
        sys.exit(error_to_exit_code(e))


# ============================================================================
# restore noun
# ============================================================================

restore_app = typer.Typer(help="Restore operations")
app.add_typer(restore_app, name="restore")


@restore_app.command("extract")
def restore_extract(
    archive: Path = Argument(..., help="Archive to restore from"),
    destination: Optional[Path] = Option(None, "-d", "--destination", help="Destination path"),
) -> None:
    """Restore full archive to destination.
    
    Example: bkx restore extract backup.bak -d /restored
    """
    renderer = get_renderer(_state["json"])
    
    try:
        spec = build_restore_spec(
            archive=archive,
            destination=destination,
            dry_run=_state["dry_run"],
        )
        
        if _state["dry_run"]:
            renderer.render_plan("restore", {
                "archive": str(archive),
                "destination": str(spec.effective_destination) if spec.effective_destination else None,
            })
            return
        
        service = get_restore_service()
        result = service.extract(spec)
        render_result(result, _state["json"])
        
    except BkxError as e:
        if _state["trace"]:
            raise
        renderer.render_error(str(e), e.details)
        sys.exit(error_to_exit_code(e))


# ============================================================================
# tools noun
# ============================================================================

tools_app = typer.Typer(help="Tool management")
app.add_typer(tools_app, name="tools")


@tools_app.command("detect")
def tools_detect() -> None:
    """Detect available optional tools.
    
    Example: bkx tools detect
    """
    renderer = get_renderer(_state["json"])
    
    from bkx.core.impl.tool_detector import get_tool_detector
    from bkx.core.ports.tool_detector import ToolDetectorPort

    detector: ToolDetectorPort = get_tool_detector()
    tools = detector.detect_all(("zstd", "tar", "rsync", "rclone", "sha256sum"))

    if _state["json"]:
        import json
        json.dump({"tools": {k: v.__dict__ for k, v in tools.items()}}, sys.stdout, indent=2)
    else:
        renderer.render_message("Available tools:", level="info")
        for name, info in tools.items():
            status = "✅" if info.available else "❌"
            renderer.render_message(f"  {status} {name}: {info.version or 'not found'}")


@tools_app.command("explain")
def tools_explain() -> None:
    """Explain which tools will be used and why.
    
    Example: bkx tools explain
    """
    from bkx.core.impl.tool_detector import get_tool_detector
    from bkx.core.ports.archiver import ArchiverPort
    from bkx.core.ports.hasher import HasherPort
    from bkx.core.impl.archiver import get_archiver
    from bkx.core.impl.hasher import get_hasher

    detector = get_tool_detector()
    tools = detector.detect_all(("zstd", "tar", "rsync", "rclone", "sha256sum"))
    
    if _state["json"]:
        import json
        json.dump({"tools": tools}, sys.stdout, indent=2)
    else:
        renderer.render_message("Tool selection rationale:", level="info")
        
        # Archiver
        if tools.get("zstd", None) and tools["zstd"].available:
            renderer.render_message("  Archiver: zstd (external tool available)", level="success")
        else:
            renderer.render_message("  Archiver: fallback implementation (no zstd)", level="warning")
        
        # Hasher
        if tools.get("sha256sum", None) and tools["sha256sum"].available:
            renderer.render_message("  Hasher: sha256sum (external tool available)", level="success")
        else:
            renderer.render_message("  Hasher: fallback implementation (no sha256sum)", level="warning")


# ============================================================================
# Entry point
# ============================================================================

def main() -> None:
    """Console script entrypoint."""
    app()

