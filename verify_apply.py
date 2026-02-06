from __future__ import annotations

import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent


def _read(rel: str) -> str:
    return (REPO_ROOT / rel).read_text(encoding="utf-8")


def test_tui_init_is_import_safe() -> None:
    text = _read("src/bkx/tui/__init__.py")
    assert "try:" in text
    assert "from bkx.tui import app" in text
    assert "except Exception" in text


def test_step3_intents_exist_in_messages() -> None:
    text = _read("src/bkx/tui/messages.py")
    for name in (
        "ShowCommandPalette",
        "OpenNotificationCenter",
        "CycleTheme",
        "SetTheme",
        "ReloadCss",
        "ClearRunLog",
        "ToggleDebugOverlay",
        "DumpDebugSnapshot",
        "ToggleToasts",
    ):
        assert re.search(rf"(?m)^class {name}\b", text), f"missing intent: {name}"


def test_step3_palette_contains_expected_commands() -> None:
    text = _read("src/bkx/tui/app.py")
    # Command IDs
    for token in (
        'id="nav:backup"',
        'id="nav:archive"',
        'id="app:cycle-theme"',
        'id="app:reload-css"',
        'id="app:toggle-debug"',
        'id="app:dump-debug"',
        'id="app:clear-run-log"',
        'id="app:notifications"',
        'id="app:toggle-toasts"',
        "theme:set:",
    ):
        assert token in text, f"missing palette token: {token}"

    # Groups
    for token in ('group="Navigation"', 'group="App"', 'group="Theme"'):
        assert token in text, f"missing group: {token}"

    # Theme command label (per current implementation)
    assert "Set theme:" in text


def test_step3_palette_selection_is_intent_driven() -> None:
    text = _read("src/bkx/tui/app.py")
    # Palette -> global intents
    for token in (
        "post_message(NavigateToScreen",
        "post_message(SetTheme",
        "post_message(CycleTheme",
        "post_message(ReloadCss",
        "post_message(ToggleDebugOverlay",
        "post_message(DumpDebugSnapshot",
        "post_message(ClearRunLog",
        "post_message(OpenNotificationCenter",
        "post_message(ToggleToasts",
    ):
        assert token in text, f"missing post_message route: {token}"


def test_step3_global_intent_handlers_registered() -> None:
    text = _read("src/bkx/tui/app.py")
    for token in (
        "ShowCommandPalette: self._handle_show_command_palette",
        "OpenNotificationCenter: self._handle_open_notification_center",
        "CycleTheme: self._handle_cycle_theme_intent",
        "SetTheme: self._handle_set_theme_intent",
        "ReloadCss: self._handle_reload_css_intent",
        "ClearRunLog: self._handle_clear_run_log_intent",
        "ToggleToasts: self._handle_toggle_toasts_intent",
        "ToggleDebugOverlay: self._handle_toggle_debug_overlay_intent",
        "DumpDebugSnapshot: self._handle_dump_debug_snapshot_intent",
    ):
        assert token in text, f"missing handler mapping: {token}"
