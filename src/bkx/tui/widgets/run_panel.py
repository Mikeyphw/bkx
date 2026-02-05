"""bkx/src/bkx/tui/widgets/run_panel.py

Persistent Run Panel (always visible).

Pure mikomn panes:
- StatusPane (StatusBar)
- ProgressPane (GlobalProgressBar)
- LogPane (streaming log buffer)

Contract:
- BKX app posts messages; panel exposes small helpers for app handlers.
"""

from __future__ import annotations

from collections.abc import Iterable

from mikomn.tui_base.panes.display.log_pane import LogPane
from mikomn.tui_base.panes.display.progress_pane import ProgressPane
from mikomn.tui_base.panes.display.status_pane import StatusPane
from mikomn.tui_base.widgets.progress import GlobalProgressBar
from mikomn.tui_base.widgets.status import StatusMessage
from textual.app import ComposeResult
from textual.containers import Vertical


class RunPanel(Vertical):
    """Always-visible run panel hosting status/progress/logs."""

    DEFAULT_CLASSES = "bkx-run-panel"

    def __init__(self, *, title: str = "Run", **kwargs) -> None:
        super().__init__(**kwargs)
        self._title = title
        self._status: StatusPane | None = None
        self._progress: ProgressPane | None = None
        self._log: LogPane | None = None
        self._progress_total: int = 100

    def compose(self) -> ComposeResult:
        yield StatusPane(title=self._title, classes="bkx-run-status")
        yield ProgressPane(classes="bkx-run-progress")
        yield LogPane(title="Log", classes="bkx-run-log")

    def on_mount(self) -> None:
        self._status = self.query_one(StatusPane)
        self._progress = self.query_one(ProgressPane)
        self._log = self.query_one(LogPane)

    def _get_progress_bar(self) -> GlobalProgressBar | None:
        """Get the progress bar from the progress pane."""
        if not self._progress:
            return None
        try:
            return self._progress.progress_bar
        except Exception:
            return None

    def start_progress(self, *, total: int = 100, message: str | None = None) -> None:
        """Start or reset the progress bar.

        Args:
            total: Total value for the progress bar (default 100)
            message: Optional status message to display
        """
        self._progress_total = int(total) if int(total) > 0 else 100
        bar = self._get_progress_bar()
        if bar:
            bar.progress = 0.0
            bar.eta_seconds = 0
            bar.saved_mb = 0.0
        if message:
            self.set_status(message, level="info")

    def update_progress(
        self,
        *,
        current: int | None = None,
        total: int | None = None,
        percent: float | None = None,
        message: str | None = None,
        eta_seconds: int | None = None,
        saved_mb: float | None = None,
    ) -> None:
        """Update the progress bar.

        Args:
            current: Current progress value
            total: Total progress value (overrides previous total)
            percent: Percentage (0-1 or 0-100). If omitted, derived from current/total.
            message: Optional status message
            eta_seconds: Estimated time remaining in seconds
            saved_mb: Amount saved in MB
        """
        if total is not None and int(total) > 0:
            self._progress_total = int(total)

        if percent is not None:
            pct = float(percent)
            if pct <= 1.0:
                pct *= 100.0
        elif current is not None and self._progress_total > 0:
            pct = (float(current) / float(self._progress_total)) * 100.0
        else:
            pct = 0.0

        pct = max(0.0, min(100.0, pct))

        bar = self._get_progress_bar()
        if bar:
            bar.progress = pct
            if eta_seconds is not None:
                bar.eta_seconds = int(eta_seconds)
            if saved_mb is not None:
                bar.saved_mb = float(saved_mb)

        if message:
            self.set_status(message, level="info")

    def complete_progress(self, *, message: str | None = None, success: bool = True) -> None:
        """Mark progress as complete.

        Args:
            message: Optional completion message
            success: Whether the operation was successful (affects status level)
        """
        bar = self._get_progress_bar()
        if bar:
            bar.progress = 100.0
            bar.eta_seconds = 0
        if message:
            level = "success" if success else "error"
            self.set_status(message, level=level)

    def set_status(self, text: str, *, level: str = "info", timeout: float | None = None) -> None:
        """Set status message.

        Args:
            text: Status text to display
            level: Message level (info, success, warning, error)
            timeout: Optional timeout in seconds
        """
        if not self._status:
            return
        try:
            self._status.status_bar.post_message(StatusMessage(text, level=level, timeout=timeout))
        except Exception:
            return

    def write(self, text: str) -> None:
        """Write text to the log.

        Args:
            text: Text to write
        """
        if not self._log:
            return
        try:
            self._log.write(text)
        except Exception:
            return

    def write_lines(self, lines: Iterable[str]) -> None:
        """Write multiple lines to the log.

        Args:
            lines: Iterable of lines to write
        """
        if not self._log:
            return
        try:
            self._log.write_lines(lines)
        except Exception:
            return

    def clear_log(self) -> None:
        """Clear the log."""
        if not self._log:
            return
        try:
            self._log.clear()
        except Exception:
            return

    @property
    def progress(self) -> ProgressPane | None:
        """Get the progress pane."""
        return self._progress

    @property
    def status(self) -> StatusPane | None:
        """Get the status pane."""
        return self._status

    @property
    def log(self) -> LogPane | None:
        """Get the log pane."""
        return self._log

