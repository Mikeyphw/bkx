"""File picker integration utilities for bkx TUI."""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path

from textual.app import ComposeResult
from textual.containers import Container
from textual.screen import ModalScreen
from textual.widgets import Button, Input, Label, Static

from bkx.tui.legacy import FileDirPicker, FilterMode


class DestinationPicker(ModalScreen):
    """Destination path picker with save dialog."""

    def __init__(
        self,
        *,
        start_path: Path | None = None,
        suggest_name: str | None = None,
        on_confirm: Callable[[Path], None] | None = None,
        on_cancel: Callable[[], None] | None = None,
    ) -> None:
        super().__init__()
        self._start_path = start_path or Path.cwd()
        self._suggest_name = suggest_name
        self._on_confirm = on_confirm
        self._on_cancel = on_cancel

    def compose(self) -> ComposeResult:
        yield Container(
            Static("Select Destination", classes="picker-header"),
            Container(
                Label("Path:", classes="form-label"),
                Input(
                    placeholder=str(self._start_path),
                    id="destination-path",
                    classes="form-input",
                ),
                classes="form-row",
            ),
            Container(
                Button("📁 Browse...", id="btn-browse"),
                Button("OK", id="btn-ok"),
                Button("Cancel", id="btn-cancel"),
                classes="button-row",
            ),
            classes="destination-picker",
        )

    def on_mount(self) -> None:
        self.query_one("#destination-path", Input).focus()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "btn-browse":
            self._browse_destination()
        elif event.button.id == "btn-ok":
            self._confirm_destination()
        elif event.button.id == "btn-cancel":
            self._cancel()

    def on_input_submitted(self, event: Input.Submitted) -> None:
        if event.input.id == "destination-path":
            self._confirm_destination()

    def _browse_destination(self) -> None:
        self.app.push_screen(
            FileDirPicker(
                start_path=self._start_path,
                filter_mode=FilterMode.DIRS_ONLY,
            ),
            self._on_destination_selected,
        )

    def _on_destination_selected(self, paths: list[Path] | None) -> None:
        if paths is None:
            return

        if not paths:
            self.app.notify(
                "No destination selected. Select a folder and confirm (Space → Confirm).",
                severity="warning",
            )
            return

        path = paths[0]
        self.query_one("#destination-path", Input).value = str(path)
        self._start_path = path

    def _confirm_destination(self) -> None:
        input_widget = self.query_one("#destination-path", Input)
        path = Path(input_widget.value.strip())
        if path.exists() or path.parent.exists():
            if self._on_confirm:
                self._on_confirm(path)
            self.dismiss(path)
        else:
            input_widget.focus()

    def _cancel(self) -> None:
        if self._on_cancel:
            self._on_cancel()
        self.dismiss(None)


def show_file_picker(
    app,
    *,
    title: str = "Select files",
    mode: FilterMode = FilterMode.FILES_ONLY,  # type: ignore[assignment]
    multiple: bool = True,
    start_path: Path | None = None,
    on_select: Callable[[list[Path]], None] | None = None,
    on_cancel: Callable[[], None] | None = None,
) -> None:
    """Show file/dir picker and handle selection.

    Args:
        app: The Textual app instance
        title: Dialog title (not used by mikomn FileDirPicker, for documentation only)
        mode: Filter mode (NONE, FILES_ONLY, DIRS_ONLY) - note: FILES_AND_DIRS not supported
        multiple: Allow selecting multiple items (handled by picker mode internally)
        start_path: Starting directory path
        on_select: Callback when files are selected, receives list[Path]
        on_cancel: Callback when picker is cancelled
    """
    picker = FileDirPicker(
        start_path=start_path or Path.cwd(),
        filter_mode=mode,
    )

    def handle_result(paths: list[Path] | None) -> None:
        if paths:
            if on_select:
                on_select(paths)
        else:
            if on_cancel:
                on_cancel()

    app.push_screen(picker, handle_result)


def show_destination_picker(
    app,
    *,
    title: str = "Select destination",  # Note: not used by mikomn, kept for API compatibility
    start_path: Path | None = None,
    suggest_name: str | None = None,
    on_confirm: Callable[[Path], None] | None = None,
    on_cancel: Callable[[], None] | None = None,
) -> None:
    """Show destination path picker dialog.

    Args:
        app: The Textual app instance
        title: Dialog title (kept for API compatibility, not used by mikomn)
        start_path: Starting directory path
        suggest_name: Suggested filename
        on_confirm: Callback when destination is confirmed, receives Path
        on_cancel: Callback when picker is cancelled
    """
    picker = DestinationPicker(
        start_path=start_path or Path.cwd(),
        suggest_name=suggest_name,
        on_confirm=on_confirm,
        on_cancel=on_cancel,
    )
    app.push_screen(picker)
