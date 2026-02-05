"""rcm TUI (Step 8): CSS pack + header context (backend/context/badges/icons)."""

from __future__ import annotations

from pathlib import Path
from typing import Any, ClassVar

from textual import on

from mikomn.tui_base.app import BaseTuiApp
from mikomn.tui_base.models import BadgeData, Command
from mikomn.tui_base.overlays.command_palette import CommandPalette, CommandSelected
from mikomn.tui_base.overlays.help_overlay import HelpOverlay
from mikomn.tui_base.screens.notification_center import NotificationCenterScreen
from mikomn.tui_base.screens.tabs_shell import TabsShellScreen
from mikomn.tui_base.utils.page_registry import PageRegistry, PageSpec
from mikomn.tui_base.utils.screen_manager import NavMethod
from mikomn.tui_base.widgets.footer import AppFooter, TabSelected
from mikomn.tui_base.widgets.header import AppHeader, HeaderIcon, HeaderIconSelected
from mikomn.tui_base.widgets.toast import ToastContainer, ToastManager

from rcm.tui.overlays import RecentLocationsModal
from rcm.tui.views import BrowseView, DashboardView, RemotesView, SearchView, SettingsView


def _css_paths() -> list[str]:
    base = Path(__file__).parent / "css"

    ordered: list[Path] = [
        base / "global.tcss",
        base / "app.tcss",
        base / "layout.tcss",
        base / "mikomn_overrides.tcss",
    ]

    for sub in ("components", "pages", "overlays"):
        d = base / sub
        if d.exists():
            ordered.extend(sorted(d.glob("*.tcss")))

    return [str(p) for p in ordered if p.exists()]


def create_pages() -> PageRegistry:
    pages = PageRegistry()
    pages.register_many(
        [
            PageSpec(route="dashboard", factory=lambda: DashboardView(), label="Dashboard", key="1", icon="🏠", group="Main", group_order=1, order=1),
            PageSpec(route="remotes", factory=lambda: RemotesView(), label="Remotes", key="2", icon="🗂️", group="Main", group_order=1, order=2),
            PageSpec(route="browse", factory=lambda: BrowseView(), label="Browse", key="3", icon="📂", group="Main", group_order=1, order=3),
            PageSpec(route="search", factory=lambda: SearchView(), label="Search", key="4", icon="🔎", group="Tools", group_order=2, order=4),
            PageSpec(route="settings", factory=lambda: SettingsView(), label="Settings", key="5", icon="⚙️", group="Tools", group_order=2, order=5),
        ]
    )
    return pages


class RcmShell(TabsShellScreen):
    """Shell with global toast container."""

    def compose(self):
        yield from super().compose()
        yield ToastContainer(classes="toast-container")

    def on_mount(self) -> None:
        super().on_mount()
        try:
            ToastManager.set_container(self.query_one(ToastContainer))
        except Exception:
            pass


