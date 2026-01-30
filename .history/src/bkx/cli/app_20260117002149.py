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
    build_archive_browse_spec,
)
from bkx.cli.render import get_renderer, render_result
from bkx.cli.exit_codes import error_to_exit_code, EXIT_OK
from bkx.core.errors import BkxError
from bkx.core.services import get_backup_service, get_archive_service, get_transfer_service, get_restore_service, get_run_service
from bkx.tui.app import run_tui


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


@archive_app.command("browse")
def archive_browse(
    archive: Path = Argument(..., help="Archive to browse"),
    query: str = Option(None, "-q", "--query", help="Search query (matches path)"),
    file_type: str = Option(None, "-t", "--type", help="Filter by type: file, dir"),
    min_size: int = Option(None, "--min-size", help="Minimum file size (bytes)"),
    max_size: int = Option(None, "--max-size", help="Maximum file size (bytes)"),
    limit: int = Option(50, "-l", "--limit", help="Maximum results to return"),
    sort_by: str = Option("path", "-s", "--sort", help="Sort by: path, size, mtime"),
    sort_order: str = Option("asc", "-o", "--order", help="Sort order: asc, desc"),
) -> None:
    """Browse archive contents with filters and search.
    
    Example: bkx archive browse archive.zip -q "docs/"
    Example: bkx archive browse archive.zip --type file --sort size --order desc
    """
    renderer = get_renderer(_state["json"])
    
    try:
        spec = build_archive_browse_spec(
            archive=archive,
            query=query,
            file_type=file_type,
            min_size=min_size,
            max_size=max_size,
            limit=limit,
            sort_by=sort_by,
            sort_order=sort_order,
        )
        
        service = get_archive_service()
        result = service.browse(spec)
        
        if _state["json"]:
            import json
            json.dump({
                "archive": str(result.archive_path),
                "query": result.query,
                "total_matches": result.total_matches,
                "returned": len(result.entries),
                "files_count": result.files_count,
                "directories_count": result.directories_count,
                "total_size": result.total_size,
                "entries": [
                    {
                        "path": e.path,
                        "is_dir": e.is_dir,
                        "size": e.size,
                    }
                    for e in result.entries
                ],
            }, sys.stdout, indent=2)
        else:
            renderer.render_message(f"Archive: {result.archive_path}", level="info")
            if result.query:
                renderer.render_message(f"Query: '{result.query}'", level="info")
            renderer.render_message(f"Found {result.total_matches} matches ({len(result.entries)} shown):", level="info")
            renderer.render_message(f"  Files: {result.files_count} | Dirs: {result.directories_count} | Size: {result.total_size} bytes", level="info")
            for entry in result.entries:
                if entry.is_dir:
                    renderer.render_message(f"  📁 {entry.path}/")
                else:
                    renderer.render_message(f"  📄 {entry.path} ({entry.size} bytes)")
        
    except BkxError as e:
        if _state["trace"]:
            raise
        renderer.render_error(str(e), e.details)
        sys.exit(error_to_exit_code(e))


@archive_app.command("verify")
def archive_verify(
    archive: Path = Argument(..., help="Archive to verify"),
) -> None:
    """Verify archive integrity.
    
    Example: bkx archive verify archive.zip
    """
    renderer = get_renderer(_state["json"])
    
    try:
        service = get_archive_service()
        result = service.verify(archive)
        
        if _state["json"]:
            import json
            json.dump({
                "success": result.success,
                "archive": str(result.archive_path),
                "checksums_valid": result.checksums_valid,
                "structure_valid": result.structure_valid,
                "entries_checked": result.entries_checked,
                "entries_failed": result.entries_failed,
                "warnings": result.warnings,
            }, sys.stdout, indent=2)
        else:
            if result.success:
                renderer.render_message(f"✅ Archive verified: {archive}", level="success")
            else:
                renderer.render_message(f"❌ Archive verification failed: {archive}", level="error")
            
            renderer.render_message(f"  Checksums: {'✅ Valid' if result.checksums_valid else '❌ Invalid'}")
            renderer.render_message(f"  Structure: {'✅ Valid' if result.structure_valid else '❌ Invalid'}")
            renderer.render_message(f"  Entries checked: {result.entries_checked}")
            
            if result.entries_failed > 0:
                renderer.render_message(f"  Failed entries: {result.entries_failed}", level="error")
                for entry in result.failed_entries:
                    renderer.render_message(f"    - {entry}")
            
            if result.warnings:
                for warning in result.warnings:
                    renderer.render_message(f"  ⚠️ {warning}", level="warning")
        
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
    overwrite: bool = Option(False, "-o", "--overwrite", help="Overwrite existing files (DANGEROUS without --yes)"),
    yes: bool = Option(False, "-y", "--yes", help="Skip confirmation and allow overwrite"),
    no_fresh_dir: bool = Option(False, "--no-fresh-dir", help="Don't create fresh directory (use destination as-is)"),
) -> None:
    """Restore full archive to destination.
    
    By default, restores to a fresh directory with timestamp to prevent accidents.
    
    Example: bkx restore extract backup.bak -d /restored
    Example: bkx restore extract backup.bak -d /restored --yes  # Skip confirmation
    """
    renderer = get_renderer(_state["json"])
    
    try:
        spec = build_restore_spec(
            archive=archive,
            destination=destination,
            dry_run=_state["dry_run"],
            fresh_dir=not no_fresh_dir,
            confirm_overwrite=not yes,
            overwrite=overwrite,
        )
        
        if _state["dry_run"]:
            renderer.render_plan("restore", {
                "archive": str(archive),
                "destination": str(spec.effective_destination) if spec.effective_destination else None,
                "fresh_dir": spec.fresh_dir,
                "requires_confirmation": spec.requires_confirmation,
            })
            return
        
        # Check if confirmation is needed
        if spec.requires_confirmation and not yes:
            if not _state["json"]:
                renderer.render_message(f"⚠️  Destination '{spec.effective_destination}' exists!", level="warning")
                renderer.render_message("  Files may be overwritten.", level="warning")
                renderer.render_message("  Use --yes to confirm or -d to specify a different destination.", level="info")
                sys.exit(1)
        
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
# run noun
# ============================================================================

