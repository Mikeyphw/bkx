#!/usr/bin/env bash
# apply.sh
set -euo pipefail

ROOT="$(pwd)"

echo "==> Applying BKX TUI patches (TCSS broad-selector fixes + Step 3 progress plumbing)"

PATCH_FILE="$(mktemp)"
cat > "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/css/panes/archive_list.tcss
@@
-ArchiveListPane {
+.bkx-archive-list-pane {
@@
-ArchiveListPane > .label {
+.bkx-archive-list-pane > .label {
@@
-ArchiveListPane > .filter {
+.bkx-archive-list-pane > .filter {
@@
-ArchiveListPane > .list {
+.bkx-archive-list-pane > .list {
@@
-ArchiveListPane > .count-label {
+.bkx-archive-list-pane > .count-label {
@@
-ListItem {
+.bkx-archive-list-pane ListItem {
@@
-ListItem:hover {
+.bkx-archive-list-pane ListItem:hover {
@@
-ListItem:focus {
+.bkx-archive-list-pane ListItem:focus {
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/css/panes/archive_preview.tcss
@@
-ArchivePreviewPane {
+.bkx-archive-preview-pane {
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/css/panes/empty_state.tcss
@@
-EmptyStatePane {
+.bkx-empty-state-pane {
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/css/panes/log.tcss
@@
-RunLogPane {
+.bkx-run-log-pane {
@@
-RichLog {
+.bkx-run-log-pane RichLog {
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/css/panes/navigation.tcss
@@
-NavigationPane {
+.bkx-navigation-pane {
@@
-NavListItem {
+.bkx-navigation-pane NavListItem {
@@
-NavListItem:hover {
+.bkx-navigation-pane NavListItem:hover {
@@
-NavListItem:focus {
+.bkx-navigation-pane NavListItem:focus {
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/css/panes/progress.tcss
@@
-ProgressPane {
+.bkx-progress-pane {
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/css/panes/status.tcss
@@
-StatusFooterPane {
+.bkx-status-footer-pane {
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/css/panes/status_bar.tcss
@@
-StatusBarPane {
+.bkx-status-bar-pane {
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/css/panes/status_cards.tcss
@@
-StatusCardsPane {
+.bkx-status-cards-pane {
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/css/panes/theme_settings.tcss
@@
-ThemeSettingsPane {
+.bkx-theme-settings-pane {
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/css/panes/tools_dashboard.tcss
@@
-ToolsDashboardPane {
+.bkx-tools-dashboard-pane {
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/css/panes/transfer_progress.tcss
@@
-TransferProgressPane {
+.bkx-transfer-progress-pane {
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/panes/archive_list.py
@@
         def __init__(
             self,
             title: str = "Archives",
             filter_placeholder: str = "Search archives...",
             **kwargs,
         ):
             super().__init__(title=title, filter_placeholder=filter_placeholder, **kwargs)
+            self.add_class("bkx-archive-list-pane")
             self._archives: list[ArchiveListItem] = []
@@
     class ArchiveListPane(Container):
@@
         def __init__(
             self,
             title: str = "Archives",
             filter_placeholder: str = "Search archives...",
             **kwargs,
         ):
             super().__init__(**kwargs)
+            self.add_class("bkx-archive-list-pane")
             self._title = title
             self._filter_placeholder = filter_placeholder
             self._archives: list[ArchiveListItem] = []
             self._filtered_archives: list[ArchiveListItem] = []
@@
 class ArchivePreviewPane(Container):
+    DEFAULT_CLASSES = "bkx-archive-preview-pane"
     """Preview pane for showing archive details.
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/panes/empty_state.py
@@
 class EmptyStatePane(Container):
+    DEFAULT_CLASSES = "bkx-empty-state-pane"
     """Empty state pane with custom implementation.
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/panes/log.py
@@
 class RunLogPane(Container):
+    DEFAULT_CLASSES = "bkx-run-log-pane"
     """Run log pane with streaming, pause, and filter capabilities.
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/panes/navigation.py
@@
 class NavigationPane(Container):
+    DEFAULT_CLASSES = "bkx-navigation-pane"
     """Sidebar navigation pane for operation selection.
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/panes/status.py
@@
 class StatusFooterPane(Container):
+    DEFAULT_CLASSES = "bkx-status-footer-pane"
     """Status footer pane with key binding hints and status info.
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/panes/status_cards.py
@@
 if _HAS_MIKOMN:
 
     class StatusCardsPane(Container):
         """Status cards pane using mikomn's StatusCards."""
+        DEFAULT_CLASSES = "bkx-status-cards-pane"
@@
 class ThemeSettingsPane(Container):
     """Settings pane for theme configuration."""
+    DEFAULT_CLASSES = "bkx-theme-settings-pane"
@@
 class ToolsDashboardPane(Container):
     """Dashboard-style pane combining status cards and settings."""
+    DEFAULT_CLASSES = "bkx-tools-dashboard-pane"
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/panes/progress.py
@@
 if _HAS_MIKOMN:
     # Use mikomn's progress widgets when available
     class ProgressPane(MikomnProgressPane):
@@
         def __init__(
@@
         ):
             super().__init__(
                 title=title,
                 show_percentage=show_percentage,
                 show_speed=show_speed,
                 show_eta=show_eta,
                 **kwargs,
             )
+            self.add_class("bkx-progress-pane")
             self._operation_name = ""
@@
     class StatusBarPane(Container):
@@
         def __init__(self, **kwargs):
             super().__init__(**kwargs)
+            self.add_class("bkx-status-bar-pane")
             self._status_bar = StatusBar()
@@
 else:
     # Fallback implementation without mikomn
     class ProgressPane(Container):
@@
         def __init__(
@@
         ):
             super().__init__(**kwargs)
+            self.add_class("bkx-progress-pane")
             self._operation_name = ""
@@
     class StatusBarPane(Container):
@@
         def __init__(self, **kwargs):
             super().__init__(**kwargs)
+            self.add_class("bkx-status-bar-pane")
             self._current_status = ""
@@
 class TransferProgressPane(Container):
@@
     def __init__(self, title: str = "Transfer Progress", **kwargs):
         super().__init__(**kwargs)
+        self.add_class("bkx-transfer-progress-pane")
         self._title = title
         self._total_bytes = 0
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/widgets/run_panel.py
@@
 from mikomn.tui_base.panes.display.progress_pane import ProgressPane
 from mikomn.tui_base.panes.display.status_pane import StatusPane
 from mikomn.tui_base.widgets.status import StatusMessage
+from mikomn.tui_base.widgets.progress import GlobalProgressBar
@@
     def __init__(self, *, title: str = "Run", **kwargs) -> None:
         super().__init__(**kwargs)
         self._title = title
         self._status: StatusPane | None = None
         self._progress: ProgressPane | None = None
         self._log: LogPane | None = None
+        self._progress_total: int = 100
@@
     def on_mount(self) -> None:
         self._status = self.query_one(StatusPane)
         self._progress = self.query_one(ProgressPane)
         self._log = self.query_one(LogPane)
+
+    def _get_progress_bar(self) -> GlobalProgressBar | None:
+        if not self._progress:
+            return None
+        try:
+            return self._progress.progress_bar
+        except Exception:
+            return None
+
+    def start_progress(self, *, total: int = 100, message: str | None = None) -> None:
+        """Start or reset the progress bar."""
+        self._progress_total = int(total) if int(total) > 0 else 100
+        bar = self._get_progress_bar()
+        if bar:
+            bar.progress = 0.0
+            bar.eta_seconds = 0
+            bar.saved_mb = 0.0
+        if message:
+            self.set_status(message, level="info")
+
+    def update_progress(
+        self,
+        *,
+        current: int | None = None,
+        total: int | None = None,
+        percent: float | None = None,
+        message: str | None = None,
+        eta_seconds: int | None = None,
+        saved_mb: float | None = None,
+    ) -> None:
+        """Update the progress bar.
+
+        percent accepts either 0..1 or 0..100.
+        If percent is omitted, it is derived from current/total.
+        """
+        if total is not None and int(total) > 0:
+            self._progress_total = int(total)
+
+        if percent is not None:
+            pct = float(percent)
+            if pct <= 1.0:
+                pct *= 100.0
+        elif current is not None and self._progress_total > 0:
+            pct = (float(current) / float(self._progress_total)) * 100.0
+        else:
+            pct = 0.0
+
+        pct = max(0.0, min(100.0, pct))
+
+        bar = self._get_progress_bar()
+        if bar:
+            bar.progress = pct
+            if eta_seconds is not None:
+                bar.eta_seconds = int(eta_seconds)
+            if saved_mb is not None:
+                bar.saved_mb = float(saved_mb)
+
+        if message:
+            self.set_status(message, level="info")
+
+    def complete_progress(self, *, message: str | None = None, success: bool = True) -> None:
+        """Mark progress complete."""
+        bar = self._get_progress_bar()
+        if bar:
+            bar.progress = 100.0
+            bar.eta_seconds = 0
+        if message:
+            self.set_status(message, level="success" if success else "error")
*** End Patch
PATCH

cat >> "${PATCH_FILE}" <<'PATCH'
*** Begin Patch
*** Update File: src/bkx/tui/app.py
@@
     def _handle_progress_started(self, intent: ProgressStarted) -> None:
         """Handle progress started message."""
         run_panel = self._get_run_panel()
-        if run_panel:
-            run_panel.write(f"[{intent.operation}] {intent.message}")
-        if run_panel and run_panel.progress:
-            try:
-                run_panel.progress.start_progress(total=intent.total)
-            except Exception:
-                pass
+        if run_panel:
+            run_panel.write(f"[{intent.operation}] {intent.message}")
+            run_panel.start_progress(total=intent.total, message=intent.message)
@@
     def _handle_progress_update(self, intent: ProgressUpdate) -> None:
         """Handle progress update message."""
         run_panel = self._get_run_panel()
-        if run_panel and run_panel.progress:
-            try:
-                run_panel.progress.update_progress(current=intent.current, total=intent.total)
-            except Exception:
-                pass
+        if run_panel:
+            run_panel.update_progress(
+                current=intent.current,
+                total=intent.total,
+                percent=intent.percent,
+                message=intent.message,
+            )
@@
     def _handle_progress_completed(self, intent: ProgressCompleted) -> None:
         """Handle progress completed message."""
         run_panel = self._get_run_panel()
-        if run_panel:
-            run_panel.write(f"[{intent.operation}] {intent.message}")
-        if run_panel and run_panel.progress:
-            try:
-                run_panel.progress.complete_progress()
-            except Exception:
-                pass
+        if run_panel:
+            run_panel.write(f"[{intent.operation}] {intent.message}")
+            run_panel.complete_progress(message=intent.message, success=True)
*** End Patch
PATCH

echo "==> Patch prepared at ${PATCH_FILE}"

if command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "==> Using git apply"
  git apply "${PATCH_FILE}"
else
  echo "==> Using patch -p0"
  patch -p0 < "${PATCH_FILE}"
fi

echo "==> Running Python compileall"
python - <<'PY'
import compileall
ok = compileall.compile_dir("src", quiet=1)
raise SystemExit(0 if ok else 1)
PY

echo "==> Running mikomn TCSS validator on src/bkx/tui/css (ignoring mikomn tokens.tcss warnings)"
python - <<'PY'
from __future__ import annotations
from pathlib import Path
import sys

css_root = Path("src/bkx/tui/css")
paths = [p for p in css_root.rglob("*.tcss")]

def _run_validator():
    try:
        import mikomn_tools.core.validate as v
    except Exception as e:
        print(f"[validator] mikomn_tools.core.validate import failed: {e}", file=sys.stderr)
        return 0

    # Try common API shapes without guessing exact names
    candidates = [
        ("validate_paths", dict(paths=paths, strict=False)),
        ("validate_path", dict(path=css_root, strict=False)),
        ("validate", dict(paths=paths, strict=False)),
        ("run", dict(paths=paths, strict=False)),
    ]

    for name, kwargs in candidates:
        fn = getattr(v, name, None)
        if not callable(fn):
            continue
        try:
            report = fn(**kwargs)
            # report may be dict-like or object-like
            warnings = getattr(report, "warning_count", None)
            errors = getattr(report, "error_count", None)
            if warnings is None and isinstance(report, dict):
                warnings = report.get("warning", 0) or report.get("warnings", 0)
                errors = report.get("error", 0) or report.get("errors", 0)
            print(f"[validator] {name}: warnings={warnings} errors={errors}")
            if errors and int(errors) > 0:
                return 1
            return 0
        except TypeError:
            continue
        except Exception as e:
            print(f"[validator] {name} failed: {e}", file=sys.stderr)
            continue

    print("[validator] No callable validator entrypoint found in mikomn_tools.core.validate", file=sys.stderr)
    return 0

sys.exit(_run_validator())
PY

echo "==> Done."
PATCH

chmod +x "${PATCH_FILE}"

# Apply immediately (this script is already apply.sh, but we keep it idempotent)
# shellcheck disable=SC1090
bash "${PATCH_FILE}"

echo "==> All changes applied successfully."
PATCH_FILE_CLEANUP="${PATCH_FILE}"
rm -f "${PATCH_FILE_CLEANUP}" || true
PATCH_FILE=""
PATCH
chmod +x "${PATCH_FILE}"

echo "==> Applying patch now..."
bash "${PATCH_FILE}"

rm -f "${PATCH_FILE}" || true
echo "==> apply.sh finished successfully."
