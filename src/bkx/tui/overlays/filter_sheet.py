from __future__ import annotations

from collections.abc import Callable

from textual.app import ComposeResult
from textual.screen import ModalScreen
from textual.widgets import Static


class BkxFilterSheet(ModalScreen):
    """Legacy Textual filter sheet placeholder."""

    DEFAULT_CLASSES = "filter-sheet bkx-filter-sheet"

    def __init__(
        self,
        filter_groups: list[object] | None = None,
        on_apply: Callable[[list[object]], None] | None = None,
        title: str = "Filters",
        **kwargs,
    ) -> None:
        super().__init__(**kwargs)
        self.filter_groups = list(filter_groups or [])
        self.on_apply = on_apply
        self.title = title

    def compose(self) -> ComposeResult:
        yield Static(self.title, classes="filter-sheet-title")
