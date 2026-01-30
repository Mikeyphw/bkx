"""Archive list pane - Filterable list for searching archives.

Uses mikomn's FilterableListPane for searchable archive browsing.
"""

from __future__ import annotations

from pathlib import Path
from typing import Callable, Optional

from textual.app import ComposeResult
from textual.containers import Container
from textual.message import Message
from textual.widgets import Label, RichLog

# mikomn TUI base widgets for enhanced UI
try:
    from mikomn.tui_base.panes import FilterableListPane
    _HAS_MIKOMN = True
except ImportError:
    _HAS_MIKOMN = False
    # Fallback to simple implementation
    from textual.widgets import Input, ListView, ListItem


class ArchiveListItem:
    """Represents an archive in the list."""
    
    def __init__(
        self,
        archive_id: str,
        name: str,
        path: Path,
        size: str = "",
        modified: str = "",
        icon: str = "📦",
    ):
        self.archive_id = archive_id
        self.name = name
        self.path = path
        self.size = size
        self.modified = modified
        self.icon = icon
    
    def __str__(self) -> str:
        return f"{self.icon} {self.name}"
    
    def to_dict(self) -> dict:
        """Convert to dictionary for display."""
        return {
            "id": self.archive_id,
            "name": self.name,
            "path": str(self.path),
            "size": self.size,
            "modified": self.modified,
        }


if _HAS_MIKOMN:
    # Use mikomn's FilterableListPane when available
    class ArchiveListPane(FilterableListPane[str]):
        """Filterable list pane for browsing archives.
        
        Features:
        - Search/filter archives by name
        - Shows archive name, path, size, and modified date
        - Emits messages when selection changes
        """
        
        class FilterChanged(Message):
            """Filter text changed."""
        
        class ArchiveSelected(Message):
            """Archive was selected."""
        
        def __init__(
            self,
            title: str = "Archives",
            filter_placeholder: str = "Search archives...",
            **kwargs,
        ):
            super().__init__(
                title=title,
                filter_placeholder=filter_placeholder,
                **kwargs
            )
            self._archives: list[ArchiveListItem] = []
        
        def set_archives(self, archives: list[ArchiveListItem]) -> None:
            """Set the list of archives to display.
            
            Args:
                archives: List of archive items
            """
            self._archives = archives
            items = [(str(a), a.to_dict()) for a in archives]
            self.set_items(items)
            self._update_count_label()
        
        def _update_count_label(self) -> None:
            """Update the count label."""
            if hasattr(self, "_count_label"):
                total = len(self._archives)
                filtered = self.get_filtered_count()
                self._count_label.update(f"Showing {filtered}/{total} archives")
        
        def on_filter_changed(self, event: FilterableListPane.FilterChanged) -> None:
            """Handle filter changes."""
            self.post_message(self.FilterChanged(
                filter_text=event.filter_text,
                filtered_count=event.filtered_count,
                total_count=event.total_count,
            ))
            self._update_count_label()
        
        def on_selection_changed(self, event: FilterableListPane.SelectionChanged) -> None:
            """Handle selection changes."""
            if event.selected_id and self._archives:
                for archive in self._archives:
                    if archive.archive_id == event.selected_id:
                        self.post_message(self.ArchiveSelected(
                            archive_id=archive.archive_id,
                            archive_path=archive.path,
                            archive_data=archive.to_dict(),
                        ))
                        break

