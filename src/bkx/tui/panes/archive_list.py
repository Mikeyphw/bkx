"""Archive list pane - Filterable list for searching archives.

Uses mikomn's FilterableListPane.
"""

from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

from mikomn_tui.base.models import FilterGroup, FilterOption
from mikomn_tui.base.panes.common.filterable_list_pane import FilterableListPane
from textual.app import ComposeResult
from textual.containers import Container
from textual.message import Message
from textual.widgets import Label, RichLog

if TYPE_CHECKING:
    from mikomn_tui.base.panes.common.filterable_list_pane import FilterableListPane as BaseFilterableListPane


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

    @property
    def extension(self) -> str:
        suffixes = self.path.suffixes
        if not suffixes:
            return "(none)"
        return "".join(suffixes)

    def __str__(self) -> str:
        return f"{self.icon} {self.name}"

    def to_dict(self) -> dict:
        return {
            "id": self.archive_id,
            "name": self.name,
            "path": str(self.path),
            "size": self.size,
            "modified": self.modified,
        }


class ArchiveListPane(FilterableListPane[ArchiveListItem]):
    """Filterable list pane for browsing archives."""

    DEFAULT_CLASSES = "bkx-archive-list-pane"
    BINDINGS = [
        ("f", "filters", "Filters"),
    ]

    class FilterChanged(Message):  # type: ignore[reportIncompatibleVariableOverride]
        """Filter text changed."""

        def __init__(self, filter_text: str, filtered_count: int, total_count: int) -> None:
            super().__init__()
            self.filter_text = filter_text
            self.filtered_count = filtered_count
            self.total_count = total_count

    class ArchiveSelected(Message):
        """Archive was selected."""

        def __init__(self, archive_id: str, archive_path: Path, archive_data: dict) -> None:
            super().__init__()
            self.archive_id = archive_id
            self.archive_path = archive_path
            self.archive_data = archive_data

    def __init__(
        self,
        title: str = "Archives",
        filter_placeholder: str = "Search archives...",
        **kwargs,
    ):
        super().__init__(title=title, filter_placeholder=filter_placeholder, **kwargs)
        self._archives: list[ArchiveListItem] = []
        self._ext_filter: str | None = None
        self._current_archive: ArchiveListItem | None = None  # Track selected archive

    def set_archives(self, archives: list[ArchiveListItem]) -> None:
        self._archives = archives
        self._apply_extension_filter()

    def action_filters(self) -> None:
        app = getattr(self, "app", None)
        if app is None:
            return
        open_fn = getattr(app, "open_archive_filter_sheet", None)
        if callable(open_fn):
            open_fn(self)

    def set_extension_filter(self, ext: str | None) -> None:
        self._ext_filter = ext
        self._apply_extension_filter()

    def _apply_extension_filter(self) -> None:
        archives = self._archives
        if self._ext_filter and self._ext_filter != "*":
            archives = [a for a in archives if a.extension == self._ext_filter]
        self.set_items([(str(a), a) for a in archives])

    # -------------------------------------------------------------------------
    # Persistence helpers (best-effort, no dependency on internal mikomn API)
    # -------------------------------------------------------------------------

    def set_filter_text(self, text: str) -> None:  # type: ignore[override]
        """Set the filter text if the base class supports it."""
        fn = getattr(super(), "set_filter_text", None)
        if callable(fn):
            try:
                fn(text)
            except Exception:
                pass

    def focus_filter(self) -> None:
        """Focus the filter input if available."""
        fn = getattr(super(), "focus_filter", None)
        if callable(fn):
            try:
                fn()
            except Exception:
                pass

    def select_archive_id(self, archive_id: str) -> None:
        """Select an archive by id when possible."""
        try:
            for a in self._archives:
                if a.archive_id == archive_id:
                    for cand in ("select_value", "set_selected", "select_item", "select"):
                        fn = getattr(self, cand, None)
                        if callable(fn):
                            try:
                                fn(a)
                                return
                            except Exception:
                                continue
                    return
        except Exception:
            return

    # -------------------------------------------------------------------------
    # Scroll position persistence helpers
    # -------------------------------------------------------------------------

    def get_scroll_position(self) -> int:
        """Get current scroll position (best-effort)."""
        try:
            # Try to get scroll position from the underlying list/scroll view
            for attr in ("scroll_y", "scroll_target_y", "_scroll_y"):
                pos = getattr(self, attr, None)
                if isinstance(pos, (int, float)) and pos >= 0:
                    return int(pos)
        except Exception:
            pass
        return 0

    def set_scroll_position(self, position: int) -> None:
        """Set scroll position (best-effort)."""
        try:
            for method_name in ("scroll_to", "scroll_y", "scroll_to_line"):
                method = getattr(self, method_name, None)
                if callable(method):
                    try:
                        method(position)
                        return
                    except Exception:
                        continue
        except Exception:
            pass

    # -------------------------------------------------------------------------
    # Keyboard navigation (j/k for selection, Enter for details, Esc for clear)
    # -------------------------------------------------------------------------

    def action_move_selection_down(self) -> None:
        """Move selection down (j key)."""
        try:
            for method_name in ("action_cursor_down", "action_down", "_move_cursor_down"):
                method = getattr(self, method_name, None)
                if callable(method):
                    method()
                    return
        except Exception:
            pass

    def action_move_selection_up(self) -> None:
        """Move selection up (k key)."""
        try:
            for method_name in ("action_cursor_up", "action_up", "_move_cursor_up"):
                method = getattr(self, method_name, None)
                if callable(method):
                    method()
                    return
        except Exception:
            pass

    def action_activate_selection(self) -> None:
        """Activate selected item (Enter key)."""
        # Emit ArchiveSelected for currently selected item
        try:
            # Try to get selected item
            for attr in ("_selected_item", "selected", "_last_selected"):
                item = getattr(self, attr, None)
                if isinstance(item, ArchiveListItem):
                    self.post_message(self.ArchiveSelected(item.archive_id, item.path, item.to_dict()))
                    return
        except Exception:
            pass

    def action_clear_filter(self) -> None:
        """Clear filter and reset selection (Esc key)."""
        try:
            clear_fn = getattr(super(), "clear_filter", None)
            if callable(clear_fn):
                clear_fn()
            # Also try to clear input directly
            for attr in ("_input", "_filter_input", "filter_input"):
                inp = getattr(self, attr, None)
                if inp is not None and hasattr(inp, "value"):
                    inp.value = ""
                    break
        except Exception:
            pass

    def build_extension_filter_group(self) -> FilterGroup | None:
        counts: dict[str, int] = {}
        for a in self._archives:
            counts[a.extension] = counts.get(a.extension, 0) + 1
        if not counts:
            return None

        options = [FilterOption(value="*", label="All", selected=self._ext_filter in (None, "*"))]
        for ext in sorted(counts.keys()):
            options.append(
                FilterOption(
                    value=ext,
                    label=f"{ext} ({counts[ext]})",
                    selected=self._ext_filter == ext,
                )
            )
        return FilterGroup(field="extension", label="Extension", options=options, multi_select=False)

    def on_filter_changed(self, event: FilterableListPane.FilterChanged) -> None:
        self.post_message(self.FilterChanged(event.filter_text, event.filtered_count, event.total_count))

    def on_selection_changed(self, event: FilterableListPane.SelectionChanged) -> None:
        archive = getattr(event, "single_selected", None)
        if not isinstance(archive, ArchiveListItem):
            return
        self.post_message(self.ArchiveSelected(archive.archive_id, archive.path, archive.to_dict()))


class ArchivePreviewPane(Container):
    """Preview pane for showing archive details.

    Features:
    - Shows archive metadata (name, path, size, modified)
    - Displays archive contents when available
    - Supports text, JSON, and markdown content types
    """

    DEFAULT_CLASSES = "bkx-archive-preview-pane"

    class PreviewUpdate(Message):
        """Preview content was updated."""

        def __init__(self, archive: dict) -> None:
            super().__init__()
            self.archive = archive

    def __init__(self, title: str = "Preview", **kwargs):
        super().__init__(**kwargs)
        self._title = title
        self._current_archive: ArchiveListItem | None = None
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
            self._content.write("[dim]Select an archive to preview[/]")

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


def _format_size(size_bytes: float) -> str:
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
