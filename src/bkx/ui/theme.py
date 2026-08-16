"""BKX theme helpers with an optional, lazily imported mikomn bridge.

The base CLI uses deterministic built-in Rich colors. Installing ``bkx[interactive]``
or ``bkx[tui]`` enables mikomn theme discovery without making it a base dependency.
"""

from __future__ import annotations

import json
import os
from collections.abc import Callable
from pathlib import Path
from typing import Any

DEFAULT_THEME = "sweet-dracula"
_THEME_ENV = "BKX_THEME"
_THEME_DIR_ENV = "BKX_THEME_DIR"
_THEME_PERSIST_ENV = "BKX_PERSIST_THEME"

_FALLBACK_PALETTE = {
    "accent_primary": "#3B82F6",
    "accent_secondary": "#8B5CF6",
    "accent": "#F59E0B",
    "fg": "#F9FAFB",
    "bg": "#1A1A2E",
    "surface": "#16213E",
    "success": "#10B981",
    "warning": "#F59E0B",
    "error": "#EF4444",
    "muted": "#9CA3AF",
}

_current_theme = os.environ.get(_THEME_ENV) or DEFAULT_THEME
_theme_cache: dict[str, Any] = {}
_rich_styles_cache: dict[str, str] = {}
_custom_theme_dirs: list[str] = []
_theme_persist_enabled = os.environ.get(_THEME_PERSIST_ENV, "").strip().lower() in {"1", "true", "yes", "on"}
_on_theme_change_callbacks: list[Callable[..., None]] = []
_bridge_registered = False
_mikomn_api_cache: dict[str, Any] | None | bool = False


def _mikomn_api() -> dict[str, Any] | None:
    """Import mikomn only when a theme-only feature is actually requested."""
    global _mikomn_api_cache
    if _mikomn_api_cache is not False:
        return _mikomn_api_cache if isinstance(_mikomn_api_cache, dict) else None
    try:
        from mikomn import get_current_theme, get_palette as mikomn_get_palette, get_theme_names as mikomn_get_theme_names
        from mikomn import load_theme, off_theme_change, on_theme_change, register_theme_dir as mikomn_register_theme_dir
        from mikomn.themes.core import generate_rich_styles, set_current_theme
    except ModuleNotFoundError:
        _mikomn_api_cache = None
        return None
    _mikomn_api_cache = {
        "get_current_theme": get_current_theme,
        "get_palette": mikomn_get_palette,
        "get_theme_names": mikomn_get_theme_names,
        "load_theme": load_theme,
        "off_theme_change": off_theme_change,
        "on_theme_change": on_theme_change,
        "register_theme_dir": mikomn_register_theme_dir,
        "generate_rich_styles": generate_rich_styles,
        "set_current_theme": set_current_theme,
    }
    env_dir = os.environ.get(_THEME_DIR_ENV)
    if env_dir:
        path = Path(env_dir).expanduser().resolve(strict=False)
        if path.exists() and path.is_dir():
            try:
                mikomn_register_theme_dir(path)
                if str(path) not in _custom_theme_dirs:
                    _custom_theme_dirs.append(str(path))
            except Exception:
                pass
    return _mikomn_api_cache


def _init_theme() -> None:
    """Initialize only BKX-local state; optional mikomn stays lazy."""
    return None


def get_current_theme_name() -> str:
    return _current_theme


def _load_theme_or_empty(name: str) -> dict[str, Any]:
    api = _mikomn_api()
    if api is None:
        return {}
    try:
        theme = api["load_theme"](name)
    except Exception:
        return {}
    return theme if isinstance(theme, dict) else {}


def get_active_theme() -> dict[str, Any]:
    global _theme_cache, _rich_styles_cache
    if not _theme_cache:
        api = _mikomn_api()
        if api is not None:
            try:
                cur = api["get_current_theme"]()
                _theme_cache = _load_theme_or_empty(cur if isinstance(cur, str) and cur else _current_theme)
                _rich_styles_cache = api["generate_rich_styles"](_current_theme) or {}
            except Exception:
                _theme_cache = {}
        if not _theme_cache:
            _theme_cache = {"palette": dict(_FALLBACK_PALETTE)}
    return _theme_cache


def get_palette() -> dict[str, str]:
    api = _mikomn_api()
    if api is not None:
        try:
            palette = api["get_palette"](_current_theme) or {}
            if isinstance(palette, dict) and palette:
                return palette
        except Exception:
            pass
    theme = get_active_theme()
    palette = theme.get("palette", {})
    return palette if isinstance(palette, dict) else dict(_FALLBACK_PALETTE)


def get_rich_styles() -> dict[str, str]:
    global _rich_styles_cache
    if not _rich_styles_cache:
        api = _mikomn_api()
        if api is not None:
            try:
                _rich_styles_cache = api["generate_rich_styles"](_current_theme) or {}
            except Exception:
                _rich_styles_cache = {}
        if not _rich_styles_cache:
            pal = get_palette()
            _rich_styles_cache = {"fg": pal.get("fg", _FALLBACK_PALETTE["fg"]), "success": pal.get("success", _FALLBACK_PALETTE["success"]), "warning": pal.get("warning", _FALLBACK_PALETTE["warning"]), "error": pal.get("error", _FALLBACK_PALETTE["error"])}
    return _rich_styles_cache


