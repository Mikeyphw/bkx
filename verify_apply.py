from __future__ import annotations

import inspect

from bkx.tui.app import BkxTuiApp
from bkx.tui.pages.backup import BackupPage
from bkx.tui.pages.logs import LogsPage
from bkx.tui.pages.tools import ToolsPage


def test_step1_bindings_present() -> None:
    bindings = {b[0]: b[1] for b in BkxTuiApp.BINDINGS if isinstance(b, tuple) and len(b) >= 2}
    assert bindings.get("ctrl+p") == "command_palette"
    assert bindings.get("ctrl+n") == "notification_center"


def test_step2_pages_upgraded() -> None:
    assert "ToolsDashboardPane" in inspect.getsource(ToolsPage.compose)
    assert "RunLogPane" in inspect.getsource(LogsPage.compose)


def test_backup_async_size_and_truncation_marker() -> None:
    src = inspect.getsource(BackupPage)
    assert "_start_size_worker" in src
    assert "_drain_size_updates" in src
    assert "~approx" in src
