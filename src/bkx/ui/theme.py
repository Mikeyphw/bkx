"""Theme management for bkx using mikomn's unified theme registry.

bkx treats themes as palette dictionaries (mikomn v0.3+):
- Themes are JSON: <name>.json
- Each theme contains a top-level ``palette`` mapping token -> hex color

This module provides:
- A small bkx-facing API for retrieving palette colors
- Rich style helpers for CLI output
- Theme directory registration and preference persistence
- Bridged theme-change callbacks for both bkx and mikomn callback conventions
"""

from __future__ import annotations

import json
import os
from collections.abc import Callable
from pathlib import Path
from typing import Any

from mikomn import (
    get_current_theme as _get_current_theme,
)
from mikomn import (
    get_palette as _get_palette,
)
from mikomn import (
    get_theme_names as _get_theme_names,
)
from mikomn import (
    load_theme,
)
from mikomn import (
    off_theme_change as _off_theme_change,
)
from mikomn import (
    on_theme_change as _on_theme_change,
)
from mikomn import (
    register_theme_dir as _register_theme_dir,
)
from mikomn.themes.core import generate_rich_styles, set_current_theme

DEFAULT_THEME = "sweet-dracula"

_THEME_ENV = "BKX_THEME"
_THEME_DIR_ENV = "BKX_THEME_DIR"
_THEME_PERSIST_ENV = "BKX_PERSIST_THEME"

_current_theme: str = DEFAULT_THEME
_theme_cache: dict[str, Any] = {}
_rich_styles_cache: dict[str, str] = {}
_custom_theme_dirs: list[str] = []
_theme_persist_enabled: bool = False

# Callback bridge
_on_theme_change_callbacks: list[Callable[..., None]] = []
_bridge_registered: bool = False


def _init_theme() -> None:
    """Initialize theme from environment/default and sync to mikomn."""
    global _current_theme, _theme_persist_enabled

    env_theme = os.environ.get(_THEME_ENV)
    if env_theme:
        _current_theme = env_theme
    else:
        _current_theme = DEFAULT_THEME

    _theme_persist_enabled = os.environ.get(_THEME_PERSIST_ENV, "").strip().lower() in (
        "1",
        "true",
        "yes",
        "on",
    )

    try:
        # Ensure mikomn global theme state matches bkx preference.
        set_current_theme(_current_theme)
    except Exception:
        # Keep bkx usable even if mikomn theme registry is unavailable.
        pass


def get_current_theme_name() -> str:
    """Return the currently active theme name."""
    return _current_theme


def _load_theme_or_empty(name: str) -> dict[str, Any]:
    theme = load_theme(name)
    return theme if isinstance(theme, dict) else {}


def get_active_theme() -> dict[str, Any]:
    """Return the active theme dict (as loaded by mikomn)."""
    global _theme_cache, _rich_styles_cache
    if not _theme_cache:
        # Prefer mikomn's global current theme name (v0.3+).
        try:
            cur = _get_current_theme()
            if isinstance(cur, str) and cur:
                _theme_cache = _load_theme_or_empty(cur)
            else:
                _theme_cache = _load_theme_or_empty(_current_theme)
        except Exception:
            _theme_cache = _load_theme_or_empty(_current_theme)

        _rich_styles_cache = generate_rich_styles(_current_theme) or {}
    return _theme_cache


def get_palette() -> dict[str, str]:
    """Return the active palette mapping token -> hex color."""
    try:
        pal = _get_palette(_current_theme) or {}
    except Exception:
        theme = get_active_theme()
        pal = theme.get("palette", {})
    return pal if isinstance(pal, dict) else {}


def get_rich_styles() -> dict[str, str]:
    """Return a small dict of common Rich styles (hex colors).

    mikomn's ``generate_rich_styles`` returns hex colors, which are valid Rich style
    values (e.g. ``"#50FA7B"``). bkx wraps these into markup when formatting.
    """
    global _rich_styles_cache
    if not _rich_styles_cache:
        _rich_styles_cache = generate_rich_styles(_current_theme) or {}
    return _rich_styles_cache


def set_theme(name: str) -> bool:
    """Set the active theme by name, syncing to mikomn."""
    global _current_theme, _theme_cache, _rich_styles_cache

    theme = _load_theme_or_empty(name)
    if not theme:
        return False

    _current_theme = name
    _theme_cache = theme
    _rich_styles_cache = generate_rich_styles(name) or {}

    try:
        set_current_theme(name)
    except Exception:
        pass

    if _theme_persist_enabled:
        save_theme_preference(name)

    return True


def cycle_theme() -> str:
    """Cycle to the next available theme and activate it."""
    names = get_all_theme_names()
    if not names:
        return _current_theme

    try:
        idx = names.index(_current_theme)
        next_name = names[(idx + 1) % len(names)]
    except ValueError:
        next_name = names[0]

    set_theme(next_name)
    return next_name


def get_all_theme_names() -> list[str]:
    """Return all theme names registered with mikomn."""
    try:
        return list(_get_theme_names())
    except Exception:
        return []


def get_theme_names() -> list[str]:
    """Alias for backward compatibility."""
    return get_all_theme_names()


def reload_theme() -> None:
    """Reload cached theme data for the currently selected theme."""
    global _theme_cache, _rich_styles_cache
    _theme_cache = _load_theme_or_empty(_current_theme)
    _rich_styles_cache = generate_rich_styles(_current_theme) or {}


# ---------------------------------------------------------------------------
# Palette accessors (bkx-friendly names)
# ---------------------------------------------------------------------------


def primary_color() -> str:
    pal = get_palette()
    return pal.get("accent_primary", pal.get("accent", "#3B82F6"))