def set_theme(name: str) -> bool:
    global _current_theme, _theme_cache, _rich_styles_cache
    theme = _load_theme_or_empty(name)
    if not theme:
        return False
    _current_theme = name
    _theme_cache = theme
    api = _mikomn_api()
    _rich_styles_cache = api["generate_rich_styles"](name) or {} if api is not None else {}
    if api is not None:
        try:
            api["set_current_theme"](name)
        except Exception:
            pass
    if _theme_persist_enabled:
        save_theme_preference(name)
    return True


def cycle_theme() -> str:
    names = get_all_theme_names()
    if not names:
        return _current_theme
    try:
        next_name = names[(names.index(_current_theme) + 1) % len(names)]
    except ValueError:
        next_name = names[0]
    set_theme(next_name)
    return next_name


def get_all_theme_names() -> list[str]:
    api = _mikomn_api()
    if api is None:
        return []
    try:
        return list(api["get_theme_names"]())
    except Exception:
        return []


def get_theme_names() -> list[str]:
    return get_all_theme_names()


def reload_theme() -> None:
    global _theme_cache, _rich_styles_cache
    _theme_cache = {}
    _rich_styles_cache = {}
    get_active_theme()


def primary_color() -> str:
    pal = get_palette(); return pal.get("accent_primary", pal.get("accent", _FALLBACK_PALETTE["accent_primary"]))

def secondary_color() -> str:
    pal = get_palette(); return pal.get("accent_secondary", pal.get("accent2", _FALLBACK_PALETTE["accent_secondary"]))

def accent_color() -> str:
    pal = get_palette(); return pal.get("accent", pal.get("accent_primary", _FALLBACK_PALETTE["accent"]))

def foreground_color() -> str:
    pal = get_palette(); return pal.get("fg", pal.get("text_primary", _FALLBACK_PALETTE["fg"]))

def background_color() -> str:
    return get_palette().get("bg", _FALLBACK_PALETTE["bg"])

def surface_color() -> str:
    return get_palette().get("surface", _FALLBACK_PALETTE["surface"])

def success_color() -> str:
    return get_palette().get("success", _FALLBACK_PALETTE["success"])

def warning_color() -> str:
    return get_palette().get("warning", _FALLBACK_PALETTE["warning"])

def error_color() -> str:
    return get_palette().get("error", _FALLBACK_PALETTE["error"])

def foreground_muted_color() -> str:
    pal = get_palette(); return pal.get("muted", pal.get("text_secondary", _FALLBACK_PALETTE["muted"]))

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


def _bridge_theme_change(theme_name: str, palette: dict[str, str]) -> None:
    for cb in list(_on_theme_change_callbacks):
        try:
            cb(theme_name, palette)
        except TypeError:
            try:
                cb(theme_name)
            except Exception:
                pass
        except Exception:
            pass


def on_theme_change(callback: Callable[..., None]) -> None:
    global _bridge_registered
    _on_theme_change_callbacks.append(callback)
    api = _mikomn_api()
    if api is not None and not _bridge_registered:
        api["on_theme_change"](_bridge_theme_change)
        _bridge_registered = True


def off_theme_change(callback: Callable[..., None]) -> None:
    global _bridge_registered
    if callback in _on_theme_change_callbacks:
        _on_theme_change_callbacks.remove(callback)
    api = _mikomn_api()
    if api is not None and _bridge_registered and not _on_theme_change_callbacks:
        try:
            api["off_theme_change"](_bridge_theme_change)
        except Exception:
            pass
        _bridge_registered = False


def register_theme_dir(directory: str) -> bool:
    path = Path(directory).expanduser().resolve(strict=False)
    if not (path.exists() and path.is_dir()):
        return False
    api = _mikomn_api()
    if api is None:
        return False
    api["register_theme_dir"](path)
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


def _get_config_dir() -> Path:
    config_home = os.environ.get("XDG_CONFIG_HOME", "")
    return (Path(config_home) / "bkx") if config_home else (Path.home() / ".config" / "bkx")


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
    try:
        config_dir = _get_config_dir(); config_dir.mkdir(parents=True, exist_ok=True)
        _get_theme_config_file().write_text(json.dumps({"theme": theme_name}, indent=2), encoding="utf-8")
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


def get_status_message(level: str = "info") -> tuple[str, str]:
    icon_map = {"info": "ℹ️", "success": "✅", "warning": "⚠️", "error": "❌"}
    style_map = {"info": info_style(), "success": success_style(), "warning": warning_style(), "error": error_style()}
    return icon_map.get(level, "ℹ️"), style_map.get(level, info_style())


def themed_message(message: str, level: str = "info") -> str:
    icon, style = get_status_message(level); return f"{icon} [{style}]{message}[/]"

def themed_header(title: str) -> str:
    return f"[bold {primary_color()}]{title}[/]"

def themed_prompt(prompt_text: str) -> str:
    return f"[bold {accent_color()}]{prompt_text}[/]"


_init_theme()
