"""Theme management for bkx using mikomn's unified theme system.

This module integrates mikomn's theme capabilities for consistent styling
across CLI (Rich), TUI (Textual), and any other UI components.

Features:
- Theme loading and caching
- Theme switching and cycling
- Custom theme directory support
- Theme preference persistence
- Theme-aware status messages for interactive mode
- Integration with mikomn's CLI prompts and TUI widgets

Related:
- mikomn.cli_base.interactive: Theme-aware prompts
- mikomn.cli_base.menus: Themed menu rendering
- mikomn.tui_base: Textual TUI integration
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Callable

from mikomn import (
    load_theme,
    get_theme_names as _get_theme_names,
    get_default_theme,
    on_theme_change as _on_theme_change,
    off_theme_change as _off_theme_change,
    register_theme_dir as _register_theme_dir,
)
from mikomn.rich import generate_rich_styles

# Default theme for bkx
DEFAULT_THEME = "sweet-dracula"

# Environment variable for theme override
_THEME_ENV = "BKX_THEME"

# Environment variable for custom theme directory
_THEME_DIR_ENV = "BKX_THEME_DIR"

# Environment variable for persisting theme preference
_THEME_PERSIST_ENV = "BKX_PERSIST_THEME"

# Cache for loaded theme
_current_theme: str = DEFAULT_THEME
_theme_cache: dict[str, Any] = {}
_rich_styles_cache: dict[str, str] = {}
_custom_theme_dirs: list[str] = []
_theme_persist_enabled: bool = False


def _init_theme() -> None:
    """Initialize theme from environment or default."""
    global _current_theme
    env_theme = os.environ.get(_THEME_ENV)
    if env_theme:
        _current_theme = env_theme
    else:
        _current_theme = DEFAULT_THEME


def get_current_theme_name() -> str:
    """Get the currently active theme name."""
    return _current_theme


def get_active_theme() -> dict[str, Any]:
    """Get the currently active theme dictionary.
    
    Returns:
        Theme dict with colors, styles, and metadata
    """
    global _theme_cache, _rich_styles_cache
    if not _theme_cache:
        _theme_cache = load_theme(_current_theme) or {}
        _rich_styles_cache = generate_rich_styles(_current_theme) or {}
    return _theme_cache


def get_rich_styles() -> dict[str, str]:
    """Get Rich-compatible styles for the active theme.
    
    Returns:
        Dict mapping style names to Rich markup strings
    """
    global _rich_styles_cache
    if not _rich_styles_cache:
        _rich_styles_cache = generate_rich_styles(_current_theme) or {}
    return _rich_styles_cache


def set_theme(name: str) -> bool:
    """Set the active theme by name.
    
    Args:
        name: Theme name to activate
        
    Returns:
        True if theme was loaded successfully, False otherwise
    """
    global _current_theme, _theme_cache, _rich_styles_cache
    theme = load_theme(name)
    if theme:
        _current_theme = name
        _theme_cache = theme
        _rich_styles_cache = generate_rich_styles(name) or {}
        _notify_theme_change(name)
        return True
    return False


def cycle_theme() -> str:
    """Cycle to the next available theme.
    
    Returns:
        Name of the newly active theme
    """
    global _current_theme, _theme_cache, _rich_styles_cache
    names = get_all_theme_names()
    if not names:
        return _current_theme
    try:
        idx = names.index(_current_theme)
        _current_theme = names[(idx + 1) % len(names)]
    except ValueError:
        _current_theme = names[0]
    _theme_cache.clear()
    _rich_styles_cache.clear()
    _notify_theme_change(_current_theme)
    return _current_theme


def get_all_theme_names() -> list[str]:
    """Get list of available theme names.
    
    Returns:
        List of theme names registered with mikomn
    """
    return _get_theme_names()


# Alias for backward compatibility
def get_theme_names() -> list[str]:
    """Get list of available theme names (alias for get_all_theme_names)."""
    return get_all_theme_names()


def reload_theme() -> None:
    """Reload the current theme (useful after theme dir changes)."""
    global _theme_cache, _rich_styles_cache
    _theme_cache = load_theme(_current_theme) or {}
    _rich_styles_cache = generate_rich_styles(_current_theme) or {}


# Convenience color accessors
def primary_color() -> str:
    """Get primary accent color.
    
    Returns:
        Hex color string (e.g., "#3B82F6")
    """
    colors = get_active_theme().get("colors", {})
    return colors.get("primary", "#3B82F6")


def secondary_color() -> str:
    """Get secondary accent color.
    
    Returns:
        Hex color string (e.g., "#8B5CF6")
    """
    colors = get_active_theme().get("colors", {})
    return colors.get("secondary", "#8B5CF6")


def accent_color() -> str:
    """Get selection/highlight color.
    
    Returns:
        Hex color string (e.g., "#F59E0B")
    """
    colors = get_active_theme().get("colors", {})
    return colors.get("accent", "#F59E0B")


def foreground_color() -> str:
    """Get main foreground/text color.
    
    Returns:
        Hex color string (e.g., "#F9FAFB")
    """
    colors = get_active_theme().get("colors", {})
    return colors.get("foreground", "#F9FAFB")


def background_color() -> str:
    """Get background color.
    
    Returns:
        Hex color string (e.g., "#1A1A2E")
    """
    colors = get_active_theme().get("colors", {})
    return colors.get("background", "#1A1A2E")


def surface_color() -> str:
    """Get surface/panel background color.
    
    Returns:
        Hex color string (e.g., "#16213E")
    """
    colors = get_active_theme().get("colors", {})
    return colors.get("surface", "#16213E")


def success_color() -> str:
    """Get success state color.
    
    Returns:
        Hex color string (e.g., "#10B981")
    """
    colors = get_active_theme().get("colors", {})
    return colors.get("success", "#10B981")


def warning_color() -> str:
    """Get warning state color.
    
    Returns:
        Hex color string (e.g., "#F59E0B")
    """
    colors = get_active_theme().get("colors", {})
    return colors.get("warning", "#F59E0B")


def error_color() -> str:
    """Get error state color.
    
    Returns:
        Hex color string (e.g., "#EF4444")
    """
    colors = get_active_theme().get("colors", {})
    return colors.get("error", "#EF4444")


def foreground_muted_color() -> str:
    """Get muted foreground color.
    
    Returns:
        Hex color string (e.g., "#9CA3AF")
    """
    colors = get_active_theme().get("colors", {})
    text_colors = colors.get("text", {})
    return text_colors.get("foreground-muted", "#9CA3AF")


# Rich style helpers
def success_style() -> str:
    """Get Rich style string for success messages."""
    return get_rich_styles().get("success", f"[{success_color()}]")


def warning_style() -> str:
    """Get Rich style string for warning messages."""
    return get_rich_styles().get("warning", f"[{warning_color()}]")


def error_style() -> str:
    """Get Rich style string for error messages."""
    return get_rich_styles().get("error", f"[{error_color()}]")


def info_style() -> str:
    """Get Rich style string for info messages."""
    return get_rich_styles().get("info", f"[{foreground_color()}]")


def primary_style() -> str:
    """Get Rich style string for primary elements."""
    return f"[bold {primary_color()}]"


def accent_style() -> str:
    """Get Rich style string for accent elements."""
    return f"[bold {accent_color()}]"


# Theme change callbacks
_on_theme_change_callbacks: list[Callable[[str], None]] = []


def on_theme_change(callback: Callable[[str], None]) -> None:
    """Register a callback to be called when the theme changes.
    
    Args:
        callback: Function that receives the new theme name
    """
    _on_theme_change_callbacks.append(callback)
    _on_theme_change(callback)


def off_theme_change(callback: Callable[[str], None]) -> None:
    """Unregister a theme change callback.
    
    Args:
        callback: Function to remove from callbacks
    """
    if callback in _on_theme_change_callbacks:
        _on_theme_change_callbacks.remove(callback)
    _off_theme_change(callback)


def _notify_theme_change(new_theme: str) -> None:
    """Internal: notify all registered callbacks of theme change."""
    for callback in _on_theme_change_callbacks:
        try:
            callback(new_theme)
        except Exception:
            pass  # Don't let callback errors break theme switching


# Initialize on module load
_init_theme()