def secondary_color() -> str:
    pal = get_palette()
    return pal.get("accent_secondary", pal.get("accent2", "#8B5CF6"))


def accent_color() -> str:
    pal = get_palette()
    return pal.get("accent", pal.get("accent_primary", "#F59E0B"))


def foreground_color() -> str:
    pal = get_palette()
    return pal.get("fg", pal.get("text_primary", "#F9FAFB"))


def background_color() -> str:
    pal = get_palette()
    return pal.get("bg", "#1A1A2E")


def surface_color() -> str:
    pal = get_palette()
    return pal.get("surface", "#16213E")


def success_color() -> str:
    pal = get_palette()
    return pal.get("success", "#10B981")


def warning_color() -> str:
    pal = get_palette()
    return pal.get("warning", "#F59E0B")


def error_color() -> str:
    pal = get_palette()
    return pal.get("error", "#EF4444")


def foreground_muted_color() -> str:
    pal = get_palette()
    return pal.get("muted", pal.get("text_secondary", "#9CA3AF"))


# ---------------------------------------------------------------------------
# Rich style helpers
# ---------------------------------------------------------------------------


def success_style() -> str:
    return f"bold {get_rich_styles().get('success', success_color())}"


def warning_style() -> str:
    return f"bold {get_rich_styles().get('warning', warning_color())}"


def error_style() -> str:
    return f"bold {get_rich_styles().get('error', error_color())}"


def info_style() -> str:
    return get_rich_styles().get("fg", foreground_color())


def primary_style() -> str:
    return f"bold {primary_color()}"


def accent_style() -> str:
    return f"bold {accent_color()}"


# ---------------------------------------------------------------------------
# Theme change callbacks (bridged)
# ---------------------------------------------------------------------------


def _bridge_theme_change(theme_name: str, palette: dict[str, str]) -> None:
    for cb in list(_on_theme_change_callbacks):
        try:
            cb(theme_name, palette)
            continue
        except TypeError:
            try:
                cb(theme_name)
            except Exception:
                continue
        except Exception:
            continue


def on_theme_change(callback: Callable[..., None]) -> None:
    """Register a callback for theme changes.

    Callback may accept:
    - (theme_name: str)
    - (theme_name: str, palette: dict[str, str])
    """
    global _bridge_registered
    _on_theme_change_callbacks.append(callback)
    if not _bridge_registered:
        _on_theme_change(_bridge_theme_change)
        _bridge_registered = True


def off_theme_change(callback: Callable[..., None]) -> None:
    """Unregister a previously registered callback."""
    if callback in _on_theme_change_callbacks:
        _on_theme_change_callbacks.remove(callback)
    if not _on_theme_change_callbacks:
        try:
            _off_theme_change(_bridge_theme_change)
        except Exception:
            pass


# ---------------------------------------------------------------------------
# Custom theme directories
# ---------------------------------------------------------------------------


def register_theme_dir(directory: str) -> bool:
    """Register a custom theme directory with mikomn."""
    global _custom_theme_dirs
    path = Path(directory)
    if not (path.exists() and path.is_dir()):
        return False

    _register_theme_dir(path)
    if str(path) not in _custom_theme_dirs:
        _custom_theme_dirs.append(str(path))
    reload_theme()
    return True


def get_custom_theme_dirs() -> list[str]:
    return list(_custom_theme_dirs)


def load_custom_theme_dirs() -> None:
    env_dir = os.environ.get(_THEME_DIR_ENV)
    if env_dir:
        register_theme_dir(env_dir)


# ---------------------------------------------------------------------------
# Theme persistence
# ---------------------------------------------------------------------------


def _get_config_dir() -> Path:
    config_home = os.environ.get("XDG_CONFIG_HOME", "")
    if config_home:
        return Path(config_home) / "bkx"
    return Path.home() / ".config" / "bkx"


def _get_theme_config_file() -> Path:
    return _get_config_dir() / "theme.json"


def enable_theme_persistence(enabled: bool = True) -> None:
    global _theme_persist_enabled
    _theme_persist_enabled = enabled


def is_theme_persistence_enabled() -> bool:
    return _theme_persist_enabled


def save_theme_preference(theme_name: str) -> bool:
    if not _theme_persist_enabled:
        return False

    config_dir = _get_config_dir()
    config_dir.mkdir(parents=True, exist_ok=True)
    config_file = _get_theme_config_file()
    try:
        config_file.write_text(json.dumps({"theme": theme_name}, indent=2), encoding="utf-8")
    except Exception:
        return False
    return True


def load_theme_preference() -> str | None:
    config_file = _get_theme_config_file()
    if not config_file.exists():
        return None
    try:
        data = json.loads(config_file.read_text(encoding="utf-8"))
    except Exception:
        return None
    theme = data.get("theme")
    return theme if isinstance(theme, str) else None


# ---------------------------------------------------------------------------
# Themed message helpers (Rich markup)
# ---------------------------------------------------------------------------


def get_status_message(level: str = "info") -> tuple[str, str]:
    icon_map = {"info": "ℹ️", "success": "✅", "warning": "⚠️", "error": "❌"}
    style_map = {
        "info": info_style(),
        "success": success_style(),
        "warning": warning_style(),
        "error": error_style(),
    }
    return (icon_map.get(level, "ℹ️"), style_map.get(level, info_style()))


def themed_message(message: str, level: str = "info") -> str:
    icon, style = get_status_message(level)
    return f"{icon} [{style}]{message}[/]"


def themed_header(title: str) -> str:
    return f"[bold {primary_color()}]{title}[/]"


def themed_prompt(prompt_text: str) -> str:
    return f"[bold {accent_color()}]{prompt_text}[/]"


_init_theme()
load_custom_theme_dirs()
