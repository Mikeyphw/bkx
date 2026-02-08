"""bkx/src/bkx/tui/widgets/key_hints.py


A compact key-hints strip shown above the footer tabs.

UI-only: renders text that the App computes from bindings/context.

"""

from __future__ import annotations

from textual.app import ComposeResult
from textual.reactive import reactive
from textual.widget import Widget
from textual.widgets import Label


class KeyHintsBar(Widget):
    """A single-line key hints strip."""

    DEFAULT_CLASSES = "bkx-key-hints"

    text: reactive[str] = reactive("")

    def compose(self) -> ComposeResult:
        yield Label("", id="bkx-key-hints-label")

    def watch_text(self, value: str) -> None:
        try:
            self.query_one("#bkx-key-hints-label", Label).update(value)
        except Exception:
            pass

    def set_text(self, value: str) -> None:
        self.text = value

