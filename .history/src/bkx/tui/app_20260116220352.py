"""Textual TUI application.

This module provides the TUI experience using Textual and mikomn's BaseTuiApp.
Uses mikomn's unified theme system and TUI widgets.

Install via: pip install .[tui]

Contract rule:
- TUI is opt-in and removable.
- Message-driven; no business logic in widgets.
- App dispatches Intents to services, never performs business logic.
"""

from __future__ import annotations

from pathlib import Path
from typing import Optional

from textual.app import App, ComposeResult
from textual.containers import Container, Horizontal
from textual.widgets import Header, Footer

# mikomn TUI base for theme cycling, command palette, layout modes
from mikomn.tui_base import BaseTuiApp
from mikomn.tui import get_theme, generate_tcss, on_theme_change, off_theme_change

# mikomn toast notifications (if available)
try:
    from mikomn.tui_base.widgets import ToastManager, Toast
    _HAS_TOAST = True
except ImportError:
    _HAS_TOAST = False

# Theme integration
from bkx.ui.theme import (
    get_current_theme_name,
    cycle_theme,
    on_theme_change as bkx_on_theme_change,
    off_theme_change as bkx_off_theme_change,
    enable_theme_persistence,
    is_theme_persistence_enabled,
    save_theme_preference,
)

# BKX components
from bkx.tui.panes import NavigationPane, RunLogPane, StatusFooterPane
from bkx.tui.css import load as load_css, reload as reload_css
from bkx.tui.messages import (
    Intent,
    BackupIntent,
    ArchiveIntent,
    RestoreIntent,
    TransferIntent,
    NavigateToScreen,
    UpdateLog,
    SetBusy,
    ClearBusy,
    ShowNotification,
    ShowToast,
    ProgressUpdate,
    ProgressStarted,
    ProgressCompleted,
    FilterChanged,
    ItemsSelected,
    RequestEmptyState,
    ClearEmptyState,
)
from bkx.cli.spec_builders import (
    build_backup_spec,
    build_archive_spec,
    build_restore_spec,
    build_transfer_spec,
)
from bkx.cli.exit_codes import error_to_exit_code, EXIT_OK
from bkx.core.services import get_backup_service, get_archive_service, get_restore_service, get_transfer_service
from bkx.core.errors import BkxError


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
        ("b", "navigate_backup", "Backup"),
        ("a", "navigate_archive", "Archive"),
        ("r", "navigate_restore", "Restore"),
        ("t", "navigate_transfer", "Transfer"),
        ("l", "navigate_logs", "Logs"),
        ("ctrl+n", "toggle_notifications", "Notifications"),
    ]
    
    # Screens registered for navigation
    SCREENS = {
        "backup": "bkx.tui.screens.BackupScreen",
        "archive": "bkx.tui.screens.ArchiveScreen",
        "restore": "bkx.tui.screens.RestoreScreen",
        "transfer": "bkx.tui.screens.TransferScreen",
        "tools": "bkx.tui.screens.ToolsScreen",
        "logs": "bkx.tui.screens.LogsScreen",
    }
    
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._theme_name = get_current_theme_name()
        self._theme_callback_id = None
        self._log_pane: Optional[RunLogPane] = None
        self._status_pane: Optional[StatusFooterPane] = None
        self._progress_pane = None
        self._toast_manager: Optional[ToastManager] = None
        self._notification_queue: list[dict] = []
        self._show_notifications = True
    
    def get_theme_name(self) -> str:
        """Get current theme name for mikomn integration."""
        return self._theme_name
    
    def cycle_theme_local(self) -> str:
        """Cycle theme and update internal state."""
        self._theme_name = cycle_theme()
        return self._theme_name
    
    async def _apply_theme_tcss(self, theme_name: str) -> None:
        """Apply mikomn theme TCSS to the app.
        
        Args:
            theme_name: Name of the theme to apply
        """
        try:
            tcss = generate_tcss(theme_name)
            if tcss:
                self.stylesheet.parse(tcss)
        except Exception:
            pass  # Don't let theme errors break the app
    
    async def on_mount(self) -> None:
        """Called when app is mounted."""
        # Initialize with BKX default theme
        await self.set_theme(self._theme_name)
        
        # Register mikomn theme with Textual
        await self._apply_theme_tcss(self._theme_name)
        
        # Enable theme persistence for TUI
        enable_theme_persistence(True)
        
        # Get references to panes
        self._log_pane = self.query_one("#run-log", RunLogPane)
        self._status_pane = self.query_one("#status-bar", StatusFooterPane)
        
        # Try to get progress pane if it exists
        try:
            self._progress_pane = self.query_one("#progress-pane")
        except Exception:
            pass
        
        # Initialize toast manager if available
        if _HAS_TOAST:
            self._toast_manager = ToastManager()
        
        # Log startup
        self._log_pane.write("BKX TUI started", level="info")
        
        # Register theme change callback to update TCSS
        def _on_theme_changed(new_theme: str):
            self._theme_name = new_theme
            self.call_after_refresh(self._apply_theme_tcss, new_theme)
            if is_theme_persistence_enabled():
                save_theme_preference(new_theme)
        
        self._theme_callback_id = _on_theme_changed
        bkx_on_theme_changed(_on_theme_changed)
    
    def on_unmount(self) -> None:
        """Called when app is unmounted."""
        if self._theme_callback_id:
            bkx_off_theme_change(self._theme_callback_id)
        super().on_unmount()
    
    def compose(self) -> ComposeResult:
        """Compose the TUI layout."""
        yield Header(show_clock=True)
        
        yield Horizontal(
            NavigationPane(),
            Container(
                id="main-content",
            ),
            RunLogPane(id="run-log"),
            StatusFooterPane(id="status-bar"),
            id="tui-layout",
        )
        
        yield Footer()
    
    # =========================================================================
    # Navigation Actions
    # =========================================================================
    
    def action_navigate_backup(self) -> None:
        """Navigate to backup screen."""
        self.push_screen("bkx.tui.screens.BackupScreen")
    
    def action_navigate_archive(self) -> None:
        """Navigate to archive screen."""
        self.push_screen("bkx.tui.screens.ArchiveScreen")
    
    def action_navigate_restore(self) -> None:
        """Navigate to restore screen."""
        self.push_screen("bkx.tui.screens.RestoreScreen")
    
    def action_navigate_transfer(self) -> None:
        """Navigate to transfer screen."""
        self.push_screen("bkx.tui.screens.TransferScreen")
    
    def action_navigate_logs(self) -> None:
        """Navigate to logs screen."""
        self.push_screen("bkx.tui.screens.LogsScreen")
    
    def action_toggle_notifications(self) -> None:
        """Toggle notification display."""
        self._show_notifications = not self._show_notifications
        status = "enabled" if self._show_notifications else "disabled"
        self.notify(f"Notifications {status}", severity="info")
    
    def action_cycle_theme(self) -> None:
        """Cycle to the next theme."""
        self._theme_name = cycle_theme()
        self.theme = self._theme_name
        self.call_after_refresh(self._apply_theme_tcss, self._theme_name)
        self.notify(f"Theme: {self._theme_name}")
        if self._log_pane:
            self._log_pane.write(f"Theme changed to {self._theme_name}", level="info")
    
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
        if self._log_pane:
            self._log_pane.write(f"Starting backup: {intent.sources}", level="info")
        if self._status_pane:
            self._status_pane.set_busy("Backing up...")
        
        # Post progress started
        self.post_message(ProgressStarted(operation="backup", message="Starting backup..."))
        
        try:
            spec = build_backup_spec(**intent.to_spec_params())
            service = get_backup_service()
            result = service.create(spec)
            
            if self._log_pane:
                self._log_pane.write(f"Backup complete: {result.archive_path}", level="success")
                self._log_pane.write(f"  Files: {result.files_count}, Size: {result.bytes_total}", level="info")
            if self._status_pane:
                self._status_pane.set_success("Backup complete")
            
            # Show toast notification
            self.post_message(ShowToast(
                message=f"Backup complete: {result.files_count} files",
                severity="success",
                duration=4.0,
            ))
            
            # Post progress completed
            self.post_message(ProgressCompleted(operation="backup", message="Backup complete"))
            
        except BkxError as e:
            if self._log_pane:
                self._log_pane.write(f"Backup failed: {e}", level="error")
            if self._status_pane:
                self._status_pane.set_error(str(e))
            
            # Show error toast
            self.post_message(ShowToast(
                message=f"Backup failed: {e}",
                severity="error",
                duration=5.0,
            ))
            
            # Post progress completed with error
            self.post_message(ProgressCompleted(operation="backup", message=f"Error: {e}"))
    
    def _handle_archive_intent(self, intent: ArchiveIntent) -> None:
        """Handle archive intent - dispatch to service."""
        if self._log_pane:
            self._log_pane.write(f"Creating archive: {intent.sources}", level="info")
        if self._status_pane:
            self._status_pane.set_busy("Creating archive...")
        
        # Post progress started
        self.post_message(ProgressStarted(operation="archive", message="Creating archive..."))
        
        try:
            spec = build_archive_spec(**intent.to_spec_params())
            service = get_archive_service()
            result = service.create(spec)
            
            if self._log_pane:
                self._log_pane.write(f"Archive created: {result.archive_path}", level="success")
                self._log_pane.write(f"  Files: {result.files_count}", level="info")
            if self._status_pane:
                self._status_pane.set_success("Archive created")
            
            # Show toast notification
            self.post_message(ShowToast(
                message=f"Archive created: {result.files_count} files",
                severity="success",
                duration=4.0,
            ))
            
            # Post progress completed
            self.post_message(ProgressCompleted(operation="archive", message="Archive created"))
            
        except BkxError as e:
            if self._log_pane:
                self._log_pane.write(f"Archive failed: {e}", level="error")
            if self._status_pane:
                self._status_pane.set_error(str(e))
            
            # Show error toast
            self.post_message(ShowToast(
                message=f"Archive failed: {e}",
                severity="error",
                duration=5.0,
            ))
            
            # Post progress completed with error
            self.post_message(ProgressCompleted(operation="archive", message=f"Error: {e}"))
    
    def _handle_restore_intent(self, intent: RestoreIntent) -> None:
        """Handle restore intent - dispatch to service."""
        if self._log_pane:
            self._log_pane.write(f"Restoring: {intent.archive}", level="info")
        if self._status_pane:
            self._status_pane.set_busy("Restoring...")
        
        # Post progress started
        self.post_message(ProgressStarted(operation="restore", message="Restoring archive..."))
        
        try:
            spec = build_restore_spec(**intent.to_spec_params())
            service = get_restore_service()
            result = service.extract(spec)
            
            if self._log_pane:
                self._log_pane.write(f"Restore complete: {result.destination_path}", level="success")
                self._log_pane.write(f"  Files: {result.files_count}", level="info")
            if self._status_pane:
                self._status_pane.set_success("Restore complete")
            
            # Show toast notification
            self.post_message(ShowToast(
                message=f"Restore complete: {result.files_count} files",
                severity="success",
                duration=4.0,
            ))
            
            # Post progress completed
            self.post_message(ProgressCompleted(operation="restore", message="Restore complete"))
            
        except BkxError as e:
            if self._log_pane:
                self._log_pane.write(f"Restore failed: {e}", level="error")
            if self._status_pane:
                self._status_pane.set_error(str(e))
            
            # Show error toast
            self.post_message(ShowToast(
                message=f"Restore failed: {e}",
                severity="error",
                duration=5.0,
            ))
            
            # Post progress completed with error
            self.post_message(ProgressCompleted(operation="restore", message=f"Error: {e}"))
    
    def _handle_transfer_intent(self, intent: TransferIntent) -> None:
        """Handle transfer intent - dispatch to service."""
        if self._log_pane:
            self._log_pane.write(f"Transferring: {intent.source} → {intent.destination}", level="info")
        if self._status_pane:
            self._status_pane.set_busy("Transferring...")
        
        # Post progress started
        self.post_message(ProgressStarted(operation="transfer", message="Transferring files..."))
        
        try:
            spec = build_transfer_spec(**intent.to_spec_params())
            service = get_transfer_service()
            result = service.transfer(spec)
            
            if self._log_pane:
                self._log_pane.write(f"Transfer complete", level="success")
            if self._status_pane:
                self._status_pane.set_success("Transfer complete")
            
            # Show toast notification
            self.post_message(ShowToast(
                message="Transfer complete",
                severity="success",
                duration=4.0,
            ))
            
            # Post progress completed
            self.post_message(ProgressCompleted(operation="transfer", message="Transfer complete"))
            
        except BkxError as e:
            if self._log_pane:
                self._log_pane.write(f"Transfer failed: {e}", level="error")
            if self._status_pane:
                self._status_pane.set_error(str(e))
            
            # Show error toast
            self.post_message(ShowToast(
                message=f"Transfer failed: {e}",
                severity="error",
                duration=5.0,
            ))
            
            # Post progress completed with error
            self.post_message(ProgressCompleted(operation="transfer", message=f"Error: {e}"))
    
    def _handle_navigate(self, intent: NavigateToScreen) -> None:
        """Handle navigation intent."""
        screen_name = intent.screen_name
        if screen_name in self.SCREENS:
            screen_class = self.SCREENS[screen_name]
            self.push_screen(screen_class)
        elif self._log_pane:
            self._log_pane.write(f"Unknown screen: {screen_name}", level="warning")
    
    def _handle_set_busy(self, intent: SetBusy) -> None:
        """Handle set busy state."""
        if self._status_pane:
            self._status_pane.set_busy(intent.message)
    
    def _handle_clear_busy(self) -> None:
        """Clear busy state."""
        if self._status_pane:
            self._status_pane.set_ready()
    
    def _handle_update_log(self, intent: UpdateLog) -> None:
        """Handle update log message."""
        if self._log_pane:
            self._log_pane.write(intent.message, intent.level)
    
    def _handle_show_notification(self, intent: ShowNotification) -> None:
        """Handle show notification message."""
        self.notify(intent.message, severity=intent.level)
    
    def _handle_show_toast(self, intent: ShowToast) -> None:
        """Handle show toast message."""
        if not self._show_notifications:
            return
        
        if _HAS_TOAST and self._toast_manager:
            severity_map = {
                "info": "info",
                "success": "success",
                "warning": "warning",
                "error": "error",
            }
            severity = severity_map.get(intent.severity, "info")
            self._toast_manager.show(Toast(
                message=intent.message,
                severity=severity,
                duration=intent.duration,
            ))
        else:
            # Fallback to Textual's notify
            self.notify(intent.message, severity=intent.severity, duration=intent.duration)
    
    def _handle_progress_started(self, intent: ProgressStarted) -> None:
        """Handle progress started message."""
        if self._progress_pane:
            self._progress_pane.start_progress(
                operation=intent.operation,
                total=intent.total,
                message=intent.message,
            )
        if self._log_pane:
            self._log_pane.write(f"[{intent.operation}] {intent.message}", level="info")
    
    def _handle_progress_update(self, intent: ProgressUpdate) -> None:
        """Handle progress update message."""
        if self._progress_pane:
            self._progress_pane.update_progress(
                current=intent.current,
                message=intent.message,
            )
    
    def _handle_progress_completed(self, intent: ProgressCompleted) -> None:
        """Handle progress completed message."""
        if self._progress_pane:
            self._progress_pane.complete_progress(intent.message)
        if self._log_pane:
            self._log_pane.write(f"[{intent.operation}] {intent.message}", level="success")
    
    def _handle_filter_changed(self, intent: FilterChanged) -> None:
        """Handle filter changed message."""
        if self._log_pane:
            self._log_pane.write(
                f"Filter: '{intent.filter_text}' ({intent.filtered_count}/{intent.total_count})",
                level="debug"
            )
    
    def _handle_items_selected(self, intent: ItemsSelected) -> None:
        """Handle items selected message."""
        if self._log_pane:
            self._log_pane.write(
                f"Selected {len(intent.item_ids)} items for {intent.picker_for}",
                level="info"
            )
    
    def _handle_request_empty_state(self, intent: RequestEmptyState) -> None:
        """Handle request empty state message."""
        # This would be handled by a container that knows how to show empty states
        if self._log_pane:
            self._log_pane.write(
                f"Empty state requested for {intent.container_id}: {intent.title}",
                level="debug"
            )
    
    def _handle_clear_empty_state(self, intent: ClearEmptyState) -> None:
        """Handle clear empty state message."""
        if self._log_pane:
            self._log_pane.write(
                f"Empty state cleared for {intent.container_id}",
                level="debug"
            )


def run_tui() -> int:
    """Run the BKX TUI.
    
    Returns:
        Exit code
    """
    app = BkxTuiApp()
    return app.run()


if __name__ == "__main__":
    run_tui()

