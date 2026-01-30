"""Run log pane - Streaming log with pause/clear functionality."""

from __future__ import annotations

from rich.text import Text
from textual.app import ComposeResult
from textual.containers import Container
from textual.widgets import Button, RichLog

from bkx.tui.messages import UpdateLog


class RunLogPane(Container):
    """Run log pane with streaming, pause, and filter capabilities.
    
    Features:
    - Streaming log output
    - Pause/resume streaming
    - Clear log
    - Filter by level
    - Auto-scroll toggle
    """
    
    def __init__(
        self,
        title: str = "Run Logs",
        max_lines: int = 1000,
        auto_scroll: bool = True,
        **kwargs,
    ):
        super().__init__(**kwargs)
        self.title = title
        self.max_lines = max_lines
        self.auto_scroll = auto_scroll
        self._is_paused = False
        self._filter_level: str | None = None
    
    def compose(self) -> ComposeResult:
        yield RichLog(
            id="log-content",
            max_lines=self.max_lines,
            highlight=True,
            markup=True,
        )
    
    def on_mount(self) -> None:
        """Initialize log on mount."""
        self._write_system_message("BKX TUI started. Ready for operations.")
    
    def write(self, message: str, level: str = "info") -> None:
        """Write a message to the log.
        
        Args:
            message: Message to write
            level: Log level (info, success, warning, error, debug)
        """
        # Apply filter
        if self._filter_level and level != self._filter_level:
            return
        
        # Apply styling based on level
        styled_message = self._style_message(message, level)
        
        log = self.query_one("#log-content", RichLog)
        log.write(styled_message)
        
        # Auto-scroll if not paused
        if self.auto_scroll and not self._is_paused:
            log.scroll_end(animate=False)
    
    def _style_message(self, message: str, level: str) -> str:
        """Style a message based on log level."""
        styles = {
            "info": ("ℹ️", "white"),
            "success": ("✅", "green"),
            "warning": ("⚠️", "yellow"),
            "error": ("❌", "red"),
            "debug": ("🔍", "dim"),
        }
        icon, color = styles.get(level, ("ℹ️", "white"))
        return f"[{color}]{icon} {message}[/{color}]"
    
    def _write_system_message(self, message: str) -> None:
        """Write a system message."""
        styled = f"[dim]-- {message} --[/]"
        log = self.query_one("#log-content", RichLog)
        log.write(styled)
    
    def toggle_pause(self) -> None:
        """Toggle log streaming pause."""
        self._is_paused = not self._is_paused
        status = "paused" if self._is_paused else "resumed"
        self._write_system_message(f"Log {status}")
    
    def clear(self) -> None:
        """Clear the log."""
        log = self.query_one("#log-content", RichLog)
        log.clear()
        self._write_system_message("Log cleared")
    
    def set_filter(self, level: str | None) -> None:
        """Set log level filter.
        
        Args:
            level: Level to filter by, or None for all
        """
        self._filter_level = level
        self._write_system_message(f"Filter set to: {level or 'all'}")
    
    def toggle_auto_scroll(self) -> None:
        """Toggle auto-scroll."""
        self.auto_scroll = not self.auto_scroll
        status = "enabled" if self.auto_scroll else "disabled"
        self._write_system_message(f"Auto-scroll {status}")
    
    def on_message(self, event: UpdateLog) -> None:
        """Handle UpdateLog messages from other components."""
        self.write(event.message, event.level)

