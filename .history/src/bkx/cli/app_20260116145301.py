"""BKX CLI entrypoint.

This is the **required** product interface.

Contract rules:
- Grammar: <tool> <noun> <verb>
- Must remain usable without interactive/TUI extras.

Implementation starts in Phase 2.
"""

from __future__ import annotations

from pathlib import Path

import typer
from typer import Option

app = typer.Typer(no_args_is_help=True, add_completion=False)


@app.callback(invoke_without_subcommand=True)
def main_callback(
    json: bool = Option(False, "--json", help="Output results as JSON"),
    dry_run: bool = Option(False, "--dry-run", "-n", help="Show what would be done without executing"),
) -> None:
    """BKX - Contract-controlled CLI-first backup/archive tool."""
    pass


@app.command()
def backup(
    sources: list[Path],
    destination: Path,
    json: bool = False,
    dry_run: bool = False,
) -> None:
    """Create a backup."""
    pass


@app.command()
def archive(
    sources: list[Path],
    output: Path,
    json: bool = False,
    dry_run: bool = False,
) -> None:
    """Create an archive."""
    pass


@app.command()
def transfer(
    source: Path,
    destination: str,
    direction: str = "push",
    json: bool = False,
    dry_run: bool = False,
) -> None:
    """Transfer files."""
    pass


@app.command()
def restore(
    archive: Path,
    destination: Path,
    json: bool = False,
    dry_run: bool = False,
) -> None:
    """Restore from backup."""
    pass


@app.command()
def tools(
    subcommand: str = "detect",
    json: bool = False,
) -> None:
    """Tool management commands."""
    pass


def main() -> None:
    """Console script entrypoint."""
    app()
