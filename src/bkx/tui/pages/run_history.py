"""Run History Page - Step 10B

UI page displaying past operations with filtering and details.
"""

from __future__ import annotations

from textual.app import ComposeResult
from textual.containers import Container, Horizontal, Vertical
from textual.message import Message
from textual.widgets import Button, Label, ListItem, ListView, RichLog, Select, Static

from bkx.tui.run_history import RunHistoryItem, get_run_history


class RunHistoryItemListItem(ListItem):
    """ListItem subclass with run_id attribute."""

    def __init__(self, *args, run_id: str = "", **kwargs):
        super().__init__(*args, **kwargs)
        self._run_id = run_id


class RunHistoryPage(Container):
    """Page displaying run history with filtering options.

    Features:
    - List of past operations with status, duration, timestamps
    - Filter by status (success, failed, running)
    - Filter by operation type (backup, archive, restore, transfer)
    - Select a run to view details and logs
    """

    DEFAULT_CLASSES = "bkx-page bkx-run-history-page"

    class RunSelected(Message):
        """Emitted when user selects a run from the history."""

        def __init__(self, run_id: str) -> None:
            super().__init__()
            self.run_id = run_id

    class RefreshRequested(Message):
        """Emitted when user requests refresh."""

    def __init__(self) -> None:
        super().__init__()
        self._history = get_run_history()
        self._selected_run_id: str | None = None
        self._status_filter: str = "all"
        self._operation_filter: str = "all"

    def compose(self) -> ComposeResult:
        yield Static("📊 Run History", classes="page-title")

        with Horizontal(classes="history-filters"):
            # Status filter
            yield Label("Status:", classes="filter-label")
            yield Select(
                [
                    ("all", "All"),
                    ("success", "✓ Success"),
                    ("failed", "✕ Failed"),
                    ("running", "▶ Running"),
                    ("cancelled", "⊘ Cancelled"),
                ],
                prompt="Status",
                value="all",
                id="status-filter",
                classes="filter-select",
            )

            # Operation filter
            yield Label("Type:", classes="filter-label")
            yield Select(
                [
                    ("all", "All"),
                    ("backup", "Backup"),
                    ("archive", "Archive"),
                    ("restore", "Restore"),
                    ("transfer", "Transfer"),
                ],
                prompt="Type",
                value="all",
                id="operation-filter",
                classes="filter-select",
            )

            # Actions
            with Horizontal(classes="filter-actions"):
                yield Button("🔄 Refresh", id="btn-refresh", classes="filter-btn")
                yield Button("🗑️ Clear", id="btn-clear", classes="filter-btn")

        with Horizontal(classes="history-content"):
            # Run list
            with Vertical(classes="history-list-container"):
                yield Label("Operations", classes="section-header")
                yield ListView(classes="history-list", id="history-list")

            # Details panel
            with Vertical(classes="history-details"):
                yield Label("Details", classes="section-header")
                yield RichLog(
                    classes="history-details-log",
                    id="history-details-log",
                    highlight=True,
                    markup=True,
                )

    def on_mount(self) -> None:
        """Initialize page after mounting."""
        self._refresh_list()

    def _refresh_list(self) -> None:
        """Refresh the history list based on filters."""
        list_view = self.query_one("#history-list", ListView)
        list_view.clear()

        # Get filtered items
        items = self._get_filtered_items()

        for item in items:
            # Create list item
            list_item = RunHistoryItemListItem(
                self._create_item_content(item),
                id=f"run-{item.run_id}",
                run_id=item.run_id,
            )
            list_view.append(list_item)

        # Update details panel if selection exists
        if self._selected_run_id:
            self._show_details(self._selected_run_id)

    def _get_filtered_items(self) -> list[RunHistoryItem]:
        """Get filtered run history items."""
        items = self._history.get_all()

        # Filter by status
        if self._status_filter != "all":
            items = [i for i in items if i.status == self._status_filter]

        # Filter by operation
        if self._operation_filter != "all":
            items = [i for i in items if i.operation == self._operation_filter]

        return items[:100]  # Limit to 100 items

    def _create_item_content(self, item: RunHistoryItem) -> Vertical:
        """Create the display content for a list item."""
        container = Vertical()

        # Status indicator
        status_icon = {
            "success": "✓",
            "failed": "✕",
            "running": "▶",
            "cancelled": "⊘",
        }.get(item.status, "?")

        status_color = {
            "success": "green",
            "failed": "red",
            "running": "yellow",
            "cancelled": "dim",
        }.get(item.status, "white")

        # Title line
        title = Label(
            f"[{status_color}]{status_icon}[/] [bold]{item.operation.title()}[/] - {item.relative_time}",
            classes="history-item-title",
        )
        container.mount(title)

        # Details line
        details = Label(
            f"  {item.source_summary} • {item.duration_str}",
            classes="history-item-details",
        )
        container.mount(details)

        return container

    def _show_details(self, run_id: str) -> None:
        """Show details for a specific run."""
        details_log = self.query_one("#history-details-log", RichLog)
        details_log.clear()

        item = self._history.get_by_id(run_id)
        if not item:
            details_log.write("[dim]Run not found[/]")
            return

        # Build details content
        status_color = {
            "success": "green",
            "failed": "red",
            "running": "yellow",
            "cancelled": "dim",
        }.get(item.status, "white")

        details_log.write(f"""[bold cyan]Run Details[/]

[dim]Run ID:[/] {item.run_id}
[dim]Operation:[/] {item.operation.title()}
[dim]Status:[/] [{status_color}]{item.status.upper()}[/]
[dim]Started:[/] {item.start_time_str}
[dim]Duration:[/] {item.duration_str}

[dim]Source:[/] {item.source_summary}""")

        if item.destination_summary:
            details_log.write(f"[dim]Destination:[/] {item.destination_summary}")

        if item.archive_path:
            details_log.write(f"[dim]Archive:[/] {item.archive_path}")

        if item.files_count is not None:
            details_log.write(f"[dim]Files:[/] {item.files_count}")

        if item.bytes_transferred is not None:
            bytes_str = self._format_bytes(item.bytes_transferred)
            details_log.write(f"[dim]Size:[/] {bytes_str}")

        if item.error_message:
            details_log.write("")
            details_log.write(f"[red]Error:[/] {item.error_message}")

        if item.log_snippet:
            details_log.write("")
            details_log.write("[dim]Log snippet:[/]")
            details_log.write(item.log_snippet)

    def _format_bytes(self, bytes_count: float) -> str:
        """Format bytes to human-readable string."""
        for unit in ["B", "KB", "MB", "GB", "TB"]:
            if bytes_count < 1024:
                return f"{bytes_count:.1f} {unit}"
            bytes_count /= 1024
        return f"{bytes_count:.1f} PB"

    def on_list_view_selected(self, event: ListView.Selected) -> None:
        """Handle run selection."""
        if isinstance(event.item, ListItem):
            run_id = getattr(event.item, "_run_id", None)
            if run_id:
                self._selected_run_id = run_id
                self._show_details(run_id)
                self.post_message(self.RunSelected(run_id))

    def on_select_changed(self, event: Select.Changed) -> None:
        """Handle filter changes."""
        select_id = event.select.id

        if select_id == "status-filter":
            self._status_filter = str(event.value) if event.value else "all"

        elif select_id == "operation-filter":
            self._operation_filter = str(event.value) if event.value else "all"

        self._refresh_list()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        """Handle button presses."""
        if event.button.id == "btn-refresh":
            self._refresh_list()
            self.post_message(self.RefreshRequested())

        elif event.button.id == "btn-clear":
            self._handle_clear()

    def _handle_clear(self) -> None:
        """Handle clear button."""
        # In a real implementation, show confirmation dialog
        self._history.clear()
        self._selected_run_id = None
        self._refresh_list()
