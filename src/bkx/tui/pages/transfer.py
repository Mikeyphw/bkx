"""bkx/src/bkx/tui/pages/transfer.py

Transfer page -> emits TransferIntent.

Uses mikomn FormPane for consistent input UX.
"""

from __future__ import annotations

from pathlib import Path

from textual import on
from textual.app import ComposeResult
from textual.containers import Container
from textual.widgets import Button, Input, Select, Static

from bkx.tui.legacy import FilterMode, FormPane
from bkx.tui.messages import TransferIntent
from bkx.tui.widgets.file_picker import show_destination_picker, show_file_picker


class TransferPage(Container):
    DEFAULT_CLASSES = "bkx-page bkx-transfer-page"

    def __init__(self) -> None:
        super().__init__()
        self._form = FormPane(id="transfer-form")
        self._form.add_field("source", "Source", placeholder="Source path")
        self._form.add_field("destination", "Destination", placeholder="Destination path")

        self._direction_select = Select(
            [("push", "push"), ("pull", "pull")],
            prompt="Direction",
            id="direction-select",
            classes="form-field",
        )
        self._form.add_block(
            "direction",
            "Direction",
            self._direction_select,
            get_value=lambda: self._direction_select.value or "push",
        )

    def compose(self) -> ComposeResult:
        yield Static("🔄 Transfer", classes="page-title")
        yield self._form
        yield Container(
            Button("📁 Pick Source", id="btn-pick-source"),
            Button("📂 Pick Destination", id="btn-pick-dest"),
            Button("🚀 Transfer", id="btn-run"),
            classes="button-row",
        )

    @on(Button.Pressed, "#btn-pick-source")
    def _pick_source(self) -> None:
        def on_selected(paths: list | None) -> None:
            if paths:
                self.query_one("#field-source", Input).value = str(paths[0])

        show_file_picker(
            self.app,
            title="Select source",
            mode=FilterMode.FILES_ONLY,  # type: ignore[arg-type]
            multiple=False,
            on_select=on_selected,
            on_cancel=None,
        )

    @on(Button.Pressed, "#btn-pick-dest")
    def _pick_dest(self) -> None:
        def on_confirm(path: Path) -> None:
            self.query_one("#field-destination", Input).value = str(path)

        show_destination_picker(
            self.app,
            title="Select destination folder",
            on_confirm=on_confirm,
            on_cancel=None,
        )

    @on(Button.Pressed, "#btn-run")
    def _run(self) -> None:
        data = self._form.get_form_data()
        source_text = str(data.get("source") or "").strip()
        dest_text = str(data.get("destination") or "").strip()
        if not source_text or not dest_text:
            self.app.notify("Source and Destination are required.", severity="warning")
            return
        direction = str(data.get("direction") or "push")
        self.post_message(
            TransferIntent(
                source=Path(source_text),
                destination=Path(dest_text),
                direction=direction,
                verify=True,
            )
        )
