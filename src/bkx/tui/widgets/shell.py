"""bkx/src/bkx/tui/widgets/shell.py

Hybrid shell layout:
- Desktop/expanded: sidebar + page + always-visible run panel
- Compact/termux: footer tabs + page + always-visible run panel (sidebar hidden)

Uses mikomn widgets/layouts to maximize reuse.
"""

from __future__ import annotations

from typing import Any

from textual import on
from textual.app import ComposeResult
from textual.containers import Container
from textual.widgets import ContentSwitcher

from mikomn.tui_base.layouts.header_body_footer import HeaderBodyFooterLayout
from mikomn.tui_base.containers.side_panes import SidePanes
from mikomn.tui_base.containers.sidebar_region import SidebarRegion
from mikomn.tui_base.utils.page_registry import PageRegistry
from mikomn.tui_base.widgets.sidebar import Sidebar, SidebarItem
from mikomn.tui_base.widgets.footer import AppFooter, FooterTab, TabSelected
from mikomn.tui_base.widgets.header import AppHeader

from bkx.tui.widgets.run_panel import RunPanel


class BkxShell(Container):
    """Persistent shell widget."""

    DEFAULT_CLASSES = "bkx-shell"

    def __init__(self, *, pages: PageRegistry) -> None:
        super().__init__()
        self._pages = pages
        self._switcher: ContentSwitcher | None = None
        self._sidebar: Sidebar | None = None
        self._footer: AppFooter | None = None
        self._run_panel: RunPanel | None = None
        self._page_cache: dict[str, Any] = {}

    def compose(self) -> ComposeResult:
        yield HeaderBodyFooterLayout(classes="bkx-shell-layout")

    def on_mount(self) -> None:
        self._mount_shell_widgets()

    def _mount_shell_widgets(self) -> None:
        try:
            header_slot = self.query_one("#header")
            body_slot = self.query_one("#body")
            footer_slot = self.query_one("#footer")
        except Exception:
            self.call_after_refresh(self._mount_shell_widgets)
            return

        header_slot.mount(AppHeader(title="BKX", subtitle="backup tool", classes="bkx-header"))

        specs = list(self._pages.specs())
        sidebar_items = [
            SidebarItem(id=s.route, label=s.label, icon=s.icon or "•", key=s.key, disabled=bool(s.disabled))
            for s in specs
        ]
        self._sidebar = Sidebar(items=sidebar_items, title="BKX", id="bkx-sidebar", classes="bkx-sidebar")

        sidebar_region = SidebarRegion(classes="bkx-sidebar-region")
        sidebar_region.mount_sidebar(self._sidebar)

        self._switcher = ContentSwitcher(classes="bkx-switcher")
        content_region = Container(self._switcher, classes="bkx-content-region")

        self._run_panel = RunPanel(classes="bkx-run-panel-region")
        run_region = Container(self._run_panel, classes="bkx-run-panel-region")

        panes = SidePanes(classes="bkx-shell-panes", orientation="auto")
        panes.mount(sidebar_region)
        panes.mount(content_region)
        panes.mount(run_region)
        body_slot.mount(panes)

        footer_tabs = [
            FooterTab(key=s.key or "", label=s.label, action=f"goto:{s.route}", icon=s.icon, id=s.route)
            for s in specs
            if not s.disabled
        ]
        self._footer = AppFooter(tabs=footer_tabs, classes="bkx-footer-tabs")
        footer_slot.mount(self._footer)

        for s in specs:
            if not s.disabled:
                self.goto_page(s.route)
                break

    @on(Sidebar.Selected)
    def _on_sidebar_selected(self, event: Sidebar.Selected) -> None:
        self.goto_page(event.item_id)

    @on(TabSelected)
    def _on_tab_selected(self, event: TabSelected) -> None:
        action = (event.action or "").strip()
        if action.startswith("goto:"):
            self.goto_page(action.split(":", 1)[1])

    def goto_page(self, route: str) -> None:
        if not self._switcher:
            return
        spec = self._pages.get(route)
        if not spec:
            return

        if route not in self._page_cache:
            widget = spec.factory()
            self._page_cache[route] = widget
            self._switcher.mount(widget)
            widget.id = route  # ContentSwitcher uses child ids for switching

        self._switcher.current = route

        if self._sidebar:
            try:
                self._sidebar.set_active(route)
            except Exception:
                pass

        if self._footer:
            try:
                enabled = [s.route for s in self._pages.specs() if not s.disabled]
                self._footer.set_active_tab(enabled.index(route))
            except Exception:
                pass

    @property
    def run_panel(self) -> RunPanel | None:
        return self._run_panel

