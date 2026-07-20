from __future__ import annotations

from textual.app import ComposeResult
from textual.screen import ModalScreen
from textual.widgets import Static


class BkxCommandPalette(ModalScreen):
    """Legacy Textual command palette placeholder.

    Runtime command entries are now provided by ``mikomn.tui`` action specs.
    """

    DEFAULT_CLASSES = "command-palette bkx-command-palette"

    def __init__(self, commands: list[object] | None = None, **kwargs) -> None:
        super().__init__(**kwargs)
        self.commands = list(commands or [])

    def compose(self) -> ComposeResult:
        yield Static("Commands", classes="command-palette-title")
