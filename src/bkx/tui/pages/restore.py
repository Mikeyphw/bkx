"""bkx/src/bkx/tui/pages/restore.py

Restore page -> emits RestoreIntent.

Uses mikomn FormPane for consistent input UX.
"""

from __future__ import annotations

from pathlib import Path

from mikomn.tui_base.panes.input.form_pane import FormPane
from mikomn.tui_base.widgets.file_picker import FilterMode
from textual import on
from textual.app import ComposeResult
from textual.containers import Container
from textual.widgets import Button, Checkbox, Input, Static

from bkx.tui.messages import RestoreIntent
from bkx.tui.widgets.file_picker import show_destination_picker, show_file_picker


class RestorePage(Container):
    DEFAULT_CLASSES = "bkx-page bkx-restore-page"

    def __init__(self) -> None:
        super().__init__()
        self._form = FormPane(id="restore-form")
        self._form.add_field("archive", "Archive", placeholder="Archive path")
        self._form.add_field("destination", "Destination", placeholder="Destination folder")
        self._form.add_field("paths", "Paths", placeholder="Optional sub-paths (space-separated)")

        self._fresh = Checkbox("Fresh dir", id="fresh-checkbox", value=False)
        self._overwrite = Checkbox("Overwrite", id="overwrite-checkbox", value=False)
        self._verify = Checkbox("Verify", id="verify-checkbox", value=True)

        self._form.add_block("fresh_dir", "Fresh dir", self._fresh, get_value=lambda: bool(self._fresh.value))
        self._form.add_block("overwrite", "Overwrite", self._overwrite, get_value=lambda: bool(self._overwrite.value))
        self._form.add_block("verify", "Verify", self._verify, get_value=lambda: bool(self._verify.value))

    def compose(self) -> ComposeResult:
        yield Static("📥 Restore", classes="page-title")
        yield self._form
        yield Container(
            Button("📁 Pick Archive", id="btn-pick-archive"),
            Button("📂 Pick Destination", id="btn-pick-dest"),
            Button("🚀 Restore", id="btn-run"),
            classes="button-row",
        )

    @on(Button.Pressed, "#btn-pick-archive")
    async def _pick_archive(self) -> None:
        selected = await show_file_picker(title="Select archive", mode=FilterMode.FILES_ONLY, multiple=False)
        if not selected:
            return
        self.query_one("#field-archive", Input).value = str(selected[0])

    @on(Button.Pressed, "#btn-pick-dest")
    async def _pick_dest(self) -> None:
        selected = await show_destination_picker(title="Select destination folder")
        if not selected:
            return
        self.query_one("#field-destination", Input).value = str(selected)

    @on(Button.Pressed, "#btn-run")
    def _run(self) -> None:
        data = self._form.get_form_data()
        archive_text = str(data.get("archive") or "").strip()
        if not archive_text:
            self.app.notify("Archive is required.", severity="warning")
            return
        dest_text = str(data.get("destination") or "").strip()
        if not dest_text:
            self.app.notify("Destination is required.", severity="warning")
            return

        archive = Path(archive_text)
        destination = Path(dest_text)

        paths_text = str(data.get("paths") or "").strip()
        paths = paths_text.split() if paths_text else []

        self.post_message(
            RestoreIntent(
                archive=archive,
                destination=destination,
                paths=paths,
                fresh_dir=bool(data.get("fresh_dir")),
                overwrite=bool(data.get("overwrite")),
                verify=bool(data.get("verify")),
            )
        )
