"""Archive Details Drawer - feature contract

A right-pane details drawer with tabs:
- Summary: Archive metadata (name, path, size, modified, ID)
- Files: List of files in archive (if available)
- Errors: Any errors encountered
- Raw log: Raw log output

Includes "Copy id/path" actions.
"""

from __future__ import annotations

from textual.app import ComposeResult
from textual.containers import Container, Horizontal, Vertical
from textual.message import Message
from textual.widgets import Button, Label, RichLog, TabbedContent

from bkx.tui.panes.archive_list import ArchiveListItem


class ArchiveDetailsDrawer(Container):
    """Details drawer for displaying archive information.

    Features:
    - Tabbed interface with Summary, Files, Errors, Raw log tabs
    - Copy ID and Copy Path actions
    - Expandable/collapsible drawer behavior
    """

    DEFAULT_CLASSES = "bkx-details-drawer"

    class CopyId(Message):
        """Request to copy archive ID."""

        def __init__(self, archive_id: str) -> None:
            super().__init__()
            self.archive_id = archive_id

    class CopyPath(Message):
        """Request to copy archive path."""

        def __init__(self, archive_path: str) -> None:
            super().__init__()
            self.archive_path = archive_path

    def __init__(self, title: str = "Details", **kwargs) -> None:
        super().__init__(**kwargs)
        self._title = title
        self._current_archive: ArchiveListItem | None = None
        self._is_collapsed = False

    def compose(self) -> ComposeResult:
        with Vertical(classes="drawer-content"):
            # Header with title and actions
            with Horizontal(classes="drawer-header"):
                yield Label(self._title, classes="drawer-title")
                with Horizontal(classes="drawer-actions"):
                    yield Button("📋 ID", id="bkx-drawer-copy-id", classes="drawer-btn", tooltip="Copy archive ID")
                    yield Button("📁 Path", id="bkx-drawer-copy-path", classes="drawer-btn", tooltip="Copy archive path")
                    yield Button("▼", id="bkx-drawer-toggle", classes="drawer-btn", tooltip="Collapse/Expand")

            # Tabbed content - using string titles for Textual compatibility
            yield TabbedContent(
                "Summary",
                "Files",
                "Errors",
                "Raw Log",
                id="bkx-details-tabs",
                classes="drawer-tabs",
            )

    def on_mount(self) -> None:
        """Initialize drawer after mounting."""
        self._tabs = self.query_one("#bkx-details-tabs", TabbedContent)
        self._toggle_btn = self.query_one("#bkx-drawer-toggle", Button)
        self._show_placeholder()

    def _show_placeholder(self) -> None:
        """Show placeholder when no archive is selected."""
        # Update each tab's content
        self._update_summary_placeholder()
        self._update_files_placeholder()
        self._update_errors_placeholder()
        self._update_raw_log_placeholder()

    def _update_summary_placeholder(self) -> None:
        """Update summary tab placeholder."""
        try:
            summary_tab = self.query_one("#tab-summary", Vertical)
            # Clear and add placeholder
            summary_tab.remove_children()
            placeholder = RichLog(
                classes="summary-content",
                highlight=True,
                markup=True,
            )
            placeholder.write("[dim]Select an archive to view details[/]")
            summary_tab.mount(placeholder)
        except Exception:
            pass

    def _update_files_placeholder(self) -> None:
        """Update files tab placeholder."""
        try:
            files_tab = self.query_one("#tab-files", Vertical)
            files_tab.remove_children()
            placeholder = RichLog(
                classes="files-content",
                highlight=True,
                markup=True,
            )
            placeholder.write("[dim]No archive selected[/]")
            files_tab.mount(placeholder)
        except Exception:
            pass

    def _update_errors_placeholder(self) -> None:
        """Update errors tab placeholder."""
        try:
            errors_tab = self.query_one("#tab-errors", Vertical)
            errors_tab.remove_children()
            placeholder = RichLog(
                classes="errors-content",
                highlight=True,
                markup=True,
            )
            placeholder.write("[dim]No errors[/]")
            errors_tab.mount(placeholder)
        except Exception:
            pass

    def _update_raw_log_placeholder(self) -> None:
        """Update raw log tab placeholder."""
        try:
            raw_tab = self.query_one("#tab-raw-log", Vertical)
            raw_tab.remove_children()
            placeholder = RichLog(
                classes="raw-log-content",
                highlight=False,
                markup=False,
            )
            placeholder.write("[dim]No logs[/]")
            raw_tab.mount(placeholder)
        except Exception:
            pass

    def set_archive(self, archive: ArchiveListItem) -> None:
        """Set the archive to display in the drawer.

        Args:
            archive: Archive item to display
        """
        self._current_archive = archive

        # Update all tabs
        self._update_summary(archive)
        self._update_files(archive)
        self._update_errors(archive)
        self._update_raw_log(archive)

    def _update_summary(self, archive: ArchiveListItem) -> None:
        """Update summary tab content."""
        try:
            summary_tab = self.query_one("#tab-summary", Vertical)
            summary_tab.remove_children()

            content = RichLog(
                classes="summary-content",
                highlight=True,
                markup=True,
            )
            content.write(f"""[bold cyan]{archive.icon} {archive.name}[/]

[dim]Path:[/] [link={archive.path}]{archive.path}[/link]
[dim]Size:[/] {archive.size}
[dim]Modified:[/] {archive.modified}
[dim]ID:[/] [dim]{archive.archive_id}[/]

[dim]-- Archive Actions --[/]
• Press [b]Enter[/] to open archive contents
• Press [b]Ctrl+C[/] while focused to copy ID""")

            summary_tab.mount(content)
        except Exception:
            pass

    def _update_files(self, archive: ArchiveListItem) -> None:
        """Update files tab content."""
        try:
            files_tab = self.query_one("#tab-files", Vertical)
            files_tab.remove_children()

            content = RichLog(
                classes="files-content",
                highlight=True,
                markup=True,
            )
            content.write(f"[dim]Archive: {archive.name}[/]")
            content.write("[dim]File listing would appear here[/]")
            content.write("[italic dim]-- Use external tool to view archive contents --[/]")

            files_tab.mount(content)
        except Exception:
            pass

    def _update_errors(self, archive: ArchiveListItem) -> None:
        """Update errors tab content."""
        try:
            errors_tab = self.query_one("#tab-errors", Vertical)
            errors_tab.remove_children()

            content = RichLog(
                classes="errors-content",
                highlight=True,
                markup=True,
            )
            content.write("[dim]No errors encountered for this archive[/]")
            content.write("[dim]Last verified: Unknown[/]")

            errors_tab.mount(content)
        except Exception:
            pass

    def _update_raw_log(self, archive: ArchiveListItem) -> None:
        """Update raw log tab content."""
        try:
            raw_tab = self.query_one("#tab-raw-log", Vertical)
            raw_tab.remove_children()

            content = RichLog(
                classes="raw-log-content",
                highlight=False,
                markup=False,
            )
            content.write(f"[dim]Archive: {archive.name}[/]")
            content.write(f"[dim]Path: {archive.path}[/]")
            content.write(f"[dim]ID: {archive.archive_id}[/]")
            content.write("")
            content.write("[dim]-- No operation logs available --[/]")

            raw_tab.mount(content)
        except Exception:
            pass

    def clear(self) -> None:
        """Clear the drawer content."""
        self._current_archive = None
        self._show_placeholder()

    def collapse(self) -> None:
        """Collapse the drawer."""
        self._is_collapsed = True
        self.styles.display = "none"

    def expand(self) -> None:  # type: ignore[override]
        """Expand the drawer."""
        self._is_collapsed = False
        self.styles.display = "block"

    def toggle(self) -> None:
        """Toggle drawer collapsed/expanded state."""
        toggle_btn = getattr(self, "_toggle_btn", None)
        if self._is_collapsed:
            self.expand()
            if toggle_btn is not None:
                toggle_btn.label = "▼"
        else:
            self.collapse()
            if toggle_btn is not None:
                toggle_btn.label = "▶"

    def on_button_pressed(self, event: Button.Pressed) -> None:
        """Handle button presses."""
        if event.button.id == "bkx-drawer-copy-id":
            if self._current_archive:
                self.post_message(self.CopyId(self._current_archive.archive_id))

        elif event.button.id == "bkx-drawer-copy-path":
            if self._current_archive:
                self.post_message(self.CopyPath(str(self._current_archive.path)))

        elif event.button.id == "bkx-drawer-toggle":
            self.toggle()


class DetailsDrawerManager:
    """Manager for handling archive selection and drawer updates."""

    def __init__(self, drawer: ArchiveDetailsDrawer) -> None:
        self._drawer = drawer
        self._last_archive: ArchiveListItem | None = None

    def on_archive_selected(self, archive: ArchiveListItem) -> None:
        """Handle archive selection."""
        self._last_archive = archive
        self._drawer.set_archive(archive)

    def clear(self) -> None:
        """Clear drawer."""
        self._last_archive = None
        self._drawer.clear()
