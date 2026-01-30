"""Interactive mode entrypoint.

This module provides the interactive CLI experience using prompts and menus.
Install via: pip install .[interactive]

Contract rules:
- Interactive uses the same Spec builders as CLI (parity-safe)
- No business logic in prompts - only input collection
- Interactive can be deleted with no refactor required
"""

from __future__ import annotations

import sys
from pathlib import Path

from bkx.core.config import get_config
from bkx.cli.spec_builders import (
    build_backup_spec,
    build_archive_spec,
    build_restore_spec,
    build_transfer_spec,
)
from bkx.cli.render import get_renderer
from bkx.cli.exit_codes import error_to_exit_code, EXIT_OK
from bkx.core.errors import BkxError


def prompt_path(prompt_text: str, must_exist: bool = True) -> Path:
    """Prompt for a path with validation.
    
    Args:
        prompt_text: Prompt message
        must_exist: Whether the path must exist
        
    Returns:
        Validated Path object
    """
    from questionary import Path as QPath
    
    # questionary.Path validates existence by default
    path = QPath(prompt_text).ask()
    if path is None:
        sys.exit(EXIT_OK)
    return Path(path)


def prompt_text(prompt_text: str, default: str | None = None) -> str | None:
    """Prompt for text input.
    
    Args:
        prompt_text: Prompt message
        default: Optional default value
        
    Returns:
        Input string or None if cancelled
    """
    from questionary import text
    
    kwargs = {"message": prompt_text}
    if default:
        kwargs["default"] = default
    
    result = text(**kwargs).ask()
    if result is None:
        return None
    return result.strip() or default


def prompt_confirm(prompt_text: str, default: bool = False) -> bool:
    """Prompt for yes/no confirmation.
    
    Args:
        prompt_text: Prompt message
        default: Default value
        
    Returns:
        True for yes, False for no
    """
    from questionary import confirm
    
    result = confirm(message=prompt_text, default=default).ask()
    return result if result is not None else default


def prompt_select(
    prompt_text: str,
    choices: list[str],
    default: int = 0,
) -> str | None:
    """Prompt for selection from a list.
    
    Args:
        prompt_text: Prompt message
        choices: List of choices
        default: Default index
        
    Returns:
        Selected choice or None if cancelled
    """
    from questionary import select
    
    result = select(
        message=prompt_text,
        choices=choices,
        default=choices[default] if 0 <= default < len(choices) else choices[0],
    ).ask()
    return result


def prompt_multiselect(
    prompt_text: str,
    choices: list[str],
) -> list[str]:
    """Prompt for multiple selections.
    
    Args:
        prompt_text: Prompt message
        choices: List of choices
        
    Returns:
        List of selected choices (empty list if cancelled)
    """
    from questionary import checkbox
    
    result = checkbox(
        message=prompt_text,
        choices=choices,
    ).ask()
    return result if result is not None else []


def run_backup_interactive() -> int:
    """Run interactive backup flow.
    
    Returns:
        Exit code
    """
    renderer = get_renderer(json=False)
    renderer.render_message("=== Interactive Backup ===", level="info")
    
    # Step 1: Select sources
    sources_text = prompt_text(
        "Source paths to back up (space-separated, or leave empty for current directory)",
        default=".",
    )
    if sources_text is None:
        return EXIT_OK
    
    sources = [Path(p.strip()) for p in sources_text.split() if p.strip()]
    if not sources:
        sources = [Path(".")]
    
    # Step 2: Destination
    destination_text = prompt_text(
        "Destination path for archive (optional, will auto-generate if empty)",
        default=None,
    )
    destination = Path(destination_text) if destination_text else None
    
    # Step 3: Archive name
    archive_name = prompt_text("Archive name (optional, auto-generated if empty)", default=None)
    
    # Step 4: Excludes
    excludes_text = prompt_text(
        "Exclude patterns (space-separated, e.g., '*.pyc __pycache__')",
        default="",
    )
    excludes = excludes_text.split() if excludes_text else []
    
    # Step 5: Compression settings (with config defaults)
    config = get_config()
    algorithm = prompt_select(
        "Compression algorithm",
        choices=["zstd", "gzip", "lz4", "xz", "bzip2", "none"],
        default=["zstd", "gzip", "lz4", "xz", "bzip2", "none"].index(
            config.compression_algorithm
        ) if config.compression_algorithm in ["zstd", "gzip", "lz4", "xz", "bzip2"] else 0,
    )
    
    level_text = prompt_text(
        "Compression level (1-22 for zstd, 1-9 for others)",
        default=str(config.compression_level),
    )
    try:
        level = int(level_text) if level_text else config.compression_level
    except ValueError:
        level = config.compression_level
    
    # Step 6: Verify
    verify = prompt_confirm(
        "Verify archive after creation?",
        default=config.verify_after_operation,
    )
    
    # Step 7: Preview and confirm
    renderer.render_message("", level="info")
    renderer.render_message("Backup Plan:", level="info")
    renderer.render_message(f"  Sources: {[str(s) for s in sources]}", level="info")
    if destination:
        renderer.render_message(f"  Destination: {destination}", level="info")
    if archive_name:
        renderer.render_message(f"  Name: {archive_name}", level="info")
    renderer.render_message(f"  Excludes: {excludes}", level="info")
    renderer.render_message(f"  Algorithm: {algorithm} (level {level})", level="info")
    renderer.render_message(f"  Verify: {verify}", level="info")
    renderer.render_message("", level="info")
    
    if not prompt_confirm("Proceed with backup?", default=True):
        renderer.render_message("Cancelled.", level="warning")
        return EXIT_OK
    
    # Build spec and execute
    try:
        spec = build_backup_spec(
            sources=sources,
            destination=destination,
            archive_name=archive_name,
            excludes=excludes,
            algorithm=algorithm if algorithm != "none" else "zstd",
            level=level,
            verify=verify,
        )
        
        from bkx.core.services import get_backup_service
        service = get_backup_service()
        result = service.create(spec)
        
        renderer.render_message("", level="info")
        renderer.render_message("Backup completed!", level="success")
        renderer.render_message(f"  Archive: {result.archive_path}", level="info")
        renderer.render_message(f"  Files: {result.files_count}", level="info")
        renderer.render_message(f"  Size: {result.bytes_total} bytes", level="info")
        
        return EXIT_OK
        
    except BkxError as e:
        renderer.render_error(str(e), e.details)
        return error_to_exit_code(e)


