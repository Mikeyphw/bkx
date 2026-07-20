"""bkx/src/bkx/tui/pages/archive.py

Archive page:
- left: archive list (ArchiveListPane)
- right: create archive form (minimal) -> emits ArchiveIntent
"""

from __future__ import annotations

from pathlib import Path

from textual import on
from textual.app import ComposeResult
from textual.containers import Container, Horizontal, Vertical
from textual.widgets import Button, Input, Label, Select, Static

from bkx.tui.legacy import FilterMode
from bkx.tui.messages import ArchiveIntent
from bkx.tui.panes.archive_list import ArchiveListPane
from bkx.tui.widgets.file_picker import show_destination_picker, show_file_picker


class ArchivePage(Container):
    DEFAULT_CLASSES = "bkx-page bkx-archive-page"

    def __init__(self) -> None:
        super().__init__()
        self._sources: list[Path] = []

    def compose(self) -> ComposeResult:
        yield Static("📦 Archive", classes="page-title")

        with Horizontal(classes="archive-split"):
            yield ArchiveListPane(classes="archive-list")

            with Vertical(classes="archive-create"):
                yield Static("Create Archive", classes="section-title")
                yield Container(
                    Label("Sources:", classes="form-label"),
                    Input(placeholder="Paths (space-separated)", classes="form-input", id="sources-input"),
                    classes="form-row",
                )
                yield Container(
                    Label("Output:", classes="form-label"),
                    Input(placeholder="Output file (optional)", classes="form-input", id="output-input"),
                    classes="form-row",
                )
                yield Container(
                    Label("Excludes:", classes="form-label"),
                    Input(placeholder="*.pyc __pycache__", classes="form-input", id="excludes-input"),
                    classes="form-row",
                )
                yield Container(
                    Label("Algorithm:", classes="form-label"),
                    Select([("zstd", "zstd"), ("gzip", "gzip"), ("xz", "xz")], prompt="Algorithm", id="algorithm-select"),
                    classes="form-row",
                )
                yield Container(
                    Button("📁 Select Sources", id="btn-select-sources"),
                    Button("📂 Select Output Dir", id="btn-select-output"),
                    Button("🚀 Create Archive", id="btn-run"),
                    classes="button-row",
                )

    @on(Button.Pressed, "#btn-select-sources")
    def _pick_sources(self) -> None:
        def on_selected(paths: list | None) -> None:
            if paths:
                self._sources = [Path(p) for p in paths]
                self.query_one("#sources-input", Input).value = " ".join(str(p) for p in self._sources)

        show_file_picker(
            self.app,
            title="Select source files/folders",
            mode=FilterMode.FILES_ONLY,  # type: ignore[arg-type]  # FILES_AND_DIRS not supported
            multiple=True,
            on_select=on_selected,
            on_cancel=None,
        )

    @on(Button.Pressed, "#btn-select-output")
    def _pick_output_dir(self) -> None:
        def on_confirm(path: Path) -> None:
            self.query_one("#output-input", Input).value = str(path)

        show_destination_picker(
            self.app,
            title="Select output directory",
            on_confirm=on_confirm,
            on_cancel=None,
        )

    @on(Button.Pressed, "#btn-run")
    def _run(self) -> None:
        sources_text = self.query_one("#sources-input", Input).value.strip()
        sources = [Path(p) for p in sources_text.split()] if sources_text else self._sources

        out_text = self.query_one("#output-input", Input).value.strip()
        output = Path(out_text) if out_text else None

        excludes_text = self.query_one("#excludes-input", Input).value.strip()
        excludes = excludes_text.split() if excludes_text else []

        algorithm = self.query_one("#algorithm-select", Select).value or "zstd"

        self.post_message(
            ArchiveIntent(
                sources=sources,
                output=output,
                excludes=excludes,
                algorithm=str(algorithm),
                verify=True,
            )
        )
