"""bkx/src/bkx/tui/state.py

Persistence for BKX TUI user state.

Stored under XDG config directory (default: ~/.config/bkx/tui_state.json).

Best-effort load/save:
- missing/invalid JSON returns defaults
- write failures are ignored
"""

from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


def _get_config_dir() -> Path:
    config_home = os.environ.get("XDG_CONFIG_HOME", "")
    if config_home:
        return Path(config_home) / "bkx"
    return Path.home() / ".config" / "bkx"


def get_state_file() -> Path:
    return _get_config_dir() / "tui_state.json"


@dataclass
class TuiState:
    """Small persisted state for the BKX TUI."""

    last_route: str = "backup"
    layout_mode: str = "normal"
    show_toasts: bool = True

    # Step 6A.2: restore archive UX
    archive_filter_text: str = ""
    archive_selected_id: str = ""

    # Step 7A: Selection + Focus Restore
    archive_scroll_position: int = 0
    last_focused_widget: str = ""
    last_focused_pane: str = ""
    restore_selection_on_navigate: bool = True

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> TuiState:
        return cls(
            last_route=str(data.get("last_route", cls.last_route)),
            layout_mode=str(data.get("layout_mode", cls.layout_mode)),
            show_toasts=bool(data.get("show_toasts", cls.show_toasts)),
            archive_filter_text=str(data.get("archive_filter_text", cls.archive_filter_text)),
            archive_selected_id=str(data.get("archive_selected_id", cls.archive_selected_id)),
            # Step 7A additions
            archive_scroll_position=int(data.get("archive_scroll_position", cls.archive_scroll_position)),
            last_focused_widget=str(data.get("last_focused_widget", cls.last_focused_widget)),
            last_focused_pane=str(data.get("last_focused_pane", cls.last_focused_pane)),
            restore_selection_on_navigate=bool(data.get("restore_selection_on_navigate", cls.restore_selection_on_navigate)),
        )


def load_state() -> TuiState:
    path = get_state_file()
    try:
        raw = path.read_text(encoding="utf-8")
        data = json.loads(raw)
        if isinstance(data, dict):
            return TuiState.from_json(data)
    except Exception:
        pass
    return TuiState()


def save_state(state: TuiState) -> None:
    path = get_state_file()
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(asdict(state), indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    except Exception:
        return
