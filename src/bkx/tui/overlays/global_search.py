"""Global Search Overlay - Step 10A

Provides global search functionality accessible from any screen via `/` shortcut.
Searches across archives, commands, pages, and more.
"""

from __future__ import annotations

from textual.app import ComposeResult
from textual.containers import Horizontal, Vertical
from textual.message import Message
from textual.screen import Screen
from textual.widgets import Input, Label, ListItem, ListView

from bkx.tui.messages import NavigateToScreen


class SearchResult:
    """A single search result."""

    def __init__(
        self,
        title: str,
        subtitle: str = "",
        category: str = "Result",
        id: str = "",
        action: str = "",
    ):
        self.title = title
        self.subtitle = subtitle
        self.category = category
        self.id = id
        self.action = action

    def __str__(self) -> str:
        return f"{self.title} ({self.category})"


class GlobalSearchResult(ListItem):
    """A list item representing a search result."""

    DEFAULT_CLASSES = "bkx-search-result"

    def __init__(self, result: SearchResult, **kwargs) -> None:
        super().__init__(**kwargs)
        self._result = result

    @property
    def result(self) -> SearchResult:
        """Get the underlying search result."""
        return self._result


class GlobalSearchScreen(Screen):
    """Global search overlay accessible via `/` shortcut.

    Features:
    - Fuzzy search across multiple categories
    - Quick keyboard navigation
    - Enter to select, Esc to cancel
    """

    BINDINGS = [
        ("escape", "cancel", "Cancel"),
        ("enter", "select", "Select"),
        ("up", "move_up", "Up"),
        ("down", "move_down", "Down"),
    ]

    class ResultSelected(Message):
        """Emitted when user selects a search result."""

        def __init__(self, result: SearchResult) -> None:
            super().__init__()
            self.result = result

    class Cancelled(Message):
        """Emitted when user cancels search."""

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)
        self._results: list[SearchResult] = []
        self._filtered_results: list[SearchResult] = []

    def compose(self) -> ComposeResult:
        with Vertical(classes="bkx-global-search"):
            # Header
            with Horizontal(classes="search-header"):
                yield Label("🔍 Search", classes="search-icon")
                yield Input(
                    placeholder="Type to search...",
                    classes="search-input",
                    id="search-input",
                )

            # Results count
            yield Label("0 results", classes="search-count", id="search-count")

            # Results list
            yield ListView(classes="search-results", id="search-results")

            # Help text
            yield Label("[↓/↑] Navigate  [Enter] Select  [Esc] Cancel", classes="search-help")

    def on_mount(self) -> None:
        """Initialize search after mounting."""
        # Focus the input
        input_widget = self.query_one("#search-input", Input)
        input_widget.focus()

        # Populate initial results
        self._refresh_results("")

    def _on_input_changed(self, event: Input.Changed) -> None:
        """Handle input changes."""
        query = event.value.strip()
        self._refresh_results(query)

    def _refresh_results(self, query: str) -> None:
        """Refresh search results based on query."""
        self._results = self._build_all_results()

        if not query:
            self._filtered_results = self._results
        else:
            query_lower = query.lower()
            self._filtered_results = [
                r
                for r in self._results
                if query_lower in r.title.lower() or query_lower in r.subtitle.lower() or query_lower in r.category.lower()
            ]

        # Limit results
        self._filtered_results = self._filtered_results[:50]

        # Update UI
        self._update_results_list()
        self._update_count_label()

    def _build_all_results(self) -> list[SearchResult]:
        """Build all searchable results."""
        results: list[SearchResult] = []

        # Add page navigation results
        pages = [
            ("backup", "Go to Backup", "Navigation"),
            ("archive", "Go to Archive", "Navigation"),
            ("restore", "Go to Restore", "Navigation"),
            ("transfer", "Go to Transfer", "Navigation"),
            ("tools", "Go to Tools", "Navigation"),
            ("logs", "Go to Logs", "Navigation"),
            ("run_history", "Go to Run History", "Navigation"),
        ]

        for page_id, title, category in pages:
            results.append(
                SearchResult(
                    title=title,
                    subtitle=f"Navigate to {title.replace('Go to ', '')}",
                    category=category,
                    id=f"nav:{page_id}",
                    action=f"navigate:{page_id}",
                )
            )

        # Add command results
        commands = [
            ("Global search", "Search archives and commands", "Command"),
            ("Toggle debug overlay", "Show debug information", "Command"),
            ("Export support bundle", "Create diagnostic bundle", "Command"),
            ("Export logs", "Save run logs to file", "Command"),
            ("Toggle compact mode", "Switch layout mode", "Command"),
            ("Cycle theme", "Change color theme", "Command"),
            ("Open notification center", "View notifications", "Command"),
            ("Jump to last run", "Focus run panel", "Command"),
            ("Focus filter", "Focus search input", "Command"),
        ]

        for title, subtitle, category in commands:
            cmd_id = title.lower().replace(" ", "-").replace("/", "")
            results.append(
                SearchResult(
                    title=title,
                    subtitle=subtitle,
                    category=category,
                    id=f"cmd:{cmd_id}",
                    action=f"command:{cmd_id}",
                )
            )

        # Add sample archive results (would come from ArchiveListPane in real implementation)
        archives = [
            ("backup-2024-01-15.zst", "Last backup archive", "Archive"),
            ("daily-backup.tar.zst", "Daily incremental backup", "Archive"),
            ("important-docs-2024.zip", "Important documents", "Archive"),
        ]

        for title, subtitle, category in archives:
            results.append(
                SearchResult(
                    title=title,
                    subtitle=subtitle,
                    category=category,
                    id=f"archive:{title}",
                    action=f"open-archive:{title}",
                )
            )

        return results

    def _update_results_list(self) -> None:
        """Update the results list widget."""
        list_view = self.query_one("#search-results", ListView)
        list_view.clear()

        for result in self._filtered_results:
            item = GlobalSearchResult(
                result=result,
                id=f"result-{result.id}",
            )
            label = Label(result.title, classes="result-title")
            subtitle = Label(f"{result.subtitle} • {result.category}", classes="result-subtitle")
            item.mount(label)
            item.mount(subtitle)
            list_view.append(item)

    def _update_count_label(self) -> None:
        """Update the results count label."""
        count = len(self._filtered_results)
        label = self.query_one("#search-count", Label)
        label.update(f"{count} result{'s' if count != 1 else ''}")

    def action_cancel(self) -> None:
        """Cancel search and dismiss overlay."""
        self.dismiss(self.Cancelled())

    def action_select(self) -> None:
        """Select the current result."""
        list_view = self.query_one("#search-results", ListView)

        try:
            selected_index = list_view.index
            if selected_index is not None and 0 <= selected_index < len(self._filtered_results):
                result = self._filtered_results[selected_index]
                self.dismiss(self.ResultSelected(result))
        except Exception:
            pass

    def action_move_up(self) -> None:
        """Move selection up."""
        list_view = self.query_one("#search-results", ListView)
        try:
            current = list_view.index or 0
            if current > 0:
                list_view.index = current - 1
        except Exception:
            pass

    def action_move_down(self) -> None:
        """Move selection down."""
        list_view = self.query_one("#search-results", ListView)
        try:
            current = list_view.index or 0
            max_index = len(self._filtered_results) - 1
            if current < max_index:
                list_view.index = current + 1
        except Exception:
            pass