def run_archive_interactive() -> int:
    """Run interactive archive flow.
    
    Returns:
        Exit code
    """
    renderer = get_renderer(json=False)
    renderer.render_message("=== Interactive Archive ===", level="info")
    
    # Step 1: Select sources
    sources_text = prompt_text(
        "Source paths to archive (space-separated)",
        default=".",
    )
    if sources_text is None:
        return EXIT_OK
    
    sources = [Path(p.strip()) for p in sources_text.split() if p.strip()]
    if not sources:
        sources = [Path(".")]
    
    # Step 2: Output path
    output_text = prompt_text("Output archive path (required)", default=None)
    if output_text is None or not output_text.strip():
        renderer.render_error("Output path is required", {})
        return 2
    output = Path(output_text.strip())
    
    # Step 3: Excludes
    excludes_text = prompt_text(
        "Exclude patterns (space-separated)",
        default="",
    )
    excludes = excludes_text.split() if excludes_text else []
    
    # Step 4: Compression
    config = get_config()
    algorithm = prompt_select(
        "Compression algorithm",
        choices=["zstd", "gzip", "lz4", "xz", "bzip2", "none"],
        default=["zstd", "gzip", "lz4", "xz", "bzip2", "none"].index(
            config.compression_algorithm
        ) if config.compression_algorithm in ["zstd", "gzip", "lz4", "xz", "bzip2"] else 0,
    )
    
    # Step 5: Verify
    verify = prompt_confirm(
        "Verify archive after creation?",
        default=config.verify_after_operation,
    )
    
    # Preview
    renderer.render_message("", level="info")
    renderer.render_message("Archive Plan:", level="info")
    renderer.render_message(f"  Sources: {[str(s) for s in sources]}", level="info")
    renderer.render_message(f"  Output: {output}", level="info")
    renderer.render_message(f"  Excludes: {excludes}", level="info")
    renderer.render_message(f"  Algorithm: {algorithm}", level="info")
    renderer.render_message(f"  Verify: {verify}", level="info")
    
    if not prompt_confirm("Proceed with archive?", default=True):
        renderer.render_message("Cancelled.", level="warning")
        return EXIT_OK
    
    try:
        spec = build_archive_spec(
            sources=sources,
            output=output,
            excludes=excludes,
            algorithm=algorithm if algorithm != "none" else "zstd",
            verify=verify,
        )
        
        from bkx.core.services import get_archive_service
        service = get_archive_service()
        result = service.create(spec)
        
        renderer.render_message("", level="info")
        renderer.render_message("Archive created!", level="success")
        renderer.render_message(f"  Path: {result.archive_path}", level="info")
        renderer.render_message(f"  Files: {result.files_count}", level="info")
        
        return EXIT_OK
        
    except BkxError as e:
        renderer.render_error(str(e), e.details)
        return error_to_exit_code(e)


