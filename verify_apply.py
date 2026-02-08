from __future__ import annotations

import sys
from pathlib import Path


def _ensure_src_on_path() -> None:
    root = Path(__file__).resolve().parent
    src = root / "src"
    if str(src) not in sys.path:
        sys.path.insert(0, str(src))


def test_step4b_exports_present() -> None:
    _ensure_src_on_path()
    import bkx.tui.overlays as o

    assert hasattr(o, "BkxHelpOverlay")
    assert hasattr(o, "BkxFilterSheet")


def test_step4b_action_help_uses_wrapper() -> None:
    txt = Path("src/bkx/tui/app.py").read_text(encoding="utf-8")
    assert "BkxHelpOverlay" in txt
    assert "def action_help" in txt


def test_step4b_archive_has_filters_binding() -> None:
    txt = Path("src/bkx/tui/panes/archive_list.py").read_text(encoding="utf-8")
    assert '("f", "filters", "Filters")' in txt

