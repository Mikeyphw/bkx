from __future__ import annotations

"""bkx.tui.overlays.help_overlay

Step 4B & 11D:
Wrapper around mikomn HelpOverlay providing a stable BKX class for styling.
Enhanced with keyboard shortcuts reference.
"""

# ruff: noqa: E402 - Textual imports must come after docstring for module structure
from textual.containers import Horizontal, Vertical
from textual.widgets import Label, Static


class BkxHelpOverlay:
    """BKX-styled HelpOverlay with keyboard shortcuts reference.

    Provides a comprehensive help overlay with tabs for:
    - Keyboard Shortcuts
    - Navigation Guide
    - Tips & Tricks
    """

    BINDINGS = [
        ("escape", "close", "Close"),
        ("ctrl+h", "close", "Close"),
    ]

    def __init__(self, bindings: list | None = None, **kwargs) -> None:
        """Initialize the help overlay.

        Args:
            bindings: Optional list of bindings (ignored, uses defaults)
            **kwargs: Additional arguments
        """
        self._bindings = bindings or []
        self._kwargs = kwargs

    @property
    def bindings(self) -> list:
        """Get bindings."""
        return self._bindings

    def compose(self):
        """Compose help overlay with tabs (for testing)."""
        from textual.widgets import TabbedContent

        yield TabbedContent(
            "Shortcuts",
            "Navigation",
            "Tips",
            id="bkx-help-tabs",
        )

    def get_shortcuts_text(self) -> str:
        """Get keyboard shortcuts as formatted text."""
        text = "[bold]Keyboard Shortcuts[/]\n\n"

        shortcuts = [
            (
                "Navigation",
                [
                    ("1-6", "Go to page (Backup, Archive, Restore, Transfer, Tools, Logs)"),
                    ("Ctrl+K", "Open command palette"),
                    ("/", "Global search"),
                    ("j / k", "Move selection down / up"),
                    ("Enter", "Activate selected item"),
                    ("Esc", "Cancel / Clear filter / Go back"),
                ],
            ),
            (
                "Actions",
                [
                    ("Ctrl+N", "Open notification center"),
                    ("N", "Toggle toasts"),
                    ("t", "Cycle theme"),
                    ("Ctrl+L", "Toggle layout mode"),
                    ("Ctrl+D", "Toggle debug overlay"),
                ],
            ),
            (
                "App",
                [
                    ("q", "Quit application"),
                    ("r", "Restart (on crash screen)"),
                ],
            ),
        ]

        for category, items in shortcuts:
            text += f"[bold cyan]{category}[/]\n"
            for key, description in items:
                text += f"  [bold]{key}[/]  {description}\n"
            text += "\n"

        return text

    def get_navigation_text(self) -> str:
        """Get navigation guide as formatted text."""
        text = "[bold]Navigation Guide[/]\n\n"

        navigation = [
            ("Sidebar", "Click sidebar items or use number keys (1-6)"),
            ("Footer Tabs", "In compact mode, use footer tabs for navigation"),
            ("Command Palette", "Ctrl+K opens searchable command list"),
            ("Global Search", "Press / to search across pages, commands, archives"),
            ("Archive List", "j/k to navigate, Enter to view details, Esc to clear filter"),
        ]

        for title, description in navigation:
            text += f"[bold green]{title}[/]: {description}\n"

        return text

    def get_tips_text(self) -> str:
        """Get tips & tricks as formatted text."""
        text = "[bold]Tips & Tricks[/]\n\n"

        tips = [
            ("🔍 Quick Search", "Press / anywhere to search"),
            ("📋 Copy IDs", "Use command palette to copy archive IDs/paths"),
            ("📦 Details Drawer", "Select an archive and press Enter to view details"),
            ("🎨 Theme", "Press t to cycle through themes"),
            ("📊 Debug Overlay", "Press Ctrl+D to see focus path and state"),
            ("💾 Auto-save", "UI state is automatically saved and restored"),
            ("⚡ Quick Actions", "Command palette has quick actions for common tasks"),
        ]

        for icon_title, description in tips:
            text += f"[bold]{icon_title}[/] {description}\n"

        return text


# Keyboard Shortcuts Reference Widget (standalone)
class ShortcutsReference(Vertical):
    """Standalone keyboard shortcuts reference widget.

    Can be embedded in any page for quick reference.
    """

    DEFAULT_CLASSES = "bkx-shortcuts-reference"

    def compose(self):
        """Compose the shortcuts reference."""

        yield Static("[bold cyan]Keyboard Shortcuts[/]", classes="shortcuts-title")

        # Quick reference grid
        shortcuts = [
            (
                "Navigation",
                [
                    ("1-6", "Pages"),
                    ("Ctrl+K", "Commands"),
                    ("/", "Search"),
                    ("Esc", "Cancel"),
                ],
            ),
            (
                "Selection",
                [
                    ("j/k", "Up/Down"),
                    ("Enter", "Select"),
                ],
            ),
            (
                "App",
                [
                    ("t", "Theme"),
                    ("q", "Quit"),
                ],
            ),
        ]

        for category, items in shortcuts:
            yield Static(f"\n[bold]{category}[/]", classes="shortcuts-category")
            for key, desc in items:
                row = Horizontal(classes="shortcuts-row")
                row.mount(Label(f"[dim][{key}][/]", classes="shortcuts-key"))
                row.mount(Label(desc, classes="shortcuts-desc"))
                yield row


# Context-sensitive help for pages
PAGE_HELP = {
    "backup": "Create backups of your important files.",
    "archive": "Browse and manage existing archives.",
    "restore": "Restore files from archives.",
    "transfer": "Transfer files between locations.",
    "tools": "Configuration and utility tools.",
    "logs": "View operation logs and history.",
    "run_history": "View past operations and their results.",
}


def get_page_help(page_name: str) -> str:
    """Get help text for a specific page.

    Args:
        page_name: The page route name

    Returns:
        Help text for the page
    """
    return PAGE_HELP.get(page_name, "No help available for this page.")