run_app = typer.Typer(help="View run history")
app.add_typer(run_app, name="run")


@run_app.command("list")
def run_list(
    limit: int = Option(20, "-l", "--limit", help="Maximum number of runs to show"),
    operation: str | None = Option(None, "-o", "--operation", help="Filter by operation type (backup, archive, transfer, restore)"),
) -> None:
    """List recent backup/archive/transfer/restore runs.
    
    Example: bkx run list -l 10
    Example: bkx run list --operation backup
    """
    renderer = get_renderer(_state["json"])
    
    try:
        service = get_run_service()
        result = service.list(limit=limit, operation=operation)
        
        if _state["json"]:
            import json
            json.dump({
                "runs": [
                    {
                        "run_id": r.run_id,
                        "operation": r.operation,
                        "status": r.status,
                        "started_at": r.started_at.isoformat(),
                        "completed_at": r.completed_at.isoformat() if r.completed_at else None,
                        "source_count": r.source_count,
                    }
                    for r in result.runs
                ],
                "total_count": result.total_count,
                "limit": result.limit,
            }, sys.stdout, indent=2)
        else:
            renderer.render_message(f"Recent runs (showing {len(result.runs)} of {result.total_count}):", level="info")
            for run in result.runs:
                status_icon = "✅" if run.status == "success" else "❌" if run.status == "failed" else "⏳"
                renderer.render_message(f"  {status_icon} {run.run_id[:8]} | {run.operation:8} | {run.status:7} | {run.source_count} sources | {run.started_at.strftime('%Y-%m-%d %H:%M')}")
        
    except BkxError as e:
        if _state["trace"]:
            raise
        renderer.render_error(str(e), e.details)
        sys.exit(error_to_exit_code(e))


@run_app.command("show")
def run_show(
    run_id: str = Argument(..., help="Run ID to show details for"),
) -> None:
    """Show details of a specific run.
    
    Example: bkx run show abc12345
    """
    renderer = get_renderer(_state["json"])
    
    try:
        service = get_run_service()
        result = service.show(run_id)
        
        if _state["json"]:
            import json
            json.dump({
                "run_id": result.run_id,
                "operation": result.operation,
                "status": result.status,
                "timing": {
                    "started_at": result.timing.started_at.isoformat() if result.timing else None,
                    "duration_seconds": result.timing.duration_seconds if result.timing else None,
                } if result.timing else None,
                "source_paths": list(result.source_paths),
                "destination_path": result.destination_path,
                "archive_path": result.archive_path,
                "files_count": result.files_count,
                "bytes_processed": result.bytes_processed,
                "error_message": result.error_message,
                "spec_snapshot": result.spec_snapshot,
            }, sys.stdout, indent=2)
        else:
            renderer.render_message(f"Run: {result.run_id}", level="info")
            renderer.render_message(f"  Operation: {result.operation}")
            renderer.render_message(f"  Status: {result.status}")
            if result.timing:
                renderer.render_message(f"  Started: {result.timing.started_at}")
                renderer.render_message(f"  Duration: {result.timing.duration_seconds:.2f}s")
            if result.source_paths:
                renderer.render_message(f"  Sources ({len(result.source_paths)}):")
                for src in result.source_paths[:5]:
                    renderer.render_message(f"    - {src}")
                if len(result.source_paths) > 5:
                    renderer.render_message(f"    ... and {len(result.source_paths) - 5} more")
            if result.destination_path:
                renderer.render_message(f"  Destination: {result.destination_path}")
            if result.archive_path:
                renderer.render_message(f"  Archive: {result.archive_path}")
            if result.files_count:
                renderer.render_message(f"  Files: {result.files_count}")
            if result.bytes_processed:
                renderer.render_message(f"  Bytes: {result.bytes_processed}")
            if result.error_message:
                renderer.render_message(f"  Error: {result.error_message}", level="error")
        
    except BkxError as e:
        if _state["trace"]:
            raise
        renderer.render_error(str(e), e.details)
        sys.exit(error_to_exit_code(e))


# ============================================================================
# Entry point
# ============================================================================

def main() -> None:
    """Console script entrypoint."""
    app()

