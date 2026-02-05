"""Run log pane - Streaming log with pause/clear functionality.

This pane is used both in the always-visible Run Panel and on the expanded Logs page.
"""

from __future__ import annotations

from textual import on
from textual.app import ComposeResult
from textual.containers import Container, Horizontal
from textual.widgets import Button, Label, RichLog

from bkx.tui.messages import UpdateLog


class RunLogPane(Container):
    """Streaming log view with basic controls."""

    DEFAULT_CLASSES = "bkx-run-log-pane"

    _FILTER_LEVELS: list[str | None] = [None, "info", "success", "warning", "error", "debug"]

    def __init__(
        self,
        title: str = "Run Logs",
        max_lines: int = 1000,
        auto_scroll: bool = True,
        **kwargs,
    ) -> None:
        super().__init__(**kwargs)
        self.title = title
        self.max_lines = max_lines
        self.auto_scroll = auto_scroll
        self._is_paused = False
        self._filter_level: str | None = None

    def compose(self) -> ComposeResult:
        yield Container(Label(self.title, classes="log-title"), classes="log-header")
        yield Horizontal(
            Button("Pause", id="pause-btn"),
            Button("Clear", id="clear-btn"),
            Button("Auto: On", id="autoscroll-btn"),
            Button("Filter: All", id="filter-btn"),
            classes="log-controls",
        )
        yield RichLog(
            id="log-content",
            max_lines=self.max_lines,
            highlight=True,
            markup=True,
        )

    def on_mount(self) -> None:
        self._sync_controls()
        self._write_system_message("BKX TUI started. Ready for operations.")

    def clear_log(self) -> None:
        log = self.query_one("#log-content", RichLog)
        log.clear()
        self._write_system_message("Log cleared")
        self._sync_controls()

    def write(self, message: str, level: str = "info") -> None:
        if self._is_paused:
            return
        if self._filter_level and level != self._filter_level:
            return

        log = self.query_one("#log-content", RichLog)
        log.write(self._style_message(message, level))

        if self.auto_scroll:
            log.scroll_end(animate=False)

    def _sync_controls(self) -> None:
        try:
            self.query_one("#pause-btn", Button).label = "Resume" if self._is_paused else "Pause"
            self.query_one("#autoscroll-btn", Button).label = "Auto: On" if self.auto_scroll else "Auto: Off"
            self.query_one("#filter-btn", Button).label = f"Filter: {self._filter_level or 'All'}"
        except Exception:
            return

    def _style_message(self, message: str, level: str) -> str:
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
        log = self.query_one("#log-content", RichLog)
        log.write(f"[dim]-- {message} --[/]")

    @on(Button.Pressed, "#pause-btn")
    def _on_pause(self) -> None:
        self._is_paused = not self._is_paused
        self._write_system_message("Log paused" if self._is_paused else "Log resumed")
        self._sync_controls()

    @on(Button.Pressed, "#clear-btn")
    def _on_clear(self) -> None:
        self.clear_log()

    @on(Button.Pressed, "#autoscroll-btn")
    def _on_autoscroll(self) -> None:
        self.auto_scroll = not self.auto_scroll
        self._write_system_message("Auto-scroll enabled" if self.auto_scroll else "Auto-scroll disabled")
        self._sync_controls()

    @on(Button.Pressed, "#filter-btn")
    def _on_filter(self) -> None:
        cur = self._FILTER_LEVELS.index(self._filter_level) if self._filter_level in self._FILTER_LEVELS else 0
        self._filter_level = self._FILTER_LEVELS[(cur + 1) % len(self._FILTER_LEVELS)]
        self._write_system_message(f"Filter set to: {self._filter_level or 'all'}")
        self._sync_controls()

    @on(UpdateLog)
    def _on_update_log(self, event: UpdateLog) -> None:
        self.write(event.message, event.level)
