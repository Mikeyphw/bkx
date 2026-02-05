"""bkx/src/bkx/tui/pages/registry.py

Page registry for BKX shell navigation.

Uses mikomn PageRegistry/PageSpec for:
- consistent ordering
- sidebar and footer tab generation
- key-based navigation (1..6)
"""

from __future__ import annotations

from mikomn.tui_base.utils.page_registry import PageRegistry, PageSpec

from bkx.tui.pages.backup import BackupPage
from bkx.tui.pages.archive import ArchivePage
from bkx.tui.pages.restore import RestorePage
from bkx.tui.pages.transfer import TransferPage
from bkx.tui.pages.tools import ToolsPage
from bkx.tui.pages.logs import LogsPage


def create_page_registry() -> PageRegistry:
    registry = PageRegistry()
    pages: list[PageSpec] = [
        PageSpec(route="backup", factory=lambda: BackupPage(), label="Backup", key="1", icon="💾", group="Main", group_order=1, order=1),
        PageSpec(route="archive", factory=lambda: ArchivePage(), label="Archive", key="2", icon="📦", group="Main", group_order=1, order=2),
        PageSpec(route="restore", factory=lambda: RestorePage(), label="Restore", key="3", icon="📥", group="Main", group_order=1, order=3),
        PageSpec(route="transfer", factory=lambda: TransferPage(), label="Transfer", key="4", icon="🔄", group="Main", group_order=1, order=4),
        PageSpec(route="tools", factory=lambda: ToolsPage(), label="Tools", key="5", icon="🔧", group="Main", group_order=1, order=5),
        PageSpec(route="logs", factory=lambda: LogsPage(), label="Logs", key="6", icon="📋", group="Main", group_order=1, order=6),
    ]
    for p in pages:
        registry.register(p)
    return registry

