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
import traceback
import uuid
from typing import Literal

from mikomn.tui_base import BaseTuiApp
from mikomn.tui_base.css_registry import reload_css
from mikomn.tui_base.models import Command, FilterGroup, FilterOption, ToastData
from mikomn.tui_base.notifications import NotificationHistory
from mikomn.tui_base.overlays import CommandSelected
from mikomn.tui_base.platform import LayoutMode
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

# Step 9: Intent history tracking
from bkx.tui.intent_history import record_intent
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
from bkx.tui.panes.archive_list import ArchiveListPane
from bkx.tui.screens.crash import CrashScreen
from bkx.tui.state import TuiState, load_state, save_state

# Step 9A: Support bundle generation
from bkx.tui.support_bundle import export_support_bundle

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
        ("7", "navigate_run_history", "Run History"),
        ("ctrl+p", "command_palette", "Commands"),
        ("ctrl+n", "notification_center", "Notifications"),
        ("N", "toggle_toasts", "Toasts"),
        # Step 7A: Keyboard navigation bindings
        ("j", "move_selection_down", "Down"),
        ("k", "move_selection_up", "Up"),
        ("enter", "activate_selection", "Select"),
        ("escape", "clear_filter", "Clear Filter"),
        # Step 10A: Global search binding
        ("/", "global_search", "Search"),
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
        self._tui_state: TuiState = load_state()
        self._show_toasts = bool(self._tui_state.show_toasts)

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
        # Step 6: restore persisted UI state (best-effort)
        try:
            if self._tui_state.layout_mode:
                try:
                    self._layout_mode = LayoutMode(self._tui_state.layout_mode)
                except Exception:
                    pass
        except Exception:
            pass
        self.call_after_refresh(lambda: self._goto_page(self._tui_state.last_route))

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
                # RunPanel.write() only accepts text, no level parameter
                run_panel.write(message)
            except Exception:
                pass

        logs_pane = self._get_logs_pane()
        if logs_pane:
            try:
                # RunLogPane.write() supports level parameter
                logs_pane.write(message, level=level)
            except TypeError:
                # Fall back if level parameter is not supported
                logs_pane.write(message)
            except Exception:
                logs_pane.write(message)

    def on_unmount(self) -> None:
        """Called when app is unmounted."""
        if self._theme_callback_id:
            bkx_off_theme_change(self._theme_callback_id)
        # Call parent's on_unmount if it exists
        parent_on_unmount = getattr(super(), "on_unmount", None)
        if callable(parent_on_unmount):
            try:
                parent_on_unmount()
            except Exception:
                pass

    def on_exception(self, exception: Exception) -> None:
        """Show a crash screen for unhandled exceptions.

        Step 8A: Persist last route before showing crash screen.
        """
        # Step 8A: Persist state before showing crash
        try:
            self._persist_tui_state()
            # Also save last_route specifically for crash recovery
            if hasattr(self, "_last_route"):
                self._tui_state.last_route = self._last_route
                save_state(self._tui_state)
        except Exception:
            pass

        try:
            tb = "".join(traceback.format_exception(type(exception), exception, exception.__traceback__))
            # Step 8A: Pass last_route to crash screen
            last_route = getattr(self, "_last_route", self._tui_state.last_route)
            self.push_screen(CrashScreen(title="BKX crashed", traceback_text=tb, last_route=last_route))
        except Exception:
            raise

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
                # Check if prev_focus has focus method before calling
                focus_method = getattr(prev_focus, "focus", None)
                if callable(focus_method):
                    self.call_after_refresh(lambda: focus_method())
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

    def _goto_page(self, route: str) -> None:
        """Navigate to a shell page route."""
        self._last_route = route
        self._persist_tui_state()
        try:
            self.query_one(BkxShell).goto_page(route)
        except Exception:
            run_panel = self._get_run_panel()
            if run_panel:
                run_panel.write(f"Unknown page: {route}")
            self.notify(f"Unknown page: {route}", severity="warning")

    # =========================================================================

    def _persist_tui_state(self) -> None:
        """Persist selected UI state (best-effort)."""
        try:
            self._tui_state.last_route = getattr(self, "_last_route", self._tui_state.last_route)
            if hasattr(self, "_layout_mode"):
                if isinstance(self._layout_mode, LayoutMode):
                    self._tui_state.layout_mode = self._layout_mode.value
                else:
                    self._tui_state.layout_mode = str(self._layout_mode)
            self._tui_state.show_toasts = bool(self._show_toasts)
            save_state(self._tui_state)
        except Exception:
            return

    def on_archive_list_pane_archive_selected(self, message: ArchiveListPane.ArchiveSelected) -> None:
        """Persist last selected archive id (Step 7A)."""
        try:
            self._tui_state.archive_selected_id = str(getattr(message, "archive_id", "") or "")
            self._persist_tui_state()
        except Exception:
            return

    def _restore_archive_ui_state(self) -> None:
        """Step 7A: Restore archive list UI state (filter + selection + scroll)."""
        try:
            pane = self.query_one(ArchiveListPane)
        except Exception:
            return

        # Restore filter text
        try:
            ft = str(self._tui_state.archive_filter_text or "")
            if ft and hasattr(pane, "set_filter_text"):
                pane.set_filter_text(ft)
        except Exception:
            pass

        # Restore selection
        try:
            sid = str(self._tui_state.archive_selected_id or "")
            if sid and hasattr(pane, "select_archive_id"):
                pane.select_archive_id(sid)
        except Exception:
            pass

        # Restore scroll position
        try:
            sp = int(self._tui_state.archive_scroll_position or 0)
            if sp > 0 and hasattr(pane, "set_scroll_position"):
                pane.set_scroll_position(sp)
        except Exception:
            pass

    def on_archive_list_pane_filter_changed(self, message: ArchiveListPane.FilterChanged) -> None:
        """Persist archive filter text and scroll position (Step 7A)."""
        try:
            self._tui_state.archive_filter_text = str(getattr(message, "filter_text", "") or "")
            self._persist_tui_state()
        except Exception:
            return

        # Also save scroll position when navigating away
        try:
            pane = self.query_one(ArchiveListPane)
            if hasattr(pane, "get_scroll_position"):
                scroll_pos = pane.get_scroll_position()
                if scroll_pos > 0:
                    self._tui_state.archive_scroll_position = scroll_pos
                    self._persist_tui_state()
        except Exception:
            pass

    def _save_focused_widget(self) -> None:
        """Save currently focused widget ID (Step 7A)."""
        try:
            focused = self.focused
            if focused is not None:
                widget_id = getattr(focused, "id", None) or getattr(focused, "_id", "")
                pane_id = ""
                # Try to get parent pane ID
                parent = getattr(focused, "parent", None)
                if parent is not None:
                    pane_id = getattr(parent, "id", "") or ""
                self._tui_state.last_focused_widget = str(widget_id)
                self._tui_state.last_focused_pane = str(pane_id)
                self._persist_tui_state()
        except Exception:
            pass

    def _restore_focused_widget(self) -> None:
        """Restore previously focused widget (Step 7A)."""
        try:
            widget_id = self._tui_state.last_focused_widget
            pane_id = self._tui_state.last_focused_pane

            if pane_id and widget_id:
                # Try to find and focus the widget
                for selector in [f"#{widget_id}", f"#{pane_id} #{widget_id}"]:
                    try:
                        widget = self.query_one(selector)
                        # Check if widget has focus method before calling
                        focus_method = getattr(widget, "focus", None)
                        if callable(focus_method):
                            focus_method()
                        return
                    except Exception:
                        continue
        except Exception:
            pass

    def action_jump_to_last_run(self) -> None:
        """Jump focus to the run panel (persistent bottom panel)."""
        shell = self._shell()
        if not shell:
            return
        run_panel = getattr(shell, "run_panel", None)
        if not run_panel:
            return
        try:
            run_panel.focus()
            run_panel.scroll_end(animate=False)
        except Exception:
            try:
                run_panel.focus()
            except Exception:
                return

    # Step 7A: Keyboard navigation actions
    def action_move_selection_down(self) -> None:
        """Move selection down in focused list pane."""
        try:
            pane = self.focused
            if pane is not None:
                # Check if pane has the action method using getattr for type safety
                action_down = getattr(pane, "action_move_selection_down", None)
                if callable(action_down):
                    action_down()
                    return
            # Try archive list pane as fallback
            archive_pane = self.query_one(ArchiveListPane)
            action_down = getattr(archive_pane, "action_move_selection_down", None)
            if callable(action_down):
                action_down()
        except Exception:
            pass

    def action_move_selection_up(self) -> None:
        """Move selection up in focused list pane."""
        try:
            pane = self.focused
            if pane is not None:
                action_up = getattr(pane, "action_move_selection_up", None)
                if callable(action_up):
                    action_up()
                    return
            # Try archive list pane as fallback
            archive_pane = self.query_one(ArchiveListPane)
            action_up = getattr(archive_pane, "action_move_selection_up", None)
            if callable(action_up):
                action_up()
        except Exception:
            pass

    def action_activate_selection(self) -> None:
        """Activate/select current item."""
        try:
            pane = self.focused
            if pane is not None:
                activate = getattr(pane, "action_activate_selection", None)
                if callable(activate):
                    activate()
                    return
            # Try archive list pane as fallback
            archive_pane = self.query_one(ArchiveListPane)
            activate = getattr(archive_pane, "action_activate_selection", None)
            if callable(activate):
                activate()
        except Exception:
            pass

    def action_clear_filter(self) -> None:
        """Clear filter and reset selection."""
        try:
            pane = self.focused
            if pane is not None:
                clear_filter = getattr(pane, "action_clear_filter", None)
                if callable(clear_filter):
                    clear_filter()
                    return
            # Try archive list pane as fallback
            archive_pane = self.query_one(ArchiveListPane)
            clear_filter = getattr(archive_pane, "action_clear_filter", None)
            if callable(clear_filter):
                clear_filter()
        except Exception:
            pass

    def action_focus_filter(self) -> None:
        """Focus the most relevant filter input on the current screen (best-effort)."""
        try:
            # Prefer an explicit ArchiveListPane filter.
            pane = self.query_one(ArchiveListPane)
            if hasattr(pane, "focus_filter"):
                pane.focus_filter()
                return
        except Exception:
            pass
        # Fallback: focus first Input with class 'filter-input' if present.
        try:
            from textual.widgets import Input

            inp = self.query_one(".filter-input", Input)
            inp.focus()
        except Exception:
            return

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

    # Step 10B: Run History navigation
    def action_navigate_run_history(self) -> None:
        """Navigate to run history page."""
        self._goto_page("run_history")

    # Step 10A: Global search
    def action_global_search(self) -> None:
        """Open global search overlay."""
        # Import here to avoid circular imports
        try:
            from bkx.tui.overlays.global_search import show_global_search

            show_global_search(self)
        except ImportError:
            # Fallback: show command palette with search hint
            self.notify("Global search not available", severity="warning")
            self.post_message(ShowCommandPalette())

    def action_toggle_toasts(self) -> None:
        """Toggle toast rendering (intent-driven)."""
        self.post_message(ToggleToasts())

    def action_toggle_debug(self) -> None:
        """Toggle debug overlay visibility (intent-driven)."""
        self.post_message(ToggleDebugOverlay())

    def action_dump_debug(self) -> None:
        """Dump debug information to file (intent-driven)."""
        # Try to use the debug overlay if available
        if self._debug_overlay is None:
            try:
                from mikomn.tui_base.widgets.debug_overlay import DebugOverlay as DO

                overlay = DO(target=self.screen)
                path = self.dump_debug_snapshot(overlay=overlay)
            except Exception:
                path = None
        else:
            path = self.dump_debug_snapshot(overlay=self._debug_overlay)

        if path:
            self.notify(f"Debug dump saved: {path}", severity="information")
            run_panel = self._get_run_panel()
            if run_panel:
                run_panel.write(f"Debug dump: {path}")
        else:
            self.notify("Failed to save debug dump", severity="error")

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

        self.notify("CSS reloaded", severity="information")

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

        self.notify("Run log cleared", severity="information")

    def _handle_toggle_toasts_intent(self) -> None:
        """Enable/disable toast rendering."""
        self._show_toasts = not self._show_toasts
        self._persist_tui_state()
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
        self.notify(f"Toasts {status}", severity="information")

    def _handle_toggle_debug_overlay_intent(self) -> None:
        """Toggle debug overlay visibility."""
        if self._debug_overlay is None:
            if DebugOverlay is not None:
                self._debug_overlay = DebugOverlay(id="debug-overlay", display=True)
                self.mount(self._debug_overlay)
                self.notify("Debug overlay enabled", severity="information")
            else:
                self.notify("Debug overlay not available", severity="warning")
            return

        current_display = self._debug_overlay.display
        self._debug_overlay.display = not current_display
        status = "shown" if not current_display else "hidden"
        self.notify(f"Debug overlay {status}", severity="information")

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
            self.notify(f"Debug dump saved to {path}", severity="information")
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
                    id="app:dump-debug",
                    title="Dump debug snapshot",
                    keywords=["debug", "dump", "snapshot"],
                    group="App",
                ),
                Command(
                    id="app:export-support-bundle",
                    title="Export support bundle",
                    keywords=["support", "bundle", "diagnostics", "zip"],
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
                Command(
                    id="app:jump-to-last-run",
                    title="Jump to last run",
                    keywords=["run", "logs", "panel"],
                    group="App",
                ),
                Command(
                    id="app:focus-filter",
                    title="Focus filter",
                    keywords=["filter", "search"],
                    group="App",
                ),
                # Step 7C: New commands
                Command(
                    id="archive:copy-id",
                    title="Copy archive ID",
                    keywords=["archive", "copy", "id", "clipboard"],
                    group="Archive",
                ),
                Command(
                    id="archive:copy-path",
                    title="Copy archive path",
                    keywords=["archive", "copy", "path", "clipboard"],
                    group="Archive",
                ),
                Command(
                    id="app:export-logs",
                    title="Export logs",
                    keywords=["export", "logs", "save", "file"],
                    group="App",
                ),
                Command(
                    id="app:toggle-compact",
                    title="Toggle compact mode",
                    keywords=["compact", "layout", "mode", "toggle"],
                    group="App",
                ),
                # Step 10A: Global search command
                Command(
                    id="app:global-search",
                    title="Global search",
                    keywords=["search", "global", "find", "/", "archive"],
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

        # Ensure group is a FilterGroup for type checking
        if isinstance(group, FilterGroup):
            filter_groups: list[FilterGroup] = [group]
            self._push_modal(BkxFilterSheet(filter_groups=filter_groups, on_apply=_apply, title="Log Filters"))

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

        # Ensure group is a FilterGroup for type checking
        if isinstance(group, FilterGroup):
            filter_groups: list[FilterGroup] = [group]
            self._push_modal(BkxFilterSheet(filter_groups=filter_groups, on_apply=_apply, title="Archive Filters"))

    def action_command_palette(self) -> None:
        """Request the command palette overlay."""
        self.post_message(ShowCommandPalette())

    def on_command_selected(self, event: CommandSelected) -> None:
        """Handle command selections from the palette (intent-driven)."""
        cmd_id = getattr(event.command, "id", None) or event.command_id
        meta = getattr(event.command, "meta", None) or {}

        if cmd_id.startswith("nav:"):
            route = str(meta.get("route") if isinstance(meta, dict) else "") or cmd_id.split(":", 1)[1]
            self.post_message(NavigateToScreen(screen_name=route))
            return

        if cmd_id.startswith("theme:set:"):
            theme_name = str(meta.get("theme_name") if isinstance(meta, dict) else "") or cmd_id.split(":", 2)[2]
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

        # Step 7C: New command handlers
        if cmd_id == "archive:copy-id":
            self._handle_copy_archive_id()
            return

        if cmd_id == "archive:copy-path":
            self._handle_copy_archive_path()
            return

        if cmd_id == "app:export-logs":
            self._handle_export_logs()
            return

        if cmd_id == "app:toggle-compact":
            self._handle_toggle_compact_mode()
            return

        # Step 10A: Global search command handler
        if cmd_id == "app:global-search":
            self.action_global_search()
            return

    def action_notification_center(self) -> None:
        """Request the notification center overlay."""
        self.post_message(OpenNotificationCenter())

    # Step 7C: Command handlers
    def _handle_copy_archive_id(self) -> None:
        """Copy selected archive ID to clipboard."""
        archive_id = self._tui_state.archive_selected_id
        if archive_id:
            try:
                self.copy_to_clipboard(archive_id)
                self.notify("Archive ID copied to clipboard", severity="information")
            except Exception:
                self.notify("Failed to copy archive ID", severity="error")
        else:
            self.notify("No archive selected", severity="warning")

    def _handle_copy_archive_path(self) -> None:
        """Copy selected archive path to clipboard."""
        # Try to get path from selected archive
        try:
            pane = self.query_one(ArchiveListPane)
            if hasattr(pane, "_current_archive") and pane._current_archive:
                path = str(pane._current_archive.path)
                try:
                    self.copy_to_clipboard(path)
                    self.notify("Archive path copied to clipboard", severity="information")
                except Exception:
                    self.notify("Failed to copy archive path", severity="error")
                return
        except Exception:
            pass

        # Fallback: no archive selected
        self.notify("No archive selected", severity="warning")

    def _handle_export_logs(self) -> None:
        """Export logs to file."""
        try:
            run_panel = self._get_run_panel()
            if run_panel and hasattr(run_panel, "get_log_text"):
                log_text = run_panel.get_log_text()
                import time

                ts = time.strftime("%Y%m%d_%H%M%S")
                from pathlib import Path

                out_path = Path(f"/tmp/bkx_logs_{ts}.txt")
                out_path.write_text(log_text, encoding="utf-8")
                self.notify(f"Logs exported to {out_path}", severity="information")
            else:
                self.notify("No logs to export", severity="warning")
        except Exception as e:
            self.notify(f"Failed to export logs: {e}", severity="error")

    def _handle_toggle_compact_mode(self) -> None:
        """Toggle compact layout mode."""
        current_mode = getattr(self, "layout_mode", "normal")
        new_mode = "compact" if current_mode == "normal" else "normal"
        try:
            # Use _layout_mode to set the property value
            self._layout_mode = LayoutMode(new_mode)
            self._tui_state.layout_mode = new_mode
            self._persist_tui_state()
            status = "enabled" if new_mode == "compact" else "disabled"
            self.notify(f"Compact mode: {status}", severity="information")
        except Exception as e:
            self.notify(f"Failed to toggle compact mode: {e}", severity="error")

    def action_export_support_bundle(self) -> None:
        """Export a support bundle zip to /tmp and notify the path.

        Step 9A: Uses the dedicated support_bundle module for comprehensive bundle creation.
        """
        try:
            bundle_path, message = export_support_bundle()
            if bundle_path and bundle_path.exists():
                self.notify(message, severity="information")
                run_panel = self._get_run_panel()
                if run_panel:
                    run_panel.write(message)
            else:
                self.notify("Failed to create support bundle", severity="error")
        except Exception as e:
            self.notify(f"Failed to export bundle: {e}", severity="error")

    def action_help(self) -> None:
        """Show the help overlay."""
        self._push_modal(BkxHelpOverlay())

    # =========================================================================
    # Message Handlers - Intent Dispatching
    # =========================================================================

    def on_intent(self, intent: Intent) -> None:
        """Handle all intents from widgets.

        Step 9B: Records all intents to intent history for debug overlay.

        Args:
            intent: Intent message from widget
        """
        # Step 9B: Record intent to history for debugging
        try:
            record_intent(intent)
        except Exception:
            pass  # Don't let intent recording failures break the app

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
            service.transfer(spec)  # type: ignore[attr-defined]

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
        try:
            NotificationHistory.record_status(intent.message, level=str(intent.level), source="bkx")
        except Exception:
            pass
        self._write_to_logs(intent.message, str(intent.level))
        # Convert to proper Literal type for notify compatibility
        severity: Literal["information", "warning", "error"] = "information"
        if intent.level in ("information", "warning", "error"):
            severity = intent.level  # type: ignore[assignment]
        self.notify(intent.message, severity=severity)

    def _handle_show_toast(self, intent: ShowToast) -> None:
        """Handle show toast message."""
        toast_id = str(uuid.uuid4())[:8]
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
            # ToastManager.add_toast uses 'level' and 'duration' parameters
            toast_duration = float(intent.duration) if intent.duration is not None else 3.0
            shown = ToastManager.add_toast(
                intent.message,
                level=str(intent.severity),
                duration=toast_duration,
                toast_id=toast_id,
            )
        except Exception:
            shown = ""

        if not shown:
            self.notify(intent.message, severity="information")

    def _handle_progress_started(self, intent: ProgressStarted) -> None:
        """Handle progress started message."""
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.write(f"[{intent.operation}] {intent.message}")
            run_panel.start_progress(total=intent.total, message=intent.message)

    def _handle_progress_update(self, intent: ProgressUpdate) -> None:
        """Handle progress update message."""
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
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.write(f"[{intent.operation}] {intent.message}")
            run_panel.complete_progress(message=intent.message, success=True)

    def _handle_filter_changed(self, intent: FilterChanged) -> None:
        """Handle filter changed message."""
        run_panel = self._get_run_panel()
        if run_panel:
            run_panel.write(f"Filter: '{intent.filter_text}' ({intent.filtered_count}/{intent.total_count})")

        # Step 6A.2: persist archive filter text (best-effort)
        try:
            if getattr(self, "_active_route", "") in ("archive", "archives"):
                self._tui_state.archive_filter_text = str(intent.filter_text or "")
                self._persist_tui_state()
        except Exception:
            pass

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
    result = app.run()
    return int(result) if result is not None else 0


if __name__ == "__main__":
    run_tui()
