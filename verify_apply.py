from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parent
TUI_INIT_PY = REPO / "src" / "bkx" / "tui" / "__init__.py"
MESSAGES_PY = REPO / "src" / "bkx" / "tui" / "messages.py"
APP_PY = REPO / "src" / "bkx" / "tui" / "app.py"


def _read(p: Path) -> str:
    return p.read_text(encoding="utf-8")


def _assert_re(pattern: str, text: str) -> None:
    assert re.search(pattern, text, flags=re.M | re.S), f"Missing pattern: {pattern!r}"


def test_tui_init_is_import_light() -> None:
    txt = _read(TUI_INIT_PY)
    assert "from bkx.tui import app" not in txt


def test_global_intents_exist() -> None:
    txt = _read(MESSAGES_PY)
    for name in (
        "ShowCommandPalette",
        "CycleTheme",
        "SetTheme",
        "ReloadCss",
        "ClearRunLog",
        "OpenNotificationCenter",
        "ToggleToasts",
        "ToggleDebugOverlay",
        "DumpDebugSnapshot",
    ):
        assert f"class {name}" in txt


def test_actions_post_intents() -> None:
    txt = _read(APP_PY)
    _assert_re(r"def action_command_palette\(self\).*?post_message\(ShowCommandPalette\(\)\)", txt)
    _assert_re(r"def action_cycle_theme\(self\).*?post_message\(CycleTheme\(\)\)", txt)
    _assert_re(
        r"def action_notification_center\(self\)"
        r".*?post_message\(OpenNotificationCenter\(\)\)",
        txt,
    )
    _assert_re(r"def action_toggle_toasts\(self\).*?post_message\(ToggleToasts\(\)\)", txt)
    _assert_re(r"def action_toggle_debug\(self\).*?post_message\(ToggleDebugOverlay\(\)\)", txt)
    _assert_re(r"def action_dump_debug\(self\).*?post_message\(DumpDebugSnapshot\(\)\)", txt)


def test_palette_posts_intents() -> None:
    txt = _read(APP_PY)
    _assert_re(
        r"def on_command_selected\(self, event: CommandSelected\)"
        r".*?post_message\(NavigateToScreen\(",
        txt,
    )
    _assert_re(r'cmd_id == "app:reload-css".*?post_message\(ReloadCss\(\)\)', txt)
    _assert_re(r'cmd_id == "app:clear-run-log".*?post_message\(ClearRunLog\(\)\)', txt)


def test_reload_css_uses_mikomn_registry() -> None:
    txt = _read(APP_PY)
    assert "reload_bkx_css" not in txt
    _assert_re(r"reload_css\(\s*self,\s*\n\s*layout_mode=self\.layout_mode", txt)
