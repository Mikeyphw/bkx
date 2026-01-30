"""BKX CLI entrypoint.

This is the **required** product interface.

Contract rules:
- Grammar: <tool> <noun> <verb>
- Must remain usable without interactive/TUI extras.

Implementation starts in Phase 2.
"""

from __future__ import annotations

import typer

app = typer.Typer(no_args_is_help=True, add_completion=False)


def main() -> None:
    """Console script entrypoint."""
    app()
