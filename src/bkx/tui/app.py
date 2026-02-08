"""Textual TUI application.

This module provides the TUI experience using Textual and mikomn's BaseTuiApp.
Uses mikomn's unified theme system and TUI widgets.

Install via: pip install .[tui]

Contract rule:
- TUI is opt-in and removable.
- Message-driven; no business logic in widgets.
- App dispatches Intents to services, never performs business logic.
"""

# mikomn TUI base for theme cycling, layout modes, and platform behavior
import uuid
from typing import Any

from mikomn.tui_base import BaseTuiApp
from mikomn.tui_base.css_registry import reload_css
from mikomn.tui_base.models import Command, FilterGroup, FilterOption, ToastData
from mikomn.tui_base.notifications import NotificationHistory
from mikomn.tui_base.overlays import CommandSelected

try:
    from mikomn.tui_base.screens.notification_center import NotificationSelected
except Exception:  # pragma: no cover
    NotificationSelected = None  # type: ignore[assignment]
from mikomn.tui_base.widgets import DebugOverlay
from mikomn.tui_base.widgets.toast import ToastContainer, ToastManager
from textual.app import App, ComposeResult

# Theme integration
from bkx.cli.spec_builders import (
    build_archive_spec,
    build_backup_spec,
    build_restore_spec,
    build_transfer_spec,
)
from bkx.core.errors import BkxError
from bkx.core.services import (
    get_archive_service,
    get_backup_service,
    get_restore_service,
    get_transfer_service,
)
from bkx.tui.messages import (
    ArchiveIntent,
    BackupIntent,
    ClearBusy,
    ClearEmptyState,
    ClearRunLog,
    CycleTheme,
    DumpDebugSnapshot,
    FilterChanged,
    Intent,
    ItemsSelected,
    NavigateToScreen,
    OpenNotificationCenter,
    ProgressCompleted,
    ProgressStarted,
    ProgressUpdate,
    ReloadCss,
    RequestEmptyState,
    RestoreIntent,
    SetBusy,
    SetTheme,
    ShowCommandPalette,
    ShowNotification,
    ShowToast,
    ToggleDebugOverlay,
    ToggleToasts,
    TransferIntent,
    UpdateLog,
)
from bkx.tui.overlays import BkxCommandPalette, BkxHelpOverlay, BkxNotificationCenterScreen
from bkx.tui.overlays.filter_sheet import BkxFilterSheet
from bkx.tui.pages import create_page_registry

# BKX components
from bkx.tui.widgets.shell import BkxShell
from bkx.ui.theme import (
    cycle_theme,
    enable_theme_persistence,
    get_current_theme_name,
    get_theme_names,
    is_theme_persistence_enabled,
    save_theme_preference,
    set_theme,
)
from bkx.ui.theme import (
    off_theme_change as bkx_off_theme_change,
)
from bkx.ui.theme import (
    on_theme_change as bkx_on_theme_change,
)

# Patch Textual default CSS to satisfy hatch parser expectations.
App.DEFAULT_CSS = App.DEFAULT_CSS.replace(
    "hatch: right $panel;",
    "hatch: none;",
)


