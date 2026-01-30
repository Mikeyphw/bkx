"""Textual TUI application.

This module provides the TUI experience using Textual and mikomn's BaseTuiApp.
Uses mikomn's unified theme system and TUI widgets.

Install via: pip install .[tui]

Contract rule:
- TUI is opt-in and removable.
- Message-driven; no business logic in widgets.
"""

from __future__ import annotations

from pathlib import Path
from typing import Optional

from textual.app import App, ComposeResult
from textual.widgets import Header, Footer, Static

# mikomn TUI base for theme cycling, command palette, layout modes
from mikomn.tui_base import BaseTuiApp
from mikomn.tui_base.models import MenuItem, MenuConfig
from mikomn.tui_base.menus import show_menu

# Theme integration
from bkx.ui.theme import get_current_theme_name, cycle_theme


class BkxTuiApp(BaseTuiApp):
    """BKX Textual TUI Application.
    
    Extends mikomn's BaseTuiApp for:
    - Theme cycling (t key)
    - Command palette (Ctrl+K)
    - Layout modes (Ctrl+L)
    - Platform-aware CSS
    - Focus management
    """
    
    # BKX-specific bindings in addition to BaseTuiApp bindings
    BINDINGS = BaseTuiApp.BINDINGS + [
        ("b", "show_backup_menu", "Backup"),
        ("a", "show_archive_menu", "Archive"),
        ("r", "show_restore_menu", "Restore"),
        ("t", "show_transfer_menu", "Transfer"),
    ]
    
    # Screens/modes available
    SCREENS = {
        "backup": "bkx.tui.screens.BackupScreen",
        "archive": "bkx.tui.screens.ArchiveScreen",
        "restore": "bkx.tui.screens.RestoreScreen",
        "transfer": "bkx.tui.screens.TransferScreen",
    }
    
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._theme_name = get_current_theme_name()
    
    def get_theme_name(self) -> str:
        """Get current theme name for mikomn integration."""
        return self._theme_name
    
    def cycle_theme_local(self) -> str:
        """Cycle theme and update internal state."""
        self._theme_name = cycle_theme()
        return self._theme_name
    
    def compose(self) -> ComposeResult:
        """Compose the TUI layout."""
        yield Header(show_clock=True)
        yield self.main_content
        yield Footer()
    
    async def on_mount(self) -> None:
        """Called when app is mounted."""
        # Initialize with BKX default theme
        await self.set_theme(self._theme_name)
    
    def action_show_backup_menu(self) -> None:
        """Show backup operations menu."""
        self._show_operation_menu("backup")
    
    def action_show_archive_menu(self) -> None:
        """Show archive operations menu."""
        self._show_operation_menu("archive")
    
    def action_show_restore_menu(self) -> None:
        """Show restore operations menu."""
        self._show_operation_menu("restore")
    
    def action_show_transfer_menu(self) -> None:
        """Show transfer operations menu."""
        self._show_operation_menu("transfer")
    
    def _show_operation_menu(self, operation: str) -> None:
        """Show operation-specific menu."""
        # Use mikomn's show_menu for consistent styling
        items = [
            MenuItem(label="New", value=f"{operation}_new"),
            MenuItem(label="History", value=f"{operation}_history"),
            MenuItem(label="Back", value="back"),
        ]
        
        config = MenuConfig(
            title=f"{operation.capitalize()} Operations",
            items=items,
        )
        
        result = show_menu(config)
        
        if result == "back":
            return
        elif result == f"{operation}_new":
            self.push_screen(f"bkx.tui.screens.{operation.capitalize()}Screen")
        elif result == f"{operation}_history":
            self.notify(f"Showing {operation} history (TODO)")


class BackupScreen:
    """Backup screen placeholder."""
    pass


class ArchiveScreen:
    """Archive screen placeholder."""
    pass


class RestoreScreen:
    """Restore screen placeholder."""
    pass


class TransferScreen:
    """Transfer screen placeholder."""
    pass


def run_tui() -> int:
    """Run the BKX TUI.
    
    Returns:
        Exit code
    """
    app = BkxTuiApp()
    return app.run()


if __name__ == "__main__":
    run_tui()