def run_restore_interactive() -> int:
    """Run interactive restore flow.
    
    Returns:
        Exit code
    """
    renderer = get_renderer(json=False)
    renderer.render_message("=== Interactive Restore ===", level="info")
    
    # Step 1: Archive path
    archive_text = prompt_text("Archive path to restore from", default=None)
    if archive_text is None:
        return EXIT_OK
    archive = Path(archive_text.strip())
    
    # Step 2: Destination
    dest_text = prompt_text(
        "Destination path (optional, will create fresh dir if empty)",
        default=None,
    )
    destination = Path(dest_text) if dest_text else None
    
    # Step 3: Fresh directory
    config = get_config()
    fresh_dir = prompt_confirm(
        "Create fresh directory with timestamp?",
        default=config.fresh_restore_dir,
    )
    
    # Step 4: Overwrite
    overwrite = prompt_confirm(
        "Allow overwriting existing files?",
        default=False,
    )
    
    # Step 5: Verify
    verify = prompt_confirm(
        "Verify archive before restore?",
        default=config.verify_after_operation,
    )
    
    # Preview
    renderer.render_message("", level="info")
    renderer.render_message("Restore Plan:", level="info")
    renderer.render_message(f"  Archive: {archive}", level="info")
    if destination:
        renderer.render_message(f"  Destination: {destination}", level="info")
    else:
        renderer.render_message(f"  Fresh dir: {fresh_dir}", level="info")
    renderer.render_message(f"  Overwrite: {overwrite}", level="info")
    renderer.render_message(f"  Verify: {verify}", level="info")
    
    if not prompt_confirm("Proceed with restore?", default=True):
        renderer.render_message("Cancelled.", level="warning")
        return EXIT_OK
    
    try:
        spec = build_restore_spec(
            archive=archive,
            destination=destination,
            fresh_dir=fresh_dir,
            confirm_overwrite=not overwrite,  # If overwrite=True, skip confirmation
            overwrite=overwrite,
            verify=verify,
        )
        
        from bkx.core.services import get_restore_service
        service = get_restore_service()
        result = service.extract(spec)
        
        renderer.render_message("", level="info")
        renderer.render_message("Restore completed!", level="success")
        renderer.render_message(f"  Destination: {result.destination_path}", level="info")
        renderer.render_message(f"  Files: {result.files_count}", level="info")
        
        return EXIT_OK
        
    except BkxError as e:
        renderer.render_error(str(e), e.details)
        return error_to_exit_code(e)


def run_transfer_interactive() -> int:
    """Run interactive transfer flow.
    
    Returns:
        Exit code
    """
    renderer = get_renderer(json=False)
    renderer.render_message("=== Interactive Transfer ===", level="info")
    
    # Step 1: Direction
    direction = prompt_select(
        "Transfer direction",
        choices=["push", "pull"],
        default=0,
    )
    if direction is None:
        return EXIT_OK
    
    # Step 2: Source
    source_text = prompt_text("Source path", default=None)
    if source_text is None:
        return EXIT_OK
    source = Path(source_text.strip())
    
    # Step 3: Destination
    dest_text = prompt_text("Destination path", default=None)
    if dest_text is None:
        return EXIT_OK
    destination = Path(dest_text.strip())
    
    # Step 4: Verify
    verify = prompt_confirm("Verify after transfer?", default=True)
    
    # Preview
    renderer.render_message("", level="info")
    renderer.render_message("Transfer Plan:", level="info")
    renderer.render_message(f"  Direction: {direction}", level="info")
    renderer.render_message(f"  Source: {source}", level="info")
    renderer.render_message(f"  Destination: {destination}", level="info")
    renderer.render_message(f"  Verify: {verify}", level="info")
    
    if not prompt_confirm("Proceed with transfer?", default=True):
        renderer.render_message("Cancelled.", level="warning")
        return EXIT_OK
    
    try:
        spec = build_transfer_spec(
            source=source,
            destination=destination,
            direction=direction,
            verify=verify,
        )
        
        from bkx.core.services import get_transfer_service
        service = get_transfer_service()
        result = service.transfer(spec)
        
        renderer.render_message("", level="info")
        renderer.render_message("Transfer completed!", level="success")
        renderer.render_message(f"  Source: {result.source_path}", level="info")
        renderer.render_message(f"  Destination: {result.destination_path}", level="info")
        
        return EXIT_OK
        
    except BkxError as e:
        renderer.render_error(str(e), e.details)
        return error_to_exit_code(e)


def main() -> int:
    """Main interactive entrypoint.
    
    Returns:
        Exit code
    """
    print("BKX Interactive Mode")
    print("=" * 40)
    
    while True:
        operation = select(
            message="What would you like to do?",
            choices=[
                "Backup",
                "Archive",
                "Restore",
                "Transfer",
                "Exit",
            ],
            default=0,
        )
        
        if operation == "Exit" or operation is None:
            print("Goodbye!")
            return EXIT_OK
        
        if operation == "Backup":
            return run_backup_interactive()
        elif operation == "Archive":
            return run_archive_interactive()
        elif operation == "Restore":
            return run_restore_interactive()
        elif operation == "Transfer":
            return run_transfer_interactive()


# Convenience import for questionary functions
from questionary import select, text, confirm, Path as QPath, checkbox