class BkxTuiApp(BaseTuiApp):
    """BKX Textual TUI Application.

    Extends mikomn's BaseTuiApp for:
    - Theme cycling (t key)
    - Command palette (Ctrl+K)
    - Layout modes (Ctrl+L)
    - Platform-aware CSS
    - Focus management
    - mikomn theme registration with Textual

    Message-driven architecture:
    - Widgets emit Intents
    - App handles Intents and dispatches to services
    - Results posted back as messages
    """

    # BKX-specific bindings in addition to BaseTuiApp bindings
    BINDINGS = BaseTuiApp.BINDINGS + [
        ("1", "navigate_backup", "Backup"),
        ("2", "navigate_archive", "Archive"),
        ("3", "navigate_restore", "Restore"),
        ("4", "navigate_transfer", "Transfer"),
        ("5", "navigate_tools", "Tools"),
        ("6", "navigate_logs", "Logs"),
        ("ctrl+p", "command_palette", "Commands"),
        ("ctrl+n", "notification_center", "Notifications"),
        ("N", "toggle_toasts", "Toasts"),
        ("?", "help", "Help"),
        ("g l", "jump_last_run", "Last run"),
    ]

    def __init__(self, **kwargs) -> None:
        from bkx.tui.css import get_css_paths

        theme_name = kwargs.pop("theme_name", None) or get_current_theme_name()
        extra_css_paths = kwargs.pop("extra_css_paths", None) or get_css_paths()

        super().__init__(theme_name=theme_name, extra_css_paths=extra_css_paths, **kwargs)
        self._pages = create_page_registry()
        self._theme_name = theme_name
        self._theme_callback_id = None
        self._show_toasts = True
        self._debug_overlay: DebugOverlay | None = None
        self._progress: dict[str, tuple[int, int, str]] = {}
        self._notification_links: dict[str, dict[str, str]] = {}
        self._last_run_link: dict[str, str] | None = None
        self._last_run_label: str = ""

    def get_theme_name(self) -> str:
        """Get current theme name for mikomn integration."""
        return self._theme_name

    def get_theme_transitions_css(self) -> str:
        """Return empty string to disable web CSS transitions.

        Theme transitions are designed for web CSS ([data-theme] selectors)
        and are not compatible with Textual TCSS.
        """
        return ""

    def cycle_theme_local(self) -> str:
        """Cycle theme and update internal state."""
        self._theme_name = cycle_theme()
        return self._theme_name

    def _apply_theme_tcss(self, theme_name: str) -> None:
        """Apply a theme to the Textual app.

        bkx's theme preference system (bkx.ui.theme) is the source of truth.
        This method bridges it into mikomn's BaseTuiApp theme application.
        """
        try:
            self.action_set_theme(theme_name)
        except Exception:
            try:
                self.theme = theme_name
            except Exception:
                pass

    async def on_mount(self) -> None:
        """Called when app is mounted."""
        # Parent's on_mount handles theme registration and CSS loading
        # Note: super().on_mount() is not async
        super().on_mount()

        # Ensure CSS variables are populated even if no themes are registered.
        self.stylesheet.set_variables(self.get_css_variables())

        # Enable theme persistence for TUI
        enable_theme_persistence(True)

        # Toast container is mounted in compose(); link it to the global manager.
        try:
            ToastManager.set_container(self.query_one("#toast-container", ToastContainer))
        except Exception:
            pass

        # Initialize debug overlay if available
        if DebugOverlay is not None:
            try:
                self._debug_overlay = self.query_one("#debug-overlay", DebugOverlay)
                # Hide debug overlay by default
                self._debug_overlay.styles.display = "none"
            except Exception:
                self._debug_overlay = None

        # Log startup via run panel
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.write("BKX TUI started")

        # Register theme change callback to update TCSS
        def _on_theme_changed(new_theme: str):
            self._theme_name = new_theme
            self.call_after_refresh(self._apply_theme_tcss, new_theme)
            if is_theme_persistence_enabled():
                save_theme_preference(new_theme)

        self._theme_callback_id = _on_theme_changed
        bkx_on_theme_change(_on_theme_changed)

    def _get_run_panel(self):
        """Return the mounted RunPanel (if available)."""
        try:
            shell = self.query_one(BkxShell)
            return shell.run_panel
        except Exception:
            return None

    def _get_logs_pane(self):
        """Return the expanded logs pane (if available)."""
        try:
            from bkx.tui.panes.log import RunLogPane

            return self.query_one("#bkx-logs-log", RunLogPane)
        except Exception:
            return None

    def _write_to_logs(self, message: str, level: str = "info") -> None:
        """Write to the persistent Run Panel and the expanded Logs pane."""
        run_panel = self._get_run_panel()
        if run_panel:
            try:
                run_panel.write(message, level=level)
            except Exception:
                run_panel.write(message)

        logs_pane = self._get_logs_pane()
        if logs_pane:
            try:
                logs_pane.write(message, level=level)
            except Exception:
                logs_pane.write(message)

    def on_unmount(self) -> None:
        """Called when app is unmounted."""
        if self._theme_callback_id:
            bkx_off_theme_change(self._theme_callback_id)
        # Call parent's on_unmount if it exists
        try:
            super().on_unmount()
        except AttributeError:
            pass

    def compose(self) -> ComposeResult:
        """Compose BKX shell (responsive sidebar + compact tabs)."""
        yield BkxShell(pages=self._pages)
        yield ToastContainer(id="toast-container")

    # =========================================================================
    # Overlay helpers (Step 4A)
    # =========================================================================

    def _shell(self) -> BkxShell | None:
        try:
            return self.query_one(BkxShell)
        except Exception:
            return None

    def _push_modal(self, screen) -> None:
        """Push a modal screen and restore focus on dismiss (best-effort)."""
        prev_focus = getattr(self, "focused", None)

        def _restore(_: object | None = None) -> None:
            if prev_focus is None:
                return
            try:
                self.call_after_refresh(lambda: prev_focus.focus())
            except Exception:
                pass

        try:
            self.push_screen(screen, _restore)  # type: ignore[arg-type]
        except TypeError:
            self.push_screen(screen)  # type: ignore[arg-type]
        except Exception:
            _restore()

    def _sync_toasts_indicator(self) -> None:
        shell = self._shell()
        if not shell:
            return
        shell.set_footer_message(None if self._show_toasts else "Toasts OFF")

    def _sync_key_hints(self, route: str) -> None:
        """Update key hints for the current route.

        Supports optional per-widget hints via `focused.bkx_key_hints()`.
        """
        shell = self._shell()
        if not shell:
            return

        nav = "[1] Backup  [2] Archive  [3] Restore  [4] Transfer"
        route_hints = {
            "backup": nav,
            "archive": nav,
            "restore": nav,
            "transfer": nav,
            "tools": nav,
            "logs": "[g l] Last run",
        }
        parts: list[str] = []
        base = route_hints.get(route)
        if base:
            parts.append(base)

        if getattr(self, "_last_run_link", None):
            if "[g l] Last run" not in "  ".join(parts):
                parts.append("[g l] Last run")

        # Focused widget may contribute additional hints.
        try:
            focused = getattr(self, "focused", None)
            get_hints = getattr(focused, "bkx_key_hints", None)
            if callable(get_hints):
                extra = get_hints()
                if isinstance(extra, str) and extra.strip():
                    parts.append(extra.strip())
                elif isinstance(extra, (list, tuple)):
                    for item in extra:
                        if isinstance(item, str) and item.strip():
                            parts.append(item.strip())
        except Exception:
            pass

        parts.append("[Ctrl+P] Commands  [?] Help")
        text = "  ".join(dict.fromkeys(parts)) or "[Ctrl+P] Commands  [?] Help"
        try:
            shell.set_key_hints(text)
        except Exception:
            pass

    def _goto_page(self, route: str) -> None:
        """Navigate to a shell page route."""
        try:
            shell = self.query_one(BkxShell)
            shell.goto_page(route)
            self._sync_key_hints(route)
            try:
                shell.set_context(self._route_label(route))
            except Exception:
                pass
        except Exception:
            run_panel = self._get_run_panel()
            if run_panel:
                run_panel.write(f"Unknown page: {route}")
            self.notify(f"Unknown page: {route}", severity="warning")

    def _route_label(self, route: str) -> str:
        """Resolve a route to a PageSpec label; fallback to the route string."""
        try:
            for spec in list(self._pages.specs()):
                if getattr(spec, "route", None) == route:
                    return str(getattr(spec, "label", route) or route)
        except Exception:
            pass
        return route

    # =========================================================================
    # Navigation Actions
    # =========================================================================

    def action_navigate_backup(self) -> None:
        """Navigate to backup page."""
        self._goto_page("backup")

    def action_navigate_archive(self) -> None:
        """Navigate to archive page."""
        self._goto_page("archive")

    def action_navigate_restore(self) -> None:
        """Navigate to restore page."""
        self._goto_page("restore")

    def action_navigate_transfer(self) -> None:
        """Navigate to transfer page."""
        self._goto_page("transfer")

    def action_navigate_tools(self) -> None:
        """Navigate to tools page."""
        self._goto_page("tools")

    def action_navigate_logs(self) -> None:
        """Navigate to logs page."""
        self._goto_page("logs")

    def action_toggle_toasts(self) -> None:
        """Toggle toast rendering (intent-driven)."""
        self.post_message(ToggleToasts())

    def action_toggle_debug(self) -> None:
        """Toggle debug overlay visibility (intent-driven)."""
        self.post_message(ToggleDebugOverlay())

    def action_dump_debug(self) -> None:
        """Dump debug information to file (intent-driven)."""
        self.post_message(DumpDebugSnapshot())

    def action_cycle_theme(self) -> None:
        """Cycle to the next theme (intent-driven)."""
        self.post_message(CycleTheme())

    def _set_theme(self) -> None:
        """Set the current theme after themes are registered."""
        try:
            self.theme = self._theme_name
        except Exception:
            pass  # Don't let theme errors break the app
        self.call_after_refresh(self._apply_theme_tcss, self._theme_name)

    # =========================================================================
    # Overlays
    # =========================================================================

    def _handle_show_command_palette_intent(self) -> None:
        """Show the command palette overlay."""
        self._push_modal(BkxCommandPalette(commands=self.build_command_palette_commands()))

    def _handle_open_notification_center_intent(self) -> None:
        """Open the notification center (history) overlay."""
        self._push_modal(BkxNotificationCenterScreen(title="BKX Notifications"))

    def _handle_reload_css_intent(self) -> None:
        """Reload TCSS for the current layout."""
        reload_css(
            self,
            layout_mode=self.layout_mode,
            extra_paths=self.get_extra_css_paths(),
            exclude_files=self.get_css_excludes(),
        )

        self.notify("CSS reloaded", severity="info")

    def _handle_clear_run_log_intent(self) -> None:
        """Clear run/log panes."""
        run_panel = self._get_run_panel()
        if run_panel:
            try:
                run_panel.clear_log()
            except Exception:
                pass

        logs_pane = self._get_logs_pane()
        if logs_pane:
            for attr in ("clear_log", "clear"):
                fn = getattr(logs_pane, attr, None)
                if callable(fn):
                    try:
                        fn()
                    except Exception:
                        pass
                    break

        self.notify("Run log cleared", severity="info")

    def _handle_toggle_toasts_intent(self) -> None:
        """Enable/disable toast rendering."""
        self._show_toasts = not self._show_toasts
        status = "enabled" if self._show_toasts else "disabled"

        try:
            container = self.query_one("#toast-container", ToastContainer)
            if not self._show_toasts:
                for meth in ("remove_all", "clear", "dismiss_all"):
                    fn = getattr(container, meth, None)
                    if callable(fn):
                        fn()
                        break
                container.display = False
            else:
                container.display = True
        except Exception:
            pass

        self._sync_toasts_indicator()
        self.notify(f"Toasts {status}", severity="info")

    def _handle_toggle_debug_overlay_intent(self) -> None:
        """Toggle debug overlay visibility."""
        if self._debug_overlay is None:
            if DebugOverlay is not None:
                self._debug_overlay = DebugOverlay(id="debug-overlay", display=True)
                self.mount(self._debug_overlay)
                self.notify("Debug overlay enabled", severity="info")
            else:
                self.notify("Debug overlay not available", severity="warning")
            return

        current_display = self._debug_overlay.display
        self._debug_overlay.display = not current_display
        status = "shown" if not current_display else "hidden"
        self.notify(f"Debug overlay {status}", severity="info")

    def _handle_dump_debug_snapshot_intent(self) -> None:
        """Dump debug information to file."""
        if self._debug_overlay is None:
            if DebugOverlay is not None:
                from mikomn.tui_base.widgets.debug_overlay import DebugOverlay as TempOverlay

                overlay = TempOverlay()
                path = self.dump_debug_snapshot(overlay=overlay)
            else:
                self.notify("Debug overlay not available", severity="warning")
                return
        else:
            path = self.dump_debug_snapshot(overlay=self._debug_overlay)

        if path:
            self.notify(f"Debug dump saved to {path}", severity="info")
            run_panel = self._get_run_panel()
            if run_panel:
                run_panel.write(f"Debug dump: {path}")
        else:
            self.notify("Failed to save debug dump", severity="error")

    def _handle_cycle_theme_intent(self) -> None:
        """Cycle to the next theme."""
        self._theme_name = cycle_theme()
        self.call_after_refresh(self._set_theme)
        self.notify(f"Theme: {self._theme_name}")
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.write(f"Theme changed to {self._theme_name}")

    def _handle_set_theme_intent(self, intent: SetTheme) -> None:
        """Activate a theme by name."""
        name = (intent.theme_name or "").strip()
        if not name:
            return

        if not set_theme(name):
            self.notify(f"Unknown theme: {name}", severity="warning")
            return

        self._theme_name = name
        self.call_after_refresh(self._set_theme)
        self.notify(f"Theme: {self._theme_name}")

    def build_command_palette_commands(self) -> list[Command]:
        """Build the command list for the command palette."""
        commands: list[Command] = []
        for spec in self._pages.specs():
            if getattr(spec, "disabled", False):
                continue
            key = (spec.key or "").strip()
            keywords = [spec.route, spec.label]
            if key:
                keywords.append(key)
            commands.append(
                Command(
                    id=f"nav:{spec.route}",
                    title=f"Go to {spec.label}",
                    keywords=keywords,
                    group="Navigation",
                    meta={"route": spec.route},
                )
            )

        commands.extend(
            [
                Command(
                    id="app:cycle-theme",
                    title="Cycle theme",
                    keywords=["theme", "palette"],
                    group="App",
                ),
                Command(
                    id="app:reload-css",
                    title="Reload CSS",
                    keywords=["css", "tcss", "style"],
                    group="App",
                ),
                Command(
                    id="app:toggle-debug",
                    title="Toggle debug overlay",
                    keywords=["debug", "overlay"],
                    group="App",
                ),
                Command(
                    id="app:jump-last-run",
                    title="Jump to last run",
                    keywords=["logs", "run", "last"],
                    group="App",
                ),
                Command(
                    id="app:dump-debug",
                    title="Dump debug snapshot",
                    keywords=["debug", "dump", "snapshot"],
                    group="App",
                ),
                Command(
                    id="app:clear-run-log",
                    title="Clear run log",
                    keywords=["log", "clear"],
                    group="App",
                ),
                Command(
                    id="app:notifications",
                    title="Open notification center",
                    keywords=["notifications", "history"],
                    group="App",
                ),
                Command(
                    id="app:toggle-toasts",
                    title="Toggle toasts",
                    keywords=["toast", "notifications"],
                    group="App",
                ),
            ]
        )

        for theme_name in get_theme_names():
            commands.append(
                Command(
                    id=f"theme:set:{theme_name}",
                    title=f"Set theme: {theme_name}",
                    keywords=["theme", theme_name],
                    group="Theme",
                    meta={"theme_name": theme_name},
                )
            )

        try:
            focused = getattr(self, "focused", None)
            get_cmds = getattr(focused, "bkx_commands", None)
            if callable(get_cmds):
                for cmd in list(get_cmds() or []):
                    if isinstance(cmd, Command):
                        commands.append(cmd)
        except Exception:
            pass

        return commands

    def open_log_filter_sheet(self, pane: object) -> None:
        """Open filter sheet for the run log pane."""
        current = getattr(pane, "_filter_level", None)

        options = [
            FilterOption(value="*", label="All", selected=current is None),
            FilterOption(value="info", label="info", selected=current == "info"),
            FilterOption(value="success", label="success", selected=current == "success"),
            FilterOption(value="warning", label="warning", selected=current == "warning"),
            FilterOption(value="error", label="error", selected=current == "error"),
            FilterOption(value="debug", label="debug", selected=current == "debug"),
        ]
        group = FilterGroup(field="level", label="Level", options=options, multi_select=False)

        def _apply(selected: dict[str, list[str]]) -> None:
            picked = (selected.get("level") or ["*"])[0]
            try:
                pane.set_filter_level(None if picked == "*" else picked)  # type: ignore[attr-defined]
            except Exception:
                pass

        self._push_modal(
            BkxFilterSheet(filter_groups=[group], on_apply=_apply, title="Log Filters")
        )

    def open_archive_filter_sheet(self, pane: object) -> None:
        """Open filter sheet for the archive list pane (extension group)."""
        build = getattr(pane, "build_extension_filter_group", None)
        if not callable(build):
            return
        group = build()
        if group is None:
            return

        def _apply(selected: dict[str, list[str]]) -> None:
            picked = (selected.get("extension") or ["*"])[0]
            try:
                pane.set_extension_filter(None if picked == "*" else picked)  # type: ignore[attr-defined]
            except Exception:
                pass

        self._push_modal(
            BkxFilterSheet(filter_groups=[group], on_apply=_apply, title="Archive Filters")
        )

    def action_command_palette(self) -> None:
        """Request the command palette overlay."""
        self.post_message(ShowCommandPalette())

    def on_command_selected(self, event: CommandSelected) -> None:
        """Handle command selections from the palette (intent-driven)."""
        cmd_id = getattr(event.command, "id", None) or event.command_id
        meta = getattr(event.command, "meta", None) or {}

        if cmd_id.startswith("nav:"):
            route = (
                str(meta.get("route") if isinstance(meta, dict) else "") or cmd_id.split(":", 1)[1]
            )
            self.post_message(NavigateToScreen(screen_name=route))
            return

        if cmd_id.startswith("theme:set:"):
            theme_name = (
                str(meta.get("theme_name") if isinstance(meta, dict) else "")
                or cmd_id.split(":", 2)[2]
            )
            self.post_message(SetTheme(theme_name=theme_name))
            return

        if cmd_id == "app:cycle-theme":
            self.post_message(CycleTheme())
            return

        if cmd_id == "app:reload-css":
            self.post_message(ReloadCss())
            return

        if cmd_id == "app:toggle-debug":
            self.post_message(ToggleDebugOverlay())
            return

        if cmd_id == "app:jump-last-run":
            self.action_jump_last_run()
            return

        if cmd_id == "app:dump-debug":
            self.post_message(DumpDebugSnapshot())
            return

        if cmd_id == "app:clear-run-log":
            self.post_message(ClearRunLog())
            return

        if cmd_id == "app:notifications":
            self.post_message(OpenNotificationCenter())
            return

        if cmd_id == "app:toggle-toasts":
            self.post_message(ToggleToasts())
            return

    def action_notification_center(self) -> None:
        """Request the notification center overlay."""
        self.post_message(OpenNotificationCenter())

    def action_jump_last_run(self) -> None:
        """Jump to the last run output (deep-link if available)."""
        link = self._last_run_link or {"route": "logs", "focus": "#bkx-logs-log"}
        route = str(link.get("route", "") or "").strip()
        focus = str(link.get("focus", "") or "").strip()

        try:
            self.query_one(BkxShell).goto_page(route or "logs")
        except Exception:
            return

        if focus:
            self.call_after_refresh(lambda: self.query_one(focus).focus())

    def on_notification_selected(self, message: Any) -> None:
        """Handle selections from the notification center (deep-link)."""
        event_id = str(getattr(message, "event_id", "") or "")
        if not event_id:
            return
        link = self._notification_links.get(event_id)
        if not link:
            return
        route = str(link.get("route", "") or "").strip()
        focus = str(link.get("focus", "") or "").strip()
        if route:
            try:
                self.query_one(BkxShell).goto_page(route)
            except Exception:
                pass
        if focus:
            self.call_after_refresh(lambda: self.query_one(focus).focus())

    def action_help(self) -> None:
        """Show the help overlay."""
        self._push_modal(BkxHelpOverlay())

    # =========================================================================
    # Message Handlers - Intent Dispatching
    # =========================================================================

    def on_intent(self, intent: Intent) -> None:
        """Handle all intents from widgets.

        Args:
            intent: Intent message from widget
        """
        if isinstance(intent, BackupIntent):
            self._handle_backup_intent(intent)
        elif isinstance(intent, ArchiveIntent):
            self._handle_archive_intent(intent)
        elif isinstance(intent, RestoreIntent):
            self._handle_restore_intent(intent)
        elif isinstance(intent, TransferIntent):
            self._handle_transfer_intent(intent)
        elif isinstance(intent, NavigateToScreen):
            self._handle_navigate(intent)
        elif isinstance(intent, ShowCommandPalette):
            self._handle_show_command_palette_intent()
        elif isinstance(intent, OpenNotificationCenter):
            self._handle_open_notification_center_intent()
        elif isinstance(intent, CycleTheme):
            self._handle_cycle_theme_intent()
        elif isinstance(intent, SetTheme):
            self._handle_set_theme_intent(intent)
        elif isinstance(intent, ReloadCss):
            self._handle_reload_css_intent()
        elif isinstance(intent, ClearRunLog):
            self._handle_clear_run_log_intent()
        elif isinstance(intent, ToggleToasts):
            self._handle_toggle_toasts_intent()
        elif isinstance(intent, ToggleDebugOverlay):
            self._handle_toggle_debug_overlay_intent()
        elif isinstance(intent, DumpDebugSnapshot):
            self._handle_dump_debug_snapshot_intent()

        elif isinstance(intent, SetBusy):
            self._handle_set_busy(intent)
        elif isinstance(intent, ClearBusy):
            self._handle_clear_busy()
        elif isinstance(intent, UpdateLog):
            self._handle_update_log(intent)
        elif isinstance(intent, ShowNotification):
            self._handle_show_notification(intent)
        elif isinstance(intent, ShowToast):
            self._handle_show_toast(intent)
        elif isinstance(intent, ProgressStarted):
            self._handle_progress_started(intent)
        elif isinstance(intent, ProgressUpdate):
            self._handle_progress_update(intent)
        elif isinstance(intent, ProgressCompleted):
            self._handle_progress_completed(intent)
        elif isinstance(intent, FilterChanged):
            self._handle_filter_changed(intent)
        elif isinstance(intent, ItemsSelected):
            self._handle_items_selected(intent)
        elif isinstance(intent, RequestEmptyState):
            self._handle_request_empty_state(intent)
        elif isinstance(intent, ClearEmptyState):
            self._handle_clear_empty_state(intent)

    def _handle_backup_intent(self, intent: BackupIntent) -> None:
        """Handle backup intent - dispatch to service."""
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.set_status("Backing up", level="info")
            run_panel.write(f"Starting backup: {intent.sources}")

        # Post progress started
        self.post_message(ProgressStarted(operation="backup", message="Starting backup"))

        try:
            spec = build_backup_spec(**intent.to_spec_params())
            service = get_backup_service()
            result = service.create(spec)

            if run_panel:
                run_panel.set_status("Backup complete", level="success")
                run_panel.write(f"Backup complete: {result.archive_path}")
                run_panel.write(f"  Files: {result.files_count}, Size: {result.bytes_total}")

            # Show toast notification
            self.post_message(
                ShowToast(
                    message=f"Backup complete: {result.files_count} files",
                    severity="success",
                    duration=4.0,
                )
            )

            # Post progress completed
            self.post_message(ProgressCompleted(operation="backup", message="Backup complete"))

        except BkxError as e:
            if run_panel:
                run_panel.set_status(f"Backup failed: {e}", level="error")
                run_panel.write(f"Backup failed: {e}")

            # Show error toast
            self.post_message(
                ShowToast(
                    message=f"Backup failed: {e}",
                    severity="error",
                    duration=5.0,
                )
            )

            # Post progress completed with error
            self.post_message(ProgressCompleted(operation="backup", message=f"Error: {e}"))

    def _handle_archive_intent(self, intent: ArchiveIntent) -> None:
        """Handle archive intent - dispatch to service."""
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.set_status("Creating archive", level="info")
            run_panel.write(f"Creating archive: {intent.sources}")

        # Post progress started
        self.post_message(ProgressStarted(operation="archive", message="Creating archive"))

        try:
            spec = build_archive_spec(**intent.to_spec_params())
            service = get_archive_service()
            result = service.create(spec)

            if run_panel:
                run_panel.set_status("Archive created", level="success")
                run_panel.write(f"Archive created: {result.archive_path}")
                run_panel.write(f"  Files: {result.files_count}")

            # Show toast notification
            self.post_message(
                ShowToast(
                    message=f"Archive created: {result.files_count} files",
                    severity="success",
                    duration=4.0,
                )
            )

            # Post progress completed
            self.post_message(ProgressCompleted(operation="archive", message="Archive created"))

        except BkxError as e:
            if run_panel:
                run_panel.set_status(f"Archive failed: {e}", level="error")
                run_panel.write(f"Archive failed: {e}")

            # Show error toast
            self.post_message(
                ShowToast(
                    message=f"Archive failed: {e}",
                    severity="error",
                    duration=5.0,
                )
            )

            # Post progress completed with error
            self.post_message(ProgressCompleted(operation="archive", message=f"Error: {e}"))

    def _handle_restore_intent(self, intent: RestoreIntent) -> None:
        """Handle restore intent - dispatch to service."""
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.set_status("Restoring", level="info")
            run_panel.write(f"Restoring: {intent.archive}")

        # Post progress started
        self.post_message(ProgressStarted(operation="restore", message="Restoring archive"))

        try:
            spec = build_restore_spec(**intent.to_spec_params())
            service = get_restore_service()
            result = service.extract(spec)

            if run_panel:
                run_panel.set_status("Restore complete", level="success")
                run_panel.write(f"Restore complete: {result.destination_path}")
                run_panel.write(f"  Files: {result.files_count}")

            # Show toast notification
            self.post_message(
                ShowToast(
                    message=f"Restore complete: {result.files_count} files",
                    severity="success",
                    duration=4.0,
                )
            )

            # Post progress completed
            self.post_message(ProgressCompleted(operation="restore", message="Restore complete"))

        except BkxError as e:
            if run_panel:
                run_panel.set_status(f"Restore failed: {e}", level="error")
                run_panel.write(f"Restore failed: {e}")

            # Show error toast
            self.post_message(
                ShowToast(
                    message=f"Restore failed: {e}",
                    severity="error",
                    duration=5.0,
                )
            )

            # Post progress completed with error
            self.post_message(ProgressCompleted(operation="restore", message=f"Error: {e}"))

    def _handle_transfer_intent(self, intent: TransferIntent) -> None:
        """Handle transfer intent - dispatch to service."""
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.set_status("Transferring", level="info")
            run_panel.write(f"Transferring: {intent.source} → {intent.destination}")

        # Post progress started
        self.post_message(ProgressStarted(operation="transfer", message="Transferring files"))

        try:
            spec = build_transfer_spec(**intent.to_spec_params())
            service = get_transfer_service()
            service.transfer(spec)

            if run_panel:
                run_panel.set_status("Transfer complete", level="success")
                run_panel.write("Transfer complete")

            # Show toast notification
            self.post_message(
                ShowToast(
                    message="Transfer complete",
                    severity="success",
                    duration=4.0,
                )
            )

            # Post progress completed
            self.post_message(ProgressCompleted(operation="transfer", message="Transfer complete"))

        except BkxError as e:
            if run_panel:
                run_panel.set_status(f"Transfer failed: {e}", level="error")
                run_panel.write(f"Transfer failed: {e}")

            # Show error toast
            self.post_message(
                ShowToast(
                    message=f"Transfer failed: {e}",
                    severity="error",
                    duration=5.0,
                )
            )

            # Post progress completed with error
            self.post_message(ProgressCompleted(operation="transfer", message=f"Error: {e}"))

    def _handle_navigate(self, intent: NavigateToScreen) -> None:
        """Handle navigation intent."""
        run_panel = self._get_run_panel()
        screen_name = intent.screen_name
        try:
            shell = self.query_one(BkxShell)
            shell.goto_page(screen_name)
        except Exception:
            if run_panel:
                run_panel.write(f"Unknown screen: {screen_name}")

    def _handle_set_busy(self, intent: SetBusy) -> None:
        """Handle set busy state."""
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.set_status(intent.message, level="info")

    def _handle_clear_busy(self) -> None:
        """Clear busy state."""
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.set_status("Ready", level="info")

    def _handle_update_log(self, intent: UpdateLog) -> None:
        """Handle update log message."""
        self._write_to_logs(intent.message, intent.level)

    def _handle_show_notification(self, intent: ShowNotification) -> None:
        """Handle show notification message."""
        event_id = ""
        try:
            event = NotificationHistory.record_status(
                intent.message, level=str(intent.level), source="bkx"
            )
            event_id = str(getattr(event, "id", "") or "")
        except Exception:
            event_id = ""

        link = getattr(intent, "link", None)
        if link and event_id:
            try:
                self._notification_links[event_id] = {str(k): str(v) for k, v in dict(link).items()}
            except Exception:
                pass
        self._write_to_logs(intent.message, str(intent.level))
        self.notify(intent.message, severity=intent.level)

    def _handle_show_toast(self, intent: ShowToast) -> None:
        """Handle show toast message."""
        toast_id = str(uuid.uuid4())[:8]
        link = getattr(intent, "link", None)
        if link:
            try:
                self._notification_links[str(toast_id)] = {
                    str(k): str(v) for k, v in dict(link).items()
                }
            except Exception:
                pass
        try:
            NotificationHistory.record_toast(
                ToastData(
                    id=toast_id,
                    message=intent.message,
                    level=str(intent.severity),
                    duration=float(intent.duration) if intent.duration is not None else None,
                ),
                source="bkx",
            )
        except Exception:
            pass

        if not self._show_toasts:
            return

        shown = ""
        try:
            shown = ToastManager.add_toast(
                intent.message,
                level=str(intent.severity),
                duration=float(intent.duration) if intent.duration is not None else 3.0,
                toast_id=toast_id,
            )
        except Exception:
            shown = ""

        if not shown:
            self.notify(intent.message, severity=intent.severity, duration=intent.duration)

    def _handle_progress_started(self, intent: ProgressStarted) -> None:
        """Handle progress started message."""
        try:
            self._progress[str(intent.operation)] = (
                0,
                int(intent.total),
                str(intent.message or ""),
            )
        except Exception:
            pass
        try:
            self.query_one(BkxShell).set_footer_message(
                f"{intent.operation}: 0% • {intent.message}"
            )
        except Exception:
            pass
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.write(f"[{intent.operation}] {intent.message}")
            run_panel.start_progress(total=intent.total, message=intent.message)

    def _handle_progress_update(self, intent: ProgressUpdate) -> None:
        """Handle progress update message."""
        try:
            self._progress[str(intent.operation)] = (
                int(intent.current),
                int(intent.total),
                str(intent.message or ""),
            )
        except Exception:
            pass
        try:
            pct = int(intent.percent) if intent.percent is not None else 0
            self.query_one(BkxShell).set_footer_message(
                f"{intent.operation}: {pct}% • {intent.message}"
            )
        except Exception:
            pass
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.update_progress(
                current=intent.current,
                total=intent.total,
                percent=intent.percent,
                message=intent.message,
            )

    def _handle_progress_completed(self, intent: ProgressCompleted) -> None:
        """Handle progress completed message."""
        self._last_run_label = str(intent.message or intent.operation)
        self._last_run_link = {"route": "logs", "focus": "#bkx-logs-log"}
        try:
            self._progress.pop(str(intent.operation), None)
        except Exception:
            pass
        try:
            shell = self.query_one(BkxShell)
            if not self._progress:
                shell.set_footer_message(None)
            else:
                op, (cur, tot, msg) = next(iter(self._progress.items()))
                pct = int((cur / tot) * 100) if tot else 0
                shell.set_footer_message(f"{op}: {pct}% • {msg}")
        except Exception:
            pass
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.write(f"[{intent.operation}] {intent.message}")
            run_panel.complete_progress(message=intent.message, success=True)

    def _handle_filter_changed(self, intent: FilterChanged) -> None:
        """Handle filter changed message."""
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.write(
                f"Filter: '{intent.filter_text}' ({intent.filtered_count}/{intent.total_count})"
            )

    def _handle_items_selected(self, intent: ItemsSelected) -> None:
        """Handle items selected message."""
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.write(f"Selected {len(intent.item_ids)} items for {intent.picker_for}")

    def _handle_request_empty_state(self, intent: RequestEmptyState) -> None:
        """Handle request empty state message."""
        # This would be handled by a container that knows how to show empty states
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.write(f"Empty state requested for {intent.container_id}: {intent.title}")

    def _handle_clear_empty_state(self, intent: ClearEmptyState) -> None:
        """Handle clear empty state message."""
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.write(f"Empty state cleared for {intent.container_id}")


def run_tui() -> int:
    """Run the BKX TUI.

    Returns:
        Exit code
    """
    app = BkxTuiApp()
    return app.run()


if __name__ == "__main__":
    run_tui()