else:
    # Fallback implementation without mikomn
    class ArchiveListPane(Container):
        """Simple list pane for archives (fallback when mikomn not available)."""
        
        class FilterChanged(Message):
            """Filter text changed."""
        
        class ArchiveSelected(Message):
            """Archive was selected."""
        
        def __init__(
            self,
            title: str = "Archives",
            filter_placeholder: str = "Search archives...",
            **kwargs,
        ):
            super().__init__(**kwargs)
            self._title = title
            self._filter_placeholder = filter_placeholder
            self._archives: list[ArchiveListItem] = []
            self._filtered_archives: list[ArchiveListItem] = []
        
        def compose(self) -> ComposeResult:
            yield Label(self._title, classes="header")
            yield Input(placeholder=self._filter_placeholder, classes="filter-input")
            yield ListView(classes="archive-list")
            yield Label("", classes="count-label", id="count-label")
        
        def on_mount(self) -> None:
            self._list_view = self.query_one(ListView)
            self._input = self.query_one(Input)
            self._count_label = self.query_one("#count-label", Label)
        
        def set_archives(self, archives: list[ArchiveListItem]) -> None:
            """Set the list of archives to display."""
            self._archives = archives
            self._apply_filter(self._input.value)
        
        def _apply_filter(self, filter_text: str) -> None:
            """Apply filter to archives list."""
            if not filter_text.strip():
                self._filtered_archives = self._archives
            else:
                lower_filter = filter_text.lower()
                self._filtered_archives = [
                    a for a in self._archives
                    if lower_filter in a.name.lower() or lower_filter in str(a.path).lower()
                ]
            
            # Update list view
            self._list_view.clear()
            for archive in self._filtered_archives:
                item = ListItem(
                    Label(f"{archive.icon} {archive.name}\n    {archive.path} [{archive.size}]", classes="archive-item"),
                    id=f"archive-{archive.archive_id}",
                )
                item._archive = archive
                self._list_view.append(item)
            
            self._update_count_label()
        
        def _update_count_label(self) -> None:
            """Update the count label."""
            total = len(self._archives)
            filtered = len(self._filtered_archives)
            self._count_label.update(f"Showing {filtered}/{total} archives")
        
        def on_input_changed(self, event: Input.Changed) -> None:
            """Handle filter input changes."""
            self._apply_filter(event.value)
            self.post_message(self.FilterChanged(
                filter_text=event.value,
                filtered_count=len(self._filtered_archives),
                total_count=len(self._archives),
            ))
        
        def on_list_view_selected(self, event: ListView.Selected) -> None:
            """Handle selection changes."""
            if isinstance(event.item, ListItem) and hasattr(event.item, "_archive"):
                archive = event.item._archive
                self.post_message(self.ArchiveSelected(
                    archive_id=archive.archive_id,
                    archive_path=archive.path,
                    archive_data=archive.to_dict(),
                ))


class ArchivePreviewPane(Container):
    """Preview pane for showing archive details.
    
    Features:
    - Shows archive metadata (name, path, size, modified)
    - Displays archive contents when available
    - Supports text, JSON, and markdown content types
    """
    
    class PreviewUpdate(Message):
        """Preview content was updated."""
    
    def __init__(self, title: str = "Preview", **kwargs):
        super().__init__(**kwargs)
        self._title = title
        self._current_archive: Optional[ArchiveListItem] = None
        self._content = None  # Will be set in compose/on_mount
    
    def compose(self) -> ComposeResult:
        yield Label(self._title, classes="preview-header")
        yield RichLog(
            classes="preview-content",
            id="preview-content",
            highlight=True,
            markup=True,
        )
    
    def on_mount(self) -> None:
        self._content = self.query_one("#preview-content", RichLog)
        self._show_placeholder()
    
    def _show_placeholder(self) -> None:
        """Show placeholder message."""
        if self._content:
            self._content.write(
                "[dim]Select an archive to preview[/]",
                wrap=True,
            )
    
    def set_archive(self, archive: ArchiveListItem) -> None:
        """Set the archive to preview.
        
        Args:
            archive: Archive item to preview
        """
        self._current_archive = archive
        
        if not self._content:
            return
        
        # Build preview content
        preview_text = f"""[bold cyan]{archive.icon} {archive.name}[/]

[dim]Path:[/] {archive.path}
[dim]Size:[/] {archive.size}
[dim]Modified:[/] {archive.modified}
[dim]ID:[/] {archive.archive_id}

[dim]-- Select to view contents --[/]"""
        
        self._content.clear()
        self._content.write(preview_text)
        self.post_message(self.PreviewUpdate(archive=archive.to_dict()))
    
    def clear(self) -> None:
        """Clear the preview."""
        self._current_archive = None
        self._show_placeholder()


# Helper function to create archive list items from paths
def create_archive_list_items(archive_paths: list[Path]) -> list[ArchiveListItem]:
    """Create ArchiveListItem objects from file paths.
    
    Args:
        archive_paths: List of archive file paths
        
    Returns:
        List of ArchiveListItem objects
    """
    items = []
    for path in archive_paths:
        if path.exists() and path.is_file():
            stat = path.stat()
            import os
            size = os.path.getsize(path)
            size_str = _format_size(size)
            modified = _format_date(stat.st_mtime)
            name = path.name
            
            items.append(
                ArchiveListItem(
                    archive_id=str(path),
                    name=name,
                    path=path,
                    size=size_str,
                    modified=modified,
                )
            )
    return items


def _format_size(size_bytes: int) -> str:
    """Format bytes to human-readable size."""
    for unit in ["B", "KB", "MB", "GB", "TB"]:
        if size_bytes < 1024:
            return f"{size_bytes:.1f} {unit}"
        size_bytes /= 1024
    return f"{size_bytes:.1f} PB"


def _format_date(timestamp: float) -> str:
    """Format timestamp to readable date string."""
    import datetime
    return datetime.datetime.fromtimestamp(timestamp).strftime("%Y-%m-%d %H:%M")

