"""Navigation pane - Sidebar with operation menu."""

from __future__ import annotations

from textual.app import ComposeResult
from textual.containers import Container
from textual.widgets import Label, ListItem, ListView

from bkx.tui.messages import NavigateToScreen


class NavListItem(ListItem):
    """Navigation list item with icon and label."""

    def __init__(self, label: str, value: str, icon: str = "📁"):
        super().__init__(Label(f"{icon} {label}"), id=f"nav-{value}")
        self.label = label
        self.value = value
        self.icon = icon


class NavigationPane(Container):
    """Sidebar navigation pane for operation selection.

    Uses mikomn's SidebarPane as base pattern but implements
    a simple ListView-based navigation for BKX operations.
    """

    DEFAULT_CLASSES = "bkx-navigation-pane"

    def compose(self) -> ComposeResult:
        yield Label("BKX\nBackup Tool", classes="nav-header")
        yield ListView(
            NavListItem("Backup", "backup", "💾"),
            NavListItem("Archive", "archive", "📦"),
            NavListItem("Restore", "restore", "📥"),
            NavListItem("Transfer", "transfer", "🔄"),
            NavListItem("Tools", "tools", "🔧"),
            NavListItem("Logs", "logs", "📋"),
        )

    def on_list_view_selected(self, event: ListView.Selected) -> None:
        """Handle navigation item selection."""
        item = event.item
        if isinstance(item, NavListItem):
            self.post_message(NavigateToScreen(screen_name=item.value))

    def on_mount(self) -> None:
        """Focus the list view on mount."""
        list_view = self.query_one(ListView)
        list_view.focus()

    def navigate_to(self, screen_name: str) -> None:
        """Programmatically navigate to a screen.

        Args:
            screen_name: Name of screen to navigate to
        """
        list_view = self.query_one(ListView)
        for index, item in enumerate(list_view):  # type: ignore[arg-type]
            if isinstance(item, NavListItem) and item.value == screen_name:
                list_view.focus()
                list_view.selected = index  # type: ignore[attr-defined]
                break
