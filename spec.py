# spec.py
"""
Step 2 (+ A + B): Use mikomn panes/forms + expand logging

Base: built against the user's UPDATED bkx.zip.

Already correct (Step 1 overlays + numeric nav):
- BINDINGS include 1..6, ctrl+p, ctrl+n
- CommandPalette + NotificationCenterScreen wired
- NotificationHistory + ToastManager wiring present

This spec adds:
A) Upgrade Backup/Restore/Transfer pages to mikomn FormPane
B) Expanded Logs page mirrors UpdateLog + toasts + status notifications
Step 2) Upgrade Tools page + Logs page; upgrade RunLogPane controls; add FormPane CSS support
"""

FILES_TO_WRITE: dict[str, str] = {
    "verify_apply.py": """\
\"\"\"Structural checks for Step 2 (+ A + B).

Focus:
- Overlays remain present (Step 1 previously implemented)
- Step 2 pages are upgraded (ToolsDashboardPane / RunLogPane)
- Backup/Restore/Transfer use mikomn FormPane
- App mirrors UpdateLog + notifications + toasts into expanded Logs pane
\"\"\"

from __future__ import annotations

import inspect

from bkx.tui.app import BkxTuiApp
from bkx.tui.pages.backup import BackupPage
from bkx.tui.pages.logs import LogsPage
from bkx.tui.pages.restore import RestorePage
from bkx.tui.pages.tools import ToolsPage
from bkx.tui.pages.transfer import TransferPage


def _binding_map() -> dict[str, str]:
    mapping: dict[str, str] = {}
    for b in BkxTuiApp.BINDINGS:
        if isinstance(b, tuple) and len(b) >= 2:
            mapping[str(b[0])] = str(b[1])
    return mapping


def test_step1_bindings_still_present() -> None:
    m = _binding_map()
    assert m.get(\"ctrl+p\") == \"command_palette\"
    assert m.get(\"ctrl+n\") == \"notification_center\"
    for k, act in [(\"1\", \"navigate_backup\"), (\"2\", \"navigate_archive\"), (\"3\", \"navigate_restore\"), (\"4\", \"navigate_transfer\"), (\"5\", \"navigate_tools\"), (\"6\", \"navigate_logs\")]:
        assert m.get(k) == act


def test_tools_and_logs_pages_are_upgraded() -> None:
    tools_src = inspect.getsource(ToolsPage.compose)
    assert \"ToolsDashboardPane\" in tools_src

    logs_src = inspect.getsource(LogsPage.compose)
    assert \"RunLogPane\" in logs_src
    assert \"bkx-logs-log\" in logs_src


def test_forms_use_formpane() -> None:
    for cls in (BackupPage, RestorePage, TransferPage):
        src = inspect.getsource(cls.__init__)
        assert \"FormPane\" in src


def test_app_mirrors_to_logs_pane() -> None:
    required = [\"_get_logs_pane\", \"_write_to_logs\"]
    for name in required:
        assert hasattr(BkxTuiApp, name), f\"missing {name}\"
""",
}

