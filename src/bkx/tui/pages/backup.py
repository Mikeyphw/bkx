"""bkx/src/bkx/tui/pages/backup.py

Backup page -> emits BackupIntent.

Notes:
- Sources are picker-only (list block), not editable free-text.
- Computes total size in a background thread; updates header progressively.
- For huge trees, scanning truncates and shows "~approx".
"""

from __future__ import annotations

import queue
import threading
from pathlib import Path

from textual import on
from textual.app import ComposeResult
from textual.containers import Container, Vertical
from textual.widgets import Button, Checkbox, Input, Select, Static

from bkx.core.config import get_config
from bkx.tui.legacy import FilterMode, FormPane
from bkx.tui.messages import BackupIntent
from bkx.tui.widgets.file_picker import show_destination_picker, show_file_picker


def _format_bytes(num: int) -> str:
    units = ["B", "KB", "MB", "GB", "TB", "PB"]
    n = float(num)
    for u in units:
        if n < 1024.0 or u == units[-1]:
            return f"{n:.1f} {u}" if u != "B" else f"{int(n)} {u}"
        n /= 1024.0
    return f"{n:.1f} PB"


class BackupPage(Container):
    DEFAULT_CLASSES = "bkx-page bkx-backup-page"

    _MAX_FILES = 50_000
    _PROGRESS_EVERY = 2_000

    def __init__(self) -> None:
        super().__init__()
        self._sources: list[Path] = []

        self._size_queue: queue.Queue[tuple[int, int, bool]] = queue.Queue()
        self._size_thread: threading.Thread | None = None
        self._scan_token = 0

        self._form = FormPane(id="backup-form")

        self._sources_header = Static("0 sources selected", classes="sources-header")
        self._sources_view = Static("No sources selected.", classes="sources-list")
        self._form.add_block(
            "sources_list",
            "Sources",
            Vertical(self._sources_header, self._sources_view, classes="sources-block"),
            get_value=lambda: [str(p) for p in self._sources],
        )

        self._form.add_field("destination", "Destination", placeholder="Output path (auto-generated if empty)")
        self._form.add_field("name", "Name", placeholder="Archive name (auto-generated if empty)")
        self._form.add_field("excludes", "Excludes", placeholder="*.pyc __pycache__ .git")

        self._algorithm_select = Select(
            [("zstd", "zstd"), ("gzip", "gzip"), ("lz4", "lz4"), ("xz", "xz"), ("bzip2", "bzip2")],
            prompt="Compression algorithm",
            id="algorithm-select",
            classes="form-field",
        )
        self._form.add_block(
            "algorithm",
            "Algorithm",
            self._algorithm_select,
            get_value=lambda: self._algorithm_select.value or "zstd",
        )
        self._form.add_field("level", "Level", placeholder="Compression level")

        self._verify_checkbox = Checkbox("Verify archive after creation", id="verify-checkbox", value=True)
        self._form.add_block(
            "verify",
            "Options",
            self._verify_checkbox,
            get_value=lambda: bool(self._verify_checkbox.value),
        )

    def compose(self) -> ComposeResult:
        yield Static("💾 Backup", classes="page-title")
        yield self._form
        yield Container(
            Button("📁 Select Sources", id="btn-select-sources"),
            Button("📂 Select Destination", id="btn-select-destination"),
            Button("🚀 Run Backup", id="btn-run"),
            classes="button-row",
        )

    def on_mount(self) -> None:
        cfg = get_config()
        level = str(getattr(cfg, "compression_level", "") or "")
        if level:
            try:
                self.query_one("#field-level", Input).value = level
            except Exception:
                pass

        # Poll background size worker without blocking UI
        self.set_interval(0.2, self._drain_size_updates)

    @on(Button.Pressed, "#btn-select-sources")
    def _pick_sources(self) -> None:
        def on_selected(paths: list | None) -> None:
            if paths:
                self._sources = [Path(p) for p in paths]
                self._refresh_sources_view(start_scan=True)

        show_file_picker(
            self.app,
            title="Select sources",
            mode=FilterMode.FILES_ONLY,  # type: ignore[arg-type]  # FILES_AND_DIRS not supported
            multiple=True,
            on_select=on_selected,
            on_cancel=None,
        )

    @on(Button.Pressed, "#btn-select-destination")
    def _pick_dest(self) -> None:
        def on_confirm(path: Path) -> None:
            self.query_one("#field-destination", Input).value = str(path)

        show_destination_picker(
            self.app,
            title="Select destination folder",
            on_confirm=on_confirm,
            on_cancel=None,
        )

    def _refresh_sources_view(self, *, start_scan: bool) -> None:
        if not self._sources:
            self._sources_header.update("0 sources selected")
            self._sources_view.update("No sources selected.")
            return

        self._sources_view.update("\n".join(str(p) for p in self._sources))
        self._sources_header.update(f"{len(self._sources)} sources selected — calculating size…")

        if start_scan:
            self._start_size_worker()

    def _start_size_worker(self) -> None:
        self._scan_token += 1
        token = self._scan_token

        def _worker() -> None:
            total = 0
            files = 0
            truncated = False
            try:
                for src in self._sources:
                    for p in src.rglob("*"):
                        if token != self._scan_token:
                            return
                        if p.is_file():
                            try:
                                total += p.stat().st_size
                            except Exception:
                                truncated = True
                            files += 1
                            if files % self._PROGRESS_EVERY == 0:
                                self._size_queue.put((total, files, False))
                            if files >= self._MAX_FILES:
                                truncated = True
                                break
                    if truncated:
                        break
            except Exception:
                truncated = True
            self._size_queue.put((total, files, truncated))

        self._size_thread = threading.Thread(target=_worker, daemon=True)
        self._size_thread.start()

    def _drain_size_updates(self) -> None:
        if not self._sources:
            return
        latest: tuple[int, int, bool] | None = None
        try:
            while True:
                latest = self._size_queue.get_nowait()
        except queue.Empty:
            pass

        if latest is None:
            return

        total, files, truncated = latest
        approx = " ~approx" if truncated else ""
        self._sources_header.update(f"{len(self._sources)} sources selected — {_format_bytes(total)} ({files:,} files){approx}")

    @on(Button.Pressed, "#btn-run")
    def _run(self) -> None:
        if not self._sources:
            self.app.notify("Select at least one source.", severity="warning")
            return

        data = self._form.get_form_data()

        dest_text = str(data.get("destination") or "").strip()
        if not dest_text:
            self.app.notify("Destination is required.", severity="warning")
            return
        destination = Path(dest_text)

        archive_name = str(data.get("name") or "").strip() or None

        excludes_text = str(data.get("excludes") or "").strip()
        excludes = excludes_text.split() if excludes_text else []

        algorithm = str(data.get("algorithm") or "zstd")
        level_text = str(data.get("level") or "").strip()
        try:
            level = int(level_text) if level_text else 3
        except ValueError:
            level = 3

        verify = bool(data.get("verify"))

        self.post_message(
            BackupIntent(
                sources=self._sources,
                destination=destination,
                archive_name=archive_name,
                excludes=excludes,
                algorithm=algorithm,
                level=level,
                verify=verify,
            )
        )
