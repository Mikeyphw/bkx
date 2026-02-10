"""Enhanced Debug Overlay - Step 9B

Displays debug information including:
- Focus path (DOM hierarchy)
- Active route
- Selected archive
- Filter text
- Last N intents
- Active progress operations
"""

from __future__ import annotations

from textual.app import ComposeResult
from textual.containers import Container, Horizontal, Vertical
from textual.message import Message
from textual.widgets import Label, RichLog, Static

from bkx.tui.intent_history import get_intent_history
from bkx.tui.state import load_state


class DebugOverlayPane(Container):
    """Debug overlay pane showing internal state.

    Step 9B: Enhanced with focus path, route, archive, filter, intents, progress.
    """

    DEFAULT_CLASSES = "bkx-debug-overlay"

    class CloseRequested(Message):
        """User requested to close the debug overlay."""

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)
        self._state = load_state()

    def compose(self) -> ComposeResult:
        with Vertical(classes="debug-content"):
            # Header
            with Horizontal(classes="debug-header"):
                yield Label("🔧 Debug Overlay", classes="debug-title")
                yield Label(f"Route: {self._state.last_route}", classes="debug-route")
                yield Static("Press Esc to close", classes="debug-hint")

            # Focus Path Section
            yield Label("Focus Path:", classes="debug-section-title")
            with Container(classes="debug-focus-path"):
                yield Label("Shell > ContentRegion > ArchiveListPane > ListView", classes="debug-focus-text", id="debug-focus-path")

            # Active State Section
            yield Label("Active State:", classes="debug-section-title")
            with Container(classes="debug-state"):
                yield Label(f"Layout: {self._state.layout_mode}", classes="debug-state-item", id="debug-layout-mode")
                yield Label(f"Toasts: {'On' if self._state.show_toasts else 'Off'}", classes="debug-state-item", id="debug-toasts")

            # Selected Archive
            yield Label("Selected Archive:", classes="debug-section-title")
            with Container(classes="debug-archive"):
                yield Label(self._state.archive_selected_id or "None", classes="debug-archive-text", id="debug-selected-archive")

            # Filter State
            yield Label("Filter State:", classes="debug-section-title")
            with Container(classes="debug-filter"):
                yield Label(f"Text: {self._state.archive_filter_text or '(none)'}", classes="debug-filter-text", id="debug-filter-text")

            # Last Intents Section
            yield Label("Last Intents:", classes="debug-section-title")
            yield RichLog(
                classes="debug-intents",
                id="debug-intents-log",
                highlight=False,
                markup=True,
            )

            # Active Progress Section
            yield Label("Active Progress:", classes="debug-section-title")
            with Container(classes="debug-progress"):
                yield Label("No active operations", classes="debug-progress-text", id="debug-progress")

    def on_mount(self) -> None:
        """Update the debug overlay with current state."""
        self._update_intents()
        self._update_focus_path()
        self._update_progress()

    def _update_intents(self) -> None:
        """Update the intents display."""
        try:
            intents_log = self.query_one("#debug-intents-log", RichLog)
            history = get_intent_history()
            records = history.get_recent(10)  # Last 10 intents

            intents_log.clear()
            if not records:
                intents_log.write("[dim]No intents recorded yet[/]")
            else:
                for record in records:
                    intents_log.write(f"[cyan]{record.timestamp_str}[/] [green]{record.intent_type}[/]: {record.message}")
        except Exception:
            pass

    def _update_focus_path(self) -> None:
        """Update the focus path display."""
        try:
            focus_label = self.query_one("#debug-focus-path", Label)
            app = self.app

            # Build focus path
            focused = getattr(app, "focused", None)
            if focused:
                path_parts = []
                widget = focused
                while widget:
                    name = getattr(widget, "id", None) or getattr(widget, "_id", "")
                    if not name:
                        name = type(widget).__name__
                    path_parts.append(name)
                    widget = getattr(widget, "parent", None)

                path_str = " > ".join(reversed(path_parts))
                focus_label.update(path_str)
        except Exception:
            pass

    def _update_progress(self) -> None:
        """Update the progress display."""
        try:
            progress_label = self.query_one("#debug-progress", Label)
            # Check if RunPanel has active progress
            from bkx.tui.widgets.shell import BkxShell

            shell = None
            try:
                shell = self.query_one(BkxShell)
            except Exception:
                pass

            if shell and getattr(shell, "run_panel", None):
                run_panel = shell.run_panel
                # Use getattr with default to handle missing attribute gracefully
                progress_active = getattr(run_panel, "_progress_active", False)
                if progress_active:
                    current = getattr(run_panel, "_progress_current", 0)
                    total = getattr(run_panel, "_progress_total", 100)
                    message = getattr(run_panel, "_progress_message", "")
                    percent = (current / total * 100) if total > 0 else 0
                    progress_label.update(f"[yellow]Running: {percent:.0f}% - {message}[/]")
                    return

            progress_label.update("[dim]No active operations[/]")
        except Exception:
            pass

    def update_state(self) -> None:
        """Refresh all debug display elements."""
        self._update_intents()
        self._update_focus_path()
        self._update_progress()

    def refresh_selected_archive(self, archive_id: str) -> None:
        """Update selected archive display."""
        try:
            label = self.query_one("#debug-selected-archive", Label)
            label.update(archive_id or "None")
        except Exception:
            pass

    def refresh_filter(self, filter_text: str) -> None:
        """Update filter text display."""
        try:
            label = self.query_one("#debug-filter-text", Label)
            label.update(f"Text: {filter_text or '(none)'}")
        except Exception:
            pass


class CompactDebugOverlay(Container):
    """Compact debug overlay showing key info in a single line.

    Suitable for positioning in corner of screen.
    """

    DEFAULT_CLASSES = "bkx-debug-overlay-compact"

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)
        self._state = load_state()

    def compose(self) -> ComposeResult:
        yield Label(self._get_summary_text(), classes="debug-compact-text", id="debug-compact-summary")

    def _get_summary_text(self) -> str:
        """Get summary text for compact display."""
        parts = []

        # Route
        parts.append(f"R:{self._state.last_route}")

        # Archive
        if self._state.archive_selected_id:
            archive_short = (
                self._state.archive_selected_id[:16] + "..."
                if len(self._state.archive_selected_id) > 16
                else self._state.archive_selected_id
            )
            parts.append(f"A:{archive_short}")
        else:
            parts.append("A:none")

        # Filter
        if self._state.archive_filter_text:
            filter_short = (
                self._state.archive_filter_text[:10] + "..."
                if len(self._state.archive_filter_text) > 10
                else self._state.archive_filter_text
            )
            parts.append(f"F:{filter_short}")
        else:
            parts.append("F:none")

        return " | ".join(parts)

    def update_summary(self) -> None:
        """Update the compact summary."""
        try:
            label = self.query_one("#debug-compact-summary", Label)
            label.update(self._get_summary_text())
        except Exception:
            pass


# Helper function to create debug overlay widget
def create_debug_overlay(compact: bool = False) -> Container:
    """Create a debug overlay widget.

    Args:
        compact: If True, creates compact version

    Returns:
        Debug overlay container widget
    """
    if compact:
        return CompactDebugOverlay()
    return DebugOverlayPane()
