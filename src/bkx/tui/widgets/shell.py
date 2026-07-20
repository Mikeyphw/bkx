"""Legacy Textual shell widget.

The active TUI runtime is built from ``mikomn.tui.TuiApp`` in ``bkx.tui.app``.
This shell remains only for older widget-level tests and historical pages.
"""

from __future__ import annotations

from typing import Any

from textual.app import ComposeResult
from textual.containers import Container, Horizontal, Vertical
from textual.widgets import ContentSwitcher, Footer, Header, Static

from bkx.tui.legacy import PageRegistry
from bkx.tui.widgets.run_panel import RunPanel


class BkxShell(Container):
    """Persistent legacy shell widget."""

    DEFAULT_CLASSES = "bkx-shell"

    def __init__(self, *, pages: PageRegistry) -> None:
        super().__init__()
        self._pages = pages
        self._switcher: ContentSwitcher | None = None
        self._run_panel: RunPanel | None = None
        self._page_cache: dict[str, Any] = {}
        self._footer_message: str | None = None

    def compose(self) -> ComposeResult:
        yield Header(show_clock=True)
        with Horizontal(classes="bkx-shell-panes"):
            yield Static("BKX", id="bkx-sidebar", classes="bkx-sidebar")
            with Vertical(classes="bkx-content-region"):
                self._switcher = ContentSwitcher(classes="bkx-switcher")
                yield self._switcher
            self._run_panel = RunPanel(classes="bkx-run-panel-region")
            yield self._run_panel
        yield Footer()

    def on_mount(self) -> None:
        for spec in self._pages.specs():
            if not spec.disabled:
                self.goto_page(spec.route)
                break

    def goto_page(self, route: str) -> None:
        if not self._switcher:
            return
        spec = self._pages.get(route)
        if not spec:
            return
        if route not in self._page_cache:
            widget = spec.factory()
            widget.id = route
            self._page_cache[route] = widget
            self._switcher.mount(widget)

        def _set_current() -> None:
            if self._switcher:
                self._switcher.current = route

        self.call_after_refresh(_set_current)

    def set_footer_message(self, message: str | None) -> None:
        self._footer_message = message

    @property
    def run_panel(self) -> RunPanel | None:
        return self._run_panel
