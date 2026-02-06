# step3_spec.py
# Step 3 spec (new runner format)
#
# Adds global intent routing + makes command palette "Global actions" message-driven.

FILES_TO_WRITE = {
    "verify_apply.py": '''
from __future__ import annotations

import dataclasses
import inspect

from bkx.tui.app import BkxTuiApp
from bkx.tui import messages as m


def test_intents_exist_and_shape() -> None:
    for name in (
        "ShowCommandPalette",
        "ShowNotificationCenter",
        "CycleTheme",
        "ReloadCss",
        "ClearLogs",
        "ToggleDebugOverlay",
        "ToggleToasts",
    ):
        assert hasattr(m, name), f"missing intent: {name}"

    assert dataclasses.is_dataclass(m.ShowCommandPalette)
    fields = {f.name for f in dataclasses.fields(m.ShowCommandPalette)}
    assert "query" in fields


def test_command_palette_has_global_actions_group() -> None:
    src = inspect.getsource(BkxTuiApp.build_command_palette_commands)
    assert 'group="Global actions"' in src
    assert "Clear logs" in src


def test_palette_selection_posts_intents() -> None:
    src = inspect.getsource(BkxTuiApp.on_command_selected)
    for token in (
        "post_message(CycleTheme",
        "post_message(ReloadCss",
        "post_message(ClearLogs",
        "post_message(ShowNotificationCenter",
        "post_message(ToggleDebugOverlay",
        "post_message(ToggleToasts",
    ):
        assert token in src


def test_global_intent_handlers_exist_and_routed() -> None:
    for name in (
        "_handle_show_command_palette",
        "_handle_show_notification_center",
        "_handle_cycle_theme",
        "_handle_reload_css",
        "_handle_clear_logs",
        "_handle_toggle_debug_overlay",
        "_handle_toggle_toasts",
    ):
        assert hasattr(BkxTuiApp, name), f"missing handler: {name}"

    src = inspect.getsource(BkxTuiApp.on_intent)
    for token in (
        "ShowCommandPalette",
        "ShowNotificationCenter",
        "CycleTheme",
        "ReloadCss",
        "ClearLogs",
        "ToggleDebugOverlay",
        "ToggleToasts",
    ):
        assert token in src
'''
}

FILES_TO_REMOVE = []

TCSS_VALIDATE_IGNORE = [
    "**/mikomn/**/tokens.tcss",
]
