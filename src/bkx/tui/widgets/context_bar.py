"""bkx/src/bkx/tui/widgets/context_bar.py

Single-line context strip shown under the header.

UI-only: this widget only renders text; the app/shell decides semantics.
"""

from __future__ import annotations

from textual.app import ComposeResult
from textual.reactive import reactive
from textual.widget import Widget
from textual.widgets import Label


class ContextBar(Widget):
    """A single-line context strip."""

    DEFAULT_CLASSES = "bkx-context-bar"

    text: reactive[str] = reactive("")

    def compose(self) -> ComposeResult:
        yield Label("", id="bkx-context-bar-label")

    def watch_text(self, value: str) -> None:
        try:
            self.query_one("#bkx-context-bar-label", Label).update(value)
        except Exception:
            pass

    def set_text(self, value: str) -> None:
        self.text = value