class RcmTuiApp(BaseTuiApp):
    TITLE = "rcm"
    SUB_TITLE = "rclone manager"

    BINDINGS: ClassVar[list[object]] = BaseTuiApp.BINDINGS + [
        ("1", "show_tab('dashboard')", "Dashboard"),
        ("2", "show_tab('remotes')", "Remotes"),
        ("3", "show_tab('browse')", "Browse"),
        ("4", "show_tab('search')", "Search"),
        ("5", "show_tab('settings')", "Settings"),
        ("r", "refresh", "Refresh"),
        ("n", "open_notifications", "Notifications"),
        ("?", "help", "Help"),
        ("ctrl+p", "command_palette", "Commands"),
    ]

    def __init__(self, **kwargs: Any) -> None:
        super().__init__(extra_css_paths=_css_paths(), **kwargs)
        self.pages = create_pages()
        self._active_route: str = "dashboard"
        self._route_context: dict[str, tuple[str, str, list[BadgeData]]] = {}

    def _active_label(self) -> str:
        spec = self.pages.get(self._active_route)
        return spec.label if spec else self._active_route

    def build_header(self) -> AppHeader:
        return AppHeader(
            title="rcm",
            subtitle="",
            backend=self._active_label(),
            context="",
            context_icon="📍",
            extended=True,
            icons=self._header_icons(),
            badges=self._header_base_badges(),
        )

    def build_footer(self) -> AppFooter:
        return AppFooter(dynamic_hints=True, two_line_hints=True, auto_icons=True)

    def register_routes(self) -> None:
        self.nav.register(
            "shell",
            lambda: RcmShell(
                pages=self.pages,
                header=self.build_header(),
                footer=self.build_footer(),
                default_page="dashboard",
            ),
            cache=True,
        )

        self.nav.register(
            "notifications",
            lambda: NotificationCenterScreen(title="rcm Notifications"),
            method=NavMethod.MODAL,
            cache=False,
        )

        self.nav.register(
            "recent_locations",
            lambda: RecentLocationsModal(),
            method=NavMethod.MODAL,
            cache=False,
        )

        self.nav.register(
            "help",
            lambda: HelpOverlay(bindings=self._help_bindings(), title="rcm Help"),
            method=NavMethod.MODAL,
            cache=False,
        )

        self.nav.register(
            "command_palette",
            lambda: CommandPalette(commands=self._palette_commands()),
            method=NavMethod.MODAL,
            cache=False,
        )

    def on_mount(self) -> None:
        self.add_class("rcm")
        super().on_mount()

        def _go() -> None:
            self.nav.goto("shell")
            self._refresh_header()

        self.call_after_refresh(_go)

    @on(TabSelected)
    def _on_tab_selected(self, event: TabSelected) -> None:
        try:
            self._active_route = event.route
            self._refresh_header()
        except Exception:
            pass

    @on(HeaderIconSelected)
    def _on_header_icon_selected(self, event: HeaderIconSelected) -> None:
        action = event.icon.action
        if not action:
            return
        try:
            self.run_action(action)
        except Exception:
            fn = getattr(self, f"action_{action}", None)
            if callable(fn):
                fn()

    def _help_bindings(self):
        return []

    def _header_icons(self) -> list[HeaderIcon]:
        compact = self.has_class("compact")
        icon = "📱" if compact else "🖥️"
        tip = "Layout: compact" if compact else "Layout: expanded"
        return [
            HeaderIcon(id="mode", icon=icon, tooltip=tip, action="toggle_layout_mode", variant="primary"),
            HeaderIcon(id="palette", icon="⌘", tooltip="Commands", action="command_palette", variant="muted"),
            HeaderIcon(id="recent", icon="🕘", tooltip="Recent locations", action="open_recent_locations", variant="muted"),
            HeaderIcon(id="bell", icon="🔔", tooltip="Notifications", action="open_notifications", variant="muted"),
            HeaderIcon(id="help", icon="❓", tooltip="Help", action="help", variant="muted"),
        ]

    def _header_base_badges(self) -> list[BadgeData]:
        compact = self.has_class("compact")
        mode = "COMPACT" if compact else "EXPANDED"
        icon = "📱" if compact else "🖥️"
        badges: list[BadgeData] = [BadgeData(label=mode, variant="primary", icon=icon)]

        if self.has_class("sidebar-collapsed"):
            badges.append(BadgeData(label="SIDEBAR", variant="warning", icon="⫶"))
        if self.has_class("sidebar-force-expanded"):
            badges.append(BadgeData(label="SIDEBAR", variant="success", icon="⫶"))

        return badges

    def update_header_context(
        self,
        *,
        route: str,
        context: str = "",
        icon: str = "📍",
        badges: list[BadgeData] | None = None,
    ) -> None:
        if context:
            self._route_context[route] = (context, icon, list(badges or []))
        else:
            self._route_context.pop(route, None)

        if route == self._active_route:
            self._refresh_header()

    def _refresh_header(self) -> None:
        try:
            header = self.screen.query_one(AppHeader)
        except Exception:
            return

        backend = self._active_label()

        ctx = self._route_context.get(self._active_route)
        if ctx:
            context, icon, extra_badges = ctx
            header.update_status(backend=backend, context=context, context_icon=icon)
        else:
            extra_badges = []
            header.update_status(backend=backend, context="")

        header.set_icons(self._header_icons())
        header.set_badges(self._header_base_badges() + list(extra_badges))

    def action_toggle_layout_mode(self) -> None:
        super().action_toggle_layout_mode()
        self._refresh_header()

    def action_show_tab(self, route: str) -> None:
        try:
            shell = self.screen
            if isinstance(shell, TabsShellScreen):
                shell.goto_page(route)
                self._active_route = route
                self._refresh_header()
        except Exception:
            pass

    def action_refresh(self) -> None:
        self._invoke_page_action(self._active_route, "refresh_view")

    def _invoke_page_action(self, route: str, method: str) -> None:
        try:
            page = self.screen.query_one(f"#{route}")
        except Exception:
            return
        fn = getattr(page, method, None)
        if callable(fn):
            fn()
            return
        if method.startswith("palette:"):
            act = method.split(":", 1)[1]
            handler = getattr(page, "handle_palette_action", None)
            if callable(handler):
                handler(act)

    def _palette_commands(self) -> list[Command]:
        cur = self._active_route

        cmds: list[Command] = [
            Command(id="app:help", title="Help", group="App", meta={"app_action": "help"}),
            Command(id="app:notifications", title="Notifications", group="App", meta={"app_action": "open_notifications"}),
            Command(id="app:recent", title="Recent: Open…", group="App", meta={"app_action": "open_recent_locations"}),
            Command(id="app:refresh", title="Refresh", group="App", meta={"app_action": "refresh"}),
            Command(id="app:quit", title="Quit", group="App", meta={"app_action": "quit"}),
        ]

        for spec in self.pages.all():
            if spec.route == cur:
                continue
            cmds.append(Command(id=f"nav:{spec.route}", title=f"Go: {spec.label}", group="Navigate", meta={"route": spec.route}))

        if cur == "remotes":
            cmds += [
                Command(id="remotes:mount", title="Mount Selected", group="Remotes", meta={"page_action": "mount_selected"}),
                Command(id="remotes:unmount", title="Unmount Selected", group="Remotes", meta={"page_action": "unmount_selected"}),
            ]
        elif cur == "browse":
            cmds += [
                Command(id="browse:back", title="Back", group="Browse", meta={"page_action": "nav_back"}),
                Command(id="browse:up", title="Up", group="Browse", meta={"page_action": "nav_up"}),
                Command(id="browse:reload", title="Reload (clear cache)", group="Browse", meta={"page_action": "reload"}),
                Command(id="browse:rec", title="Toggle Recursive", group="Browse", meta={"page_action": "toggle_recursive"}),
                Command(id="browse:copy", title="Copy remote:path", group="Browse", meta={"page_action": "copy_path"}),
                Command(id="browse:open", title="Open local path (if mounted)", group="Browse", meta={"page_action": "open_path"}),
            ]
        elif cur == "search":
            cmds += [
                Command(id="search:run", title="Run Search", group="Search", meta={"page_action": "run_search"}),
                Command(id="search:clear", title="Clear Query", group="Search", meta={"page_action": "clear_query"}),
                Command(id="search:toggle_display", title="Toggle Full/Base display", group="Search", meta={"page_action": "toggle_display"}),
                Command(id="search:copy", title="Copy remote:path", group="Search", meta={"page_action": "copy_path"}),
                Command(id="search:open", title="Open local path (if mounted)", group="Search", meta={"page_action": "open_path"}),
            ]

        return cmds

    @on(CommandSelected)
    def _on_command_selected(self, event: CommandSelected) -> None:
        cmd = getattr(event, "command", None)
        meta = getattr(cmd, "meta", None)
        if not isinstance(meta, dict):
            return

        route = meta.get("route")
        page_action = meta.get("page_action")
        app_action = meta.get("app_action")

        if isinstance(route, str):
            self.action_show_tab(route)
            return

        if isinstance(app_action, str):
            fn = getattr(self, f"action_{app_action}", None)
            if callable(fn):
                fn()
            return

        if isinstance(page_action, str):
            self._invoke_page_action(self._active_route, f"palette:{page_action}")

    def action_open_notifications(self) -> None:
        self.nav.goto("notifications")

    def action_open_recent_locations(self) -> None:
        self.nav.goto("recent_locations")

    def action_help(self) -> None:
        self.nav.goto("help")

    def action_command_palette(self) -> None:
        self.nav.goto("command_palette")

    def toast(self, message: str, *, level: str = "info") -> None:
        try:
            if level == "error":
                ToastManager.error(message)
            elif level == "warning":
                ToastManager.warning(message)
            elif level == "success":
                ToastManager.success(message)
            else:
                ToastManager.info(message)
            return
        except Exception:
            pass
        self.notify(message)


def main() -> None:
    RcmTuiApp().run()
