"""Interactive mode entrypoint.

This module provides the interactive CLI experience using prompts and menus.
Uses mikomn for theme-aware prompts and menus.

Install via: pip install .[interactive]

Contract rules:
- Interactive uses the same Spec builders as CLI (parity-safe)
- No business logic in prompts - only input collection
- Interactive can be deleted with no refactor required
"""

from __future__ import annotations

from pathlib import Path

# mikomn imports for interactive prompts
from mikomn.cli.base.interactive import (
    MenuConfig,
    MenuItem,
    ask_confirm,
    ask_path,
    ask_text,
    banner,
)
from mikomn.cli.base.menus import show_menu
from mikomn.cli.base.ui2.exceptions import NonInteractiveInput, UserCancelled
from rich.prompt import Prompt

from bkx.cli.exit_codes import EXIT_OK, error_to_exit_code
from bkx.cli.render import get_renderer
from bkx.cli.interface_dispatch import build_spec_from_interface_values
from bkx.core.config import get_config
from bkx.core.errors import BkxError


def prompt_path(prompt_text: str, must_exist: bool = True, default: str | None = None) -> Path | None:
    """Prompt for a path with validation.

    Returns None if cancelled or non-interactive.
    """
    try:
        default_path = Path(default).expanduser() if default else None
        return ask_path(prompt_text, default=default_path, must_exist=must_exist)
    except (UserCancelled, NonInteractiveInput):
        return None


def prompt_text(prompt_text: str, default: str | None = None) -> str | None:
    """Prompt for text input.

    Returns None if cancelled or non-interactive.
    """
    try:
        result = ask_text(prompt_text, default=default)
    except (UserCancelled, NonInteractiveInput):
        return None
    value = (result or "").strip()
    if value == "":
        return default
    return value


def prompt_confirm(prompt_text: str, default: bool = False) -> bool:
    """Prompt for yes/no confirmation (False if cancelled/non-interactive)."""
    try:
        return bool(ask_confirm(prompt_text, default=default))
    except (UserCancelled, NonInteractiveInput):
        return False


def prompt_select(
    prompt_text: str,
    choices: list[str],
    default: int = 0,
) -> str | None:
    """Prompt for selection from a list.

    Returns None if cancelled/non-interactive.
    """
    menu_items = [MenuItem(label=choice, value=choice) for choice in choices]
    cfg = MenuConfig(title=prompt_text, prompt="Choice", allow_quit=True)

    def _prompt_ask(text: str, **kwargs) -> str:
        # Prefer Enter -> default selection (1-indexed) while preserving quit via 'q'.
        kwargs["default"] = str(default + 1)
        return Prompt.ask(text, **kwargs)

    try:
        return show_menu(menu_items, config=cfg, prompt_ask=_prompt_ask)  # type: ignore[arg-type]
    except (UserCancelled, NonInteractiveInput):
        return None


def prompt_multiselect(
    prompt_text: str,
    choices: list[str],
) -> list[str]:
    """Prompt for multiple selections.

    Returns empty list if cancelled/non-interactive.
    """
    selected: list[str] = []
    for choice in choices:
        try:
            is_selected = ask_confirm(f"{prompt_text} - {choice}", default=False)
        except (UserCancelled, NonInteractiveInput):
            return []
        if is_selected:
            selected.append(choice)
    return selected


def _section_header(title: str) -> None:
    """Print a styled section header.

    Args:
        title: Header text
    """
    banner(title)


def run_backup_interactive() -> int:
    """Run interactive backup flow.

    Returns:
        Exit code
    """
    renderer = get_renderer(json_output=False)
    _section_header("Interactive Backup")

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
        default=["zstd", "gzip", "lz4", "xz", "bzip2", "none"].index(config.compression_algorithm)
        if config.compression_algorithm in ["zstd", "gzip", "lz4", "xz", "bzip2"]
        else 0,
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
        spec = build_spec_from_interface_values(
            "backup.create",
            {
                "sources": sources,
                "destination": destination,
                "archive_name": archive_name,
                "excludes": excludes,
                "algorithm": algorithm,
                "level": level,
                "verify": verify,
            },
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
    renderer = get_renderer(json_output=False)
    _section_header("Interactive Archive")

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
        default=["zstd", "gzip", "lz4", "xz", "bzip2", "none"].index(config.compression_algorithm)
        if config.compression_algorithm in ["zstd", "gzip", "lz4", "xz", "bzip2"]
        else 0,
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
        spec = build_spec_from_interface_values(
            "archive.create",
            {
                "sources": sources,
                "output": output,
                "excludes": excludes,
                "algorithm": algorithm,
                "verify": verify,
            },
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
    renderer = get_renderer(json_output=False)
    _section_header("Interactive Restore")

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
        spec = build_spec_from_interface_values(
            "restore.extract",
            {
                "archive": archive,
                "destination": destination,
                "fresh_dir": fresh_dir,
                "confirm_overwrite": not overwrite,
                "overwrite": overwrite,
                "verify": verify,
            },
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
    renderer = get_renderer(json_output=False)
    _section_header("Interactive Transfer")

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
    source_raw = source_text.strip()
    source = source_raw if "://" in source_raw else Path(source_raw)

    # Step 3: Destination
    dest_text = prompt_text("Destination path", default=None)
    if dest_text is None:
        return EXIT_OK
    destination_raw = dest_text.strip()
    destination = destination_raw if "://" in destination_raw else Path(destination_raw)

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
        spec = build_spec_from_interface_values(
            "transfer.run",
            {
                "source": source,
                "destination": destination,
                "direction": direction,
                "verify": verify,
            },
        )

        from bkx.core.services import get_transfer_service

        service = get_transfer_service()
        result = service.push(spec) if direction == "push" else service.pull(spec)

        renderer.render_message("", level="info")
        renderer.render_message("Transfer completed!", level="success")
        renderer.render_message(f"  Source: {result.source}", level="info")
        renderer.render_message(f"  Destination: {result.destination}", level="info")

        return EXIT_OK

    except BkxError as e:
        renderer.render_error(str(e), e.details)
        return error_to_exit_code(e)


def main() -> int:
    """Main interactive entrypoint.

    Returns:
        Exit code
    """
    _section_header("BKX Interactive Mode")

    while True:
        operation = prompt_select(
            "What would you like to do?",
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
            renderer = get_renderer(json_output=False)
            renderer.render_message("Goodbye!", level="info")
            return EXIT_OK

        if operation == "Backup":
            return run_backup_interactive()
        elif operation == "Archive":
            return run_archive_interactive()
        elif operation == "Restore":
            return run_restore_interactive()
        elif operation == "Transfer":
            return run_transfer_interactive()
