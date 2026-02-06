
from __future__ import annotations

import dataclasses
import inspect

from bkx.tui.app import BkxTuiApp
from bkx.tui import messages as m


def test_step3_intents_exist() -> None:
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
    assert "query" in {f.name for f in dataclasses.fields(m.ShowCommandPalette)}


def test_palette_global_actions_group() -> None:
    src = inspect.getsource(BkxTuiApp.build_command_palette_commands)
    assert 'group="Global actions"' in src
    assert "Clear logs" in src


def test_palette_selection_posts_intents() -> None:
    src = inspect.getsource(BkxTuiApp.on_command_selected)
    for token in (
        "post_message(CycleTheme",
        "post_message(ReloadCss",
        "post_message(ToggleDebugOverlay",
        "post_message(ClearLogs",
        "post_message(ShowNotificationCenter",
        "post_message(ToggleToasts",
    ):
        assert token in src


def test_on_intent_routes_global_actions() -> None:
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