def show_global_search(app) -> None:
    """Show the global search overlay.

    Args:
        app: The Textual app instance
    """
    overlay = GlobalSearchScreen()
    app._push_modal(overlay)


def handle_search_result(app, result: SearchResult) -> None:
    """Handle a search result selection.

    Args:
        app: The Textual app instance
        result: The selected search result
    """
    action = result.action

    if action.startswith("navigate:"):
        route = action.split(":", 1)[1]
        try:
            app.post_message(NavigateToScreen(screen_name=route))
        except Exception:
            pass

    elif action.startswith("command:"):
        cmd_id = action.split(":", 1)[1]
        # Map command IDs to actions
        cmd_mapping = {
            "global-search": "global_search",
            "toggle-debug-overlay": "toggle_debug",
            "export-support-bundle": "export_support_bundle",
            "export-logs": "export_logs",
            "toggle-compact-mode": "toggle_compact",
            "cycle-theme": "cycle_theme",
            "open-notification-center": "notification_center",
            "jump-to-last-run": "jump_to_last_run",
            "focus-filter": "focus_filter",
        }
        action_name = cmd_mapping.get(cmd_id)
        if action_name:
            action_method = getattr(app, f"action_{action_name}", None)
            if callable(action_method):
                action_method()

    elif action.startswith("open-archive:"):
        archive_name = action.split(":", 1)[1]
        # Open archive in details drawer
        try:
            app._tui_state.archive_selected_id = archive_name
            state_save = getattr(app, "_persist_tui_state", None)
            if callable(state_save):
                state_save()
        except Exception:
            pass