PATCHES: list[str] = [
    # Step 2: Tools page -> ToolsDashboardPane + settings/status reflection
    r"""--- a/src/bkx/tui/pages/tools.py
+++ b/src/bkx/tui/pages/tools.py
@@ -1,17 +1,52 @@
 """bkx/src/bkx/tui/pages/tools.py
 
-Tools page (placeholder) - will be migrated to mikomn-native panes next.
+Tools page (dashboard).
+
+Uses BKX panes built on mikomn widgets:
+- Status cards overview
+- Theme/settings surface (read-only reflection of current config for now)
 """
 
+from __future__ import annotations
+
 from textual.app import ComposeResult
 from textual.containers import Container
 from textual.widgets import Static
 
+from bkx.core.config import get_config
+from bkx.tui.panes.status_cards import ToolsDashboardPane
+from bkx.ui.theme import get_current_theme_name
+
 
 class ToolsPage(Container):
+    \"\"\"Tools and settings page.\"\"\"
+
     DEFAULT_CLASSES = "bkx-page bkx-tools-page"
 
     def compose(self) -> ComposeResult:
         yield Static("🔧 Tools", classes="page-title")
-        yield Static("Tools UI will be migrated to mikomn-native panes next.", classes="muted")
+        yield ToolsDashboardPane(id="bkx-tools-dashboard")
+
+    def on_mount(self) -> None:
+        cfg = get_config()
+        theme = get_current_theme_name()
+
+        try:
+            dash = self.query_one("#bkx-tools-dashboard", ToolsDashboardPane)
+        except Exception:
+            return
+
+        dash.update_setting("theme", theme)
+        dash.update_setting("compression", cfg.compression_algorithm)
+        dash.update_setting("verify", "Enabled" if cfg.verify_after_operation else "Disabled")
+        dash.update_setting("level", str(cfg.compression_level))
+
+        dash.update_status_card("theme", theme, "Current color scheme", "info")
+        dash.update_status_card("compression", cfg.compression_algorithm, "Default algorithm", "success")
+        dash.update_status_card(
+            "verification",
+            "Enabled" if cfg.verify_after_operation else "Disabled",
+            "Verify after ops",
+            "info",
+        )
+        dash.update_status_card("level", str(cfg.compression_level), "Compression level", "info")
""",
    # Step 2: Logs page -> expanded RunLogPane target
    r"""--- a/src/bkx/tui/pages/logs.py
+++ b/src/bkx/tui/pages/logs.py
@@ -1,17 +1,25 @@
 """bkx/src/bkx/tui/pages/logs.py
 
-Logs page (placeholder). The persistent Run Panel contains the live log.
+Logs page.
+
+The persistent Run Panel contains the live log. This page provides an expanded
+log view (mirrors UpdateLog, toasts, and status notifications).
 """
 
+from __future__ import annotations
+
 from textual.app import ComposeResult
 from textual.containers import Container
 from textual.widgets import Static
 
+from bkx.tui.panes.log import RunLogPane
+
 
 class LogsPage(Container):
+    \"\"\"Logs page with an expanded log view.\"\"\"
+
     DEFAULT_CLASSES = "bkx-page bkx-logs-page"
 
     def compose(self) -> ComposeResult:
         yield Static("📋 Logs", classes="page-title")
-        yield Static("Live output appears in the Run Panel.", classes="muted")
+        yield RunLogPane(title="Logs", id="bkx-logs-log")
""",
    # Step 2: ToolsDashboardPane tweaks (default cards + update_setting)
    r"""--- a/src/bkx/tui/panes/status_cards.py
+++ b/src/bkx/tui/panes/status_cards.py
@@ -281,9 +281,19 @@
 class ToolsDashboardPane(Container):
     """Dashboard-style pane combining status cards and settings."""
 
     DEFAULT_CLASSES = "bkx-tools-dashboard-pane"
 
-    def __init__(self, **kwargs):
+    def __init__(self, config: StatusCardConfig | None = None, **kwargs):
         super().__init__(**kwargs)
-        self._status_cards_config = StatusCardConfig()
+        self._status_cards_config = config or StatusCardConfig(cards=create_default_status_cards())
@@
-    def add_status_card(self, card: StatusCard) -> None:
+    def update_setting(self, key: str, value: str) -> None:
+        """Update a setting in the embedded ThemeSettingsPane."""
+        try:
+            pane = self.query_one(ThemeSettingsPane)
+            pane.update_setting(key, value)
+        except Exception:
+            return
+
+    def add_status_card(self, card: StatusCard) -> None:
         """Add a status card."""
         self._status_cards_config.cards.append(card)
""",
    # Step 2: Upgrade RunLogPane with controls + clearer API
    r"""--- a/src/bkx/tui/panes/log.py
+++ b/src/bkx/tui/panes/log.py
@@ -1,131 +1,169 @@
-"""Run log pane - Streaming log with pause/clear functionality."""
+"""Run log pane - Streaming log with pause/clear functionality.
+
+This pane is used both in the always-visible Run Panel and on the expanded Logs page.
+"""
 
 from __future__ import annotations
 
+from textual import on
 from textual.app import ComposeResult
-from textual.containers import Container
-from textual.widgets import RichLog
+from textual.containers import Container, Horizontal
+from textual.widgets import Button, Label, RichLog
 
 from bkx.tui.messages import UpdateLog
 
 
 class RunLogPane(Container):
-    """Run log pane with streaming, pause, and filter capabilities.
-
-    Features:
-    - Streaming log output
-    - Pause/resume streaming
-    - Clear log
-    - Filter by level
-    - Auto-scroll toggle
-    """
+    """Streaming log view with basic controls."""
 
     DEFAULT_CLASSES = "bkx-run-log-pane"
 
+    _FILTER_LEVELS: list[str | None] = [None, "info", "success", "warning", "error", "debug"]
+
     def __init__(
         self,
         title: str = "Run Logs",
         max_lines: int = 1000,
         auto_scroll: bool = True,
         **kwargs,
-    ):
+    ) -> None:
         super().__init__(**kwargs)
         self.title = title
         self.max_lines = max_lines
         self.auto_scroll = auto_scroll
         self._is_paused = False
         self._filter_level: str | None = None
 
     def compose(self) -> ComposeResult:
+        yield Container(Label(self.title, classes="log-title"), classes="log-header")
+        yield Horizontal(
+            Button("Pause", id="pause-btn"),
+            Button("Clear", id="clear-btn"),
+            Button("Auto: On", id="autoscroll-btn"),
+            Button("Filter: All", id="filter-btn"),
+            classes="log-controls",
+        )
         yield RichLog(
             id="log-content",
             max_lines=self.max_lines,
             highlight=True,
             markup=True,
         )
 
     def on_mount(self) -> None:
-        """Initialize log on mount."""
+        self._sync_controls()
         self._write_system_message("BKX TUI started. Ready for operations.")
 
+    def clear_log(self) -> None:
+        log = self.query_one("#log-content", RichLog)
+        log.clear()
+        self._write_system_message("Log cleared")
+        self._sync_controls()
+
     def write(self, message: str, level: str = "info") -> None:
-        """Write a message to the log.
-
-        Args:
-            message: Message to write
-            level: Log level (info, success, warning, error, debug)
-        """
-        # Apply filter
+        if self._is_paused:
+            return
         if self._filter_level and level != self._filter_level:
             return
 
-        # Apply styling based on level
         styled_message = self._style_message(message, level)
 
         log = self.query_one("#log-content", RichLog)
         log.write(styled_message)
 
-        # Auto-scroll if not paused
-        if self.auto_scroll and not self._is_paused:
+        if self.auto_scroll:
             log.scroll_end(animate=False)
 
+    def _sync_controls(self) -> None:
+        try:
+            self.query_one("#pause-btn", Button).label = "Resume" if self._is_paused else "Pause"
+            self.query_one("#autoscroll-btn", Button).label = "Auto: On" if self.auto_scroll else "Auto: Off"
+            self.query_one("#filter-btn", Button).label = f"Filter: {self._filter_level or 'All'}"
+        except Exception:
+            return
+
     def _style_message(self, message: str, level: str) -> str:
-        """Style a message based on log level."""
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
-        """Write a system message."""
-        styled = f"[dim]-- {message} --[/]"
         log = self.query_one("#log-content", RichLog)
-        log.write(styled)
-
-    def toggle_pause(self) -> None:
-        """Toggle log streaming pause."""
-        self._is_paused = not self._is_paused
-        status = "paused" if self._is_paused else "resumed"
-        self._write_system_message(f"Log {status}")
-
-    def clear(self) -> None:
-        """Clear the log."""
-        log = self.query_one("#log-content", RichLog)
-        log.clear()
-        self._write_system_message("Log cleared")
-
-    def set_filter(self, level: str | None) -> None:
-        """Set log level filter.
-
-        Args:
-            level: Level to filter by, or None for all
-        """
-        self._filter_level = level
-        self._write_system_message(f"Filter set to: {level or 'all'}")
-
-    def toggle_auto_scroll(self) -> None:
-        """Toggle auto-scroll."""
-        self.auto_scroll = not self.auto_scroll
-        status = "enabled" if self.auto_scroll else "disabled"
-        self._write_system_message(f"Auto-scroll {status}")
-
-    def on_message(self, event: UpdateLog) -> None:
-        """Handle UpdateLog messages from other components."""
-        self.write(event.message, event.level)
+        log.write(f"[dim]-- {message} --[/]")
+
+    @on(Button.Pressed, "#pause-btn")
+    def _on_pause(self) -> None:
+        self._is_paused = not self._is_paused
+        self._write_system_message("Log paused" if self._is_paused else "Log resumed")
+        self._sync_controls()
+
+    @on(Button.Pressed, "#clear-btn")
+    def _on_clear(self) -> None:
+        self.clear_log()
+
+    @on(Button.Pressed, "#autoscroll-btn")
+    def _on_autoscroll(self) -> None:
+        self.auto_scroll = not self.auto_scroll
+        self._write_system_message("Auto-scroll enabled" if self.auto_scroll else "Auto-scroll disabled")
+        self._sync_controls()
+
+    @on(Button.Pressed, "#filter-btn")
+    def _on_filter(self) -> None:
+        cur = self._FILTER_LEVELS.index(self._filter_level) if self._filter_level in self._FILTER_LEVELS else 0
+        self._filter_level = self._FILTER_LEVELS[(cur + 1) % len(self._FILTER_LEVELS)]
+        self._write_system_message(f"Filter set to: {self._filter_level or 'all'}")
+        self._sync_controls()
+
+    @on(UpdateLog)
+    def _on_update_log(self, event: UpdateLog) -> None:
+        self.write(event.message, event.level)
""",
    # A) Backup -> FormPane (fields + blocks)
    r"""--- a/src/bkx/tui/pages/backup.py
+++ b/src/bkx/tui/pages/backup.py
@@ -1,21 +1,24 @@
 """bkx/src/bkx/tui/pages/backup.py
 
 Backup page (form) -> emits BackupIntent.
+
+Uses mikomn FormPane for a consistent, validated input experience.
 """
 
 from __future__ import annotations
 
 from pathlib import Path
 
+from mikomn.tui_base.panes.input.form_pane import FormPane
+from mikomn.tui_base.widgets.file_picker import FilterMode
 from textual import on
 from textual.app import ComposeResult
 from textual.containers import Container
-from textual.widgets import Button, Checkbox, Input, Label, Select, Static
+from textual.widgets import Button, Checkbox, Input, Select, Static
 
 from bkx.core.config import get_config
 from bkx.tui.messages import BackupIntent
-from bkx.tui.widgets.file_picker import show_file_picker, show_destination_picker
-from mikomn.tui_base.widgets.file_picker import FilterMode
+from bkx.tui.widgets.file_picker import show_destination_picker, show_file_picker
 
 
 class BackupPage(Container):
     DEFAULT_CLASSES = "bkx-page bkx-backup-page"
 
     def __init__(self) -> None:
         super().__init__()
         self._sources: list[Path] = []
+
+        self._form = FormPane(id="backup-form")
+        self._form.add_field("sources", "Sources", placeholder="Paths to back up (space-separated)")
+        self._form.add_field("destination", "Destination", placeholder="Output path (auto-generated if empty)")
+        self._form.add_field("name", "Name", placeholder="Archive name (auto-generated if empty)")
+        self._form.add_field("excludes", "Excludes", placeholder="*.pyc __pycache__ .git")
+
+        self._algorithm_select = Select(
+            [("zstd", "zstd"), ("gzip", "gzip"), ("lz4", "lz4"), ("xz", "xz"), ("bzip2", "bzip2")],
+            prompt="Compression algorithm",
+            id="algorithm-select",
+            classes="form-field",
+        )
+        self._form.add_block(
+            "algorithm",
+            "Algorithm",
+            self._algorithm_select,
+            get_value=lambda: self._algorithm_select.value or "zstd",
+        )
+        self._form.add_field("level", "Level", placeholder="Compression level")
+
+        self._verify_checkbox = Checkbox("Verify archive after creation", id="verify-checkbox", value=True)
+        self._form.add_block(
+            "verify",
+            "Options",
+            self._verify_checkbox,
+            get_value=lambda: bool(self._verify_checkbox.value),
+        )
 
     def compose(self) -> ComposeResult:
         yield Static("💾 Backup", classes="page-title")
-
-        yield Container(
-            Container(
-                Label("Sources:", classes="form-label"),
-                Input(placeholder="Paths to back up (space-separated)", classes="form-input", id="sources-input"),
-                classes="form-row",
-            ),
-            Container(
-                Label("Destination:", classes="form-label"),
-                Input(placeholder="Output path (auto-generated if empty)", classes="form-input", id="destination-input"),
-                classes="form-row",
-            ),
-            Container(
-                Label("Name:", classes="form-label"),
-                Input(placeholder="Archive name (auto-generated if empty)", classes="form-input", id="name-input"),
-                classes="form-row",
-            ),
-            Container(
-                Label("Excludes:", classes="form-label"),
-                Input(placeholder="*.pyc __pycache__ .git", classes="form-input", id="excludes-input"),
-                classes="form-row",
-            ),
-            Container(
-                Label("Algorithm:", classes="form-label"),
-                Select(
-                    [("zstd","zstd"),("gzip","gzip"),("lz4","lz4"),("xz","xz"),("bzip2","bzip2")],
-                    prompt="Compression algorithm",
-                    id="algorithm-select",
-                ),
-                classes="form-row",
-            ),
-            Container(
-                Label("Level:", classes="form-label"),
-                Input(placeholder="Compression level", classes="form-input", id="level-input"),
-                classes="form-row",
-            ),
-            Container(
-                Label("Options:", classes="form-label"),
-                Checkbox("Verify archive after creation", id="verify-checkbox", value=True),
-                classes="form-row",
-            ),
-            classes="form-container",
-        )
+        yield self._form
 
         yield Container(
             Button("📁 Select Sources", id="btn-select-sources"),
             Button("📂 Select Destination", id="btn-select-destination"),
             Button("🚀 Run Backup", id="btn-run"),
             classes="button-row",
         )
 
     def on_mount(self) -> None:
         config = get_config()
-        try:
-            level = str(getattr(getattr(config, "compression", None), "level", "") or "")
-        except Exception:
-            level = ""
+        level = str(getattr(config, "compression_level", "") or "")
         if level:
-            self.query_one("#level-input", Input).value = level
+            self.query_one("#field-level", Input).value = level
 
     @on(Button.Pressed, "#btn-select-sources")
     async def _pick_sources(self) -> None:
@@ -55,7 +58,7 @@
         if not selected:
             return
         self._sources = [Path(p) for p in selected]
-        self.query_one("#sources-input", Input).value = " ".join(str(p) for p in self._sources)
+        self.query_one("#field-sources", Input).value = " ".join(str(p) for p in self._sources)
 
     @on(Button.Pressed, "#btn-select-destination")
     async def _pick_dest(self) -> None:
@@ -63,7 +66,7 @@
         selected = await show_destination_picker(title="Select destination folder")
         if not selected:
             return
-        self.query_one("#destination-input", Input).value = str(selected)
+        self.query_one("#field-destination", Input).value = str(selected)
 
     @on(Button.Pressed, "#btn-run")
     def _run(self) -> None:
-        sources_text = self.query_one("#sources-input", Input).value.strip()
+        data = self._form.get_form_data()
+
+        sources_text = str(data.get("sources") or "").strip()
         sources = [Path(p) for p in sources_text.split()] if sources_text else self._sources
 
-        dest_text = self.query_one("#destination-input", Input).value.strip()
+        dest_text = str(data.get("destination") or "").strip()
         destination = Path(dest_text) if dest_text else None
 
-        archive_name = self.query_one("#name-input", Input).value.strip() or None
+        archive_name = str(data.get("name") or "").strip() or None
 
-        excludes_text = self.query_one("#excludes-input", Input).value.strip()
+        excludes_text = str(data.get("excludes") or "").strip()
         excludes = excludes_text.split() if excludes_text else []
 
-        algorithm = self.query_one("#algorithm-select", Select).value or "zstd"
-        level_text = self.query_one("#level-input", Input).value.strip()
+        algorithm = str(data.get("algorithm") or "zstd")
+        level_text = str(data.get("level") or "").strip()
         try:
             level = int(level_text) if level_text else 3
         except ValueError:
             level = 3
 
-        verify = self.query_one("#verify-checkbox", Checkbox).value
+        verify = bool(data.get("verify"))
 
         self.post_message(
             BackupIntent(
                 sources=sources,
                 destination=destination,
                 archive_name=archive_name,
                 excludes=excludes,
                 algorithm=str(algorithm),
                 level=level,
                 verify=verify,
             )
         )
""",
    # A) Restore -> FormPane
    r"""--- a/src/bkx/tui/pages/restore.py
+++ b/src/bkx/tui/pages/restore.py
@@ -1,54 +1,47 @@
 """bkx/src/bkx/tui/pages/restore.py
 
-Restore page (minimal) -> emits RestoreIntent.
+Restore page -> emits RestoreIntent.
+
+Uses mikomn FormPane for consistent input UX.
 """
 
 from __future__ import annotations
 
 from pathlib import Path
 
+from mikomn.tui_base.panes.input.form_pane import FormPane
 from textual import on
 from textual.app import ComposeResult
 from textual.containers import Container
-from textual.widgets import Button, Checkbox, Input, Label, Static
+from textual.widgets import Button, Checkbox, Input, Static
 
 from bkx.tui.messages import RestoreIntent
-from bkx.tui.widgets.file_picker import show_file_picker, show_destination_picker
+from bkx.tui.widgets.file_picker import show_destination_picker, show_file_picker
 from mikomn.tui_base.widgets.file_picker import FilterMode
 
 
 class RestorePage(Container):
     DEFAULT_CLASSES = "bkx-page bkx-restore-page"
 
+    def __init__(self) -> None:
+        super().__init__()
+
+        self._form = FormPane(id="restore-form")
+        self._form.add_field("archive", "Archive", placeholder="Archive path")
+        self._form.add_field("destination", "Destination", placeholder="Destination folder")
+        self._form.add_field("paths", "Paths", placeholder="Optional sub-paths (space-separated)")
+
+        self._fresh = Checkbox("Fresh dir", id="fresh-checkbox", value=False)
+        self._overwrite = Checkbox("Overwrite", id="overwrite-checkbox", value=False)
+        self._verify = Checkbox("Verify", id="verify-checkbox", value=True)
+
+        self._form.add_block("fresh_dir", "Fresh dir", self._fresh, get_value=lambda: bool(self._fresh.value))
+        self._form.add_block("overwrite", "Overwrite", self._overwrite, get_value=lambda: bool(self._overwrite.value))
+        self._form.add_block("verify", "Verify", self._verify, get_value=lambda: bool(self._verify.value))
+
     def compose(self) -> ComposeResult:
         yield Static("📥 Restore", classes="page-title")
-
-        yield Container(
-            Container(
-                Label("Archive:", classes="form-label"),
-                Input(placeholder="Archive path", classes="form-input", id="archive-input"),
-                classes="form-row",
-            ),
-            Container(
-                Label("Destination:", classes="form-label"),
-                Input(placeholder="Destination folder", classes="form-input", id="dest-input"),
-                classes="form-row",
-            ),
-            Container(
-                Label("Paths:", classes="form-label"),
-                Input(placeholder="Optional sub-paths (space-separated)", classes="form-input", id="paths-input"),
-                classes="form-row",
-            ),
-            Container(
-                Label("Options:", classes="form-label"),
-                Checkbox("Fresh dir", id="fresh-checkbox", value=False),
-                Checkbox("Overwrite", id="overwrite-checkbox", value=False),
-                Checkbox("Verify", id="verify-checkbox", value=True),
-                classes="form-row",
-            ),
-            classes="form-container",
-        )
+        yield self._form
 
         yield Container(
             Button("📁 Pick Archive", id="btn-pick-archive"),
             Button("📂 Pick Destination", id="btn-pick-dest"),
             Button("🚀 Restore", id="btn-run"),
             classes="button-row",
         )
@@ -58,7 +51,7 @@
     async def _pick_archive(self) -> None:
         selected = await show_file_picker(title="Select archive", mode=FilterMode.FILES_ONLY, multiple=False)
         if not selected:
             return
-        self.query_one("#archive-input", Input).value = str(selected[0])
+        self.query_one("#field-archive", Input).value = str(selected[0])
 
     @on(Button.Pressed, "#btn-pick-dest")
     async def _pick_dest(self) -> None:
@@ -66,26 +59,24 @@
         selected = await show_destination_picker(title="Select destination folder")
         if not selected:
             return
-        self.query_one("#dest-input", Input).value = str(selected)
+        self.query_one("#field-destination", Input).value = str(selected)
 
     @on(Button.Pressed, "#btn-run")
     def _run(self) -> None:
-        archive = Path(self.query_one("#archive-input", Input).value.strip())
-        dest_text = self.query_one("#dest-input", Input).value.strip()
+        data = self._form.get_form_data()
+
+        archive = Path(str(data.get("archive") or "").strip())
+        dest_text = str(data.get("destination") or "").strip()
         destination = Path(dest_text) if dest_text else None
-        paths_text = self.query_one("#paths-input", Input).value.strip()
+        paths_text = str(data.get("paths") or "").strip()
         paths = paths_text.split() if paths_text else []
-        fresh_dir = self.query_one("#fresh-checkbox", Checkbox).value
-        overwrite = self.query_one("#overwrite-checkbox", Checkbox).value
-        verify = self.query_one("#verify-checkbox", Checkbox).value
 
         self.post_message(
             RestoreIntent(
                 archive=archive,
                 destination=destination,
                 paths=paths,
-                fresh_dir=fresh_dir,
-                overwrite=overwrite,
-                verify=verify,
+                fresh_dir=bool(data.get("fresh_dir")),
+                overwrite=bool(data.get("overwrite")),
+                verify=bool(data.get("verify")),
             )
         )
""",
    # A) Transfer -> FormPane
    r"""--- a/src/bkx/tui/pages/transfer.py
+++ b/src/bkx/tui/pages/transfer.py
@@ -1,47 +1,51 @@
 """bkx/src/bkx/tui/pages/transfer.py
 
-Transfer page (minimal) -> emits TransferIntent.
+Transfer page -> emits TransferIntent.
+
+Uses mikomn FormPane for consistent input UX.
 """
 
 from __future__ import annotations
 
 from pathlib import Path
 
+from mikomn.tui_base.panes.input.form_pane import FormPane
 from textual import on
 from textual.app import ComposeResult
 from textual.containers import Container
-from textual.widgets import Button, Input, Label, Select, Static
+from textual.widgets import Button, Input, Select, Static
 
 from bkx.tui.messages import TransferIntent
-from bkx.tui.widgets.file_picker import show_file_picker, show_destination_picker
+from bkx.tui.widgets.file_picker import show_destination_picker, show_file_picker
 from mikomn.tui_base.widgets.file_picker import FilterMode
 
 
 class TransferPage(Container):
     DEFAULT_CLASSES = "bkx-page bkx-transfer-page"
 
+    def __init__(self) -> None:
+        super().__init__()
+
+        self._form = FormPane(id="transfer-form")
+        self._form.add_field("source", "Source", placeholder="Source path")
+        self._form.add_field("destination", "Destination", placeholder="Destination path")
+
+        self._direction_select = Select(
+            [("push", "push"), ("pull", "pull")],
+            prompt="Direction",
+            id="direction-select",
+            classes="form-field",
+        )
+        self._form.add_block(
+            "direction",
+            "Direction",
+            self._direction_select,
+            get_value=lambda: self._direction_select.value or "push",
+        )
+
     def compose(self) -> ComposeResult:
         yield Static("🔄 Transfer", classes="page-title")
-
-        yield Container(
-            Container(
-                Label("Source:", classes="form-label"),
-                Input(placeholder="Source path", classes="form-input", id="source-input"),
-                classes="form-row",
-            ),
-            Container(
-                Label("Destination:", classes="form-label"),
-                Input(placeholder="Destination path", classes="form-input", id="dest-input"),
-                classes="form-row",
-            ),
-            Container(
-                Label("Direction:", classes="form-label"),
-                Select([("push","push"),("pull","pull")], prompt="Direction", id="direction-select"),
-                classes="form-row",
-            ),
-            classes="form-container",
-        )
+        yield self._form
 
         yield Container(
             Button("📁 Pick Source", id="btn-pick-source"),
             Button("📂 Pick Destination", id="btn-pick-dest"),
             Button("🚀 Transfer", id="btn-run"),
             classes="button-row",
         )
@@ -49,7 +53,7 @@
     async def _pick_source(self) -> None:
         selected = await show_file_picker(title="Select source", mode=FilterMode.FILES_AND_DIRS, multiple=False)
         if not selected:
             return
-        self.query_one("#source-input", Input).value = str(selected[0])
+        self.query_one("#field-source", Input).value = str(selected[0])
 
     @on(Button.Pressed, "#btn-pick-dest")
     async def _pick_dest(self) -> None:
@@ -57,14 +61,18 @@
         selected = await show_destination_picker(title="Select destination folder")
         if not selected:
             return
-        self.query_one("#dest-input", Input).value = str(selected)
+        self.query_one("#field-destination", Input).value = str(selected)
 
     @on(Button.Pressed, "#btn-run")
     def _run(self) -> None:
-        source = Path(self.query_one("#source-input", Input).value.strip())
-        destination = Path(self.query_one("#dest-input", Input).value.strip())
-        direction = self.query_one("#direction-select", Select).value or "push"
-        self.post_message(TransferIntent(source=source, destination=destination, direction=str(direction), verify=True))
+        data = self._form.get_form_data()
+        source = Path(str(data.get("source") or "").strip())
+        destination = Path(str(data.get("destination") or "").strip())
+        direction = str(data.get("direction") or "push")
+        self.post_message(TransferIntent(source=source, destination=destination, direction=direction, verify=True))
""",
    # B) Mirror UpdateLog + notifications + toasts into expanded logs pane
    r"""--- a/src/bkx/tui/app.py
+++ b/src/bkx/tui/app.py
@@ -205,6 +205,32 @@
             return shell.run_panel
         except Exception:
             return None
+
+    def _get_logs_pane(self):
+        \"\"\"Return the expanded logs pane (if available).\"\"\"
+        try:
+            from bkx.tui.panes.log import RunLogPane
+
+            return self.query_one("#bkx-logs-log", RunLogPane)
+        except Exception:
+            return None
+
+    def _write_to_logs(self, message: str, level: str = "info") -> None:
+        \"\"\"Write to the persistent Run Panel and the expanded Logs page.\"\"\"
+        run_panel = self._get_run_panel()
+        if run_panel:
+            try:
+                run_panel.write(message, level=level)
+            except Exception:
+                run_panel.write(message)
+
+        logs_pane = self._get_logs_pane()
+        if logs_pane:
+            try:
+                logs_pane.write(message, level=level)
+            except Exception:
+                logs_pane.write(message)
@@ -459,10 +485,7 @@
 
     def _handle_update_log(self, intent: UpdateLog) -> None:
         \"\"\"Handle update log message.\"\"\"
-        run_panel = self._get_run_panel()
-        if run_panel:
-            run_panel.write(intent.message)
+        self._write_to_logs(intent.message, intent.level)
@@ -700,6 +723,7 @@
         try:
             NotificationHistory.record_status(intent.message, level=str(intent.level), source="bkx")
         except Exception:
             pass
+        self._write_to_logs(intent.message, str(intent.level))
         self.notify(intent.message, severity=intent.level)
@@ -721,6 +745,8 @@
         except Exception:
             pass
 
+        self._write_to_logs(intent.message, str(intent.severity))
+
         if not self._show_toasts:
             return
 """,
    # FormPane CSS support (no duplicate selectors, class-based only)
    r"""--- a/src/bkx/tui/css/shell.tcss
+++ b/src/bkx/tui/css/shell.tcss
@@ -83,6 +83,18 @@
 .form-input {
     width: 1fr;
 }
+
+.form-field {
+    width: 1fr;
+}
+
+.form-field-wrapper {
+    width: 1fr;
+}
+
+.form-error {
+    height: auto;
+}
 """,
]

FILES_TO_REMOVE: list[str] = []

TCSS_VALIDATE_IGNORE: list[str] = [
    "**/mikomn/**/tokens.tcss",
]
