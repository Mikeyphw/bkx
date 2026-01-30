"""CSS path registry for BKX TUI."""

from __future__ import annotations

from pathlib import Path
from typing import Iterable


def get_css_paths() -> list[str]:
    """Return ordered CSS paths for BKX TUI.
    
    Returns:
        List of CSS file paths in load order.
    """
    base_dir = Path(__file__).parent
    
    css_paths: list[str] = [
        # Panes
        str(base_dir / "panes" / "status_cards.tcss"),
        str(base_dir / "panes" / "theme_settings.tcss"),
        str(base_dir / "panes" / "tools_dashboard.tcss"),
        str(base_dir / "panes" / "empty_state.tcss"),
        str(base_dir / "panes" / "log.tcss"),
        str(base_dir / "panes" / "status.tcss"),
        str(base_dir / "panes" / "progress.tcss"),
        str(base_dir / "panes" / "status_bar.tcss"),
        str(base_dir / "panes" / "transfer_progress.tcss"),
        str(base_dir / "panes" / "archive_list.tcss"),
        str(base_dir / "panes" / "archive_preview.tcss"),
        str(base_dir / "panes" / "navigation.tcss"),
        # Screens
        str(base_dir / "screens" / "restore.tcss"),
        str(base_dir / "screens" / "backup.tcss"),
        str(base_dir / "screens" / "archive.tcss"),
        str(base_dir / "screens" / "transfer.tcss"),
        str(base_dir / "screens" / "tools.tcss"),
        str(base_dir / "screens" / "logs.tcss"),
    ]
    
    return css_paths


def load_css(app, *, extra_paths: Iterable[str] | None = None) -> None:
    """Load base and optional extra CSS into the Textual app.
    
    Args:
        app: Textual app instance
        extra_paths: Optional additional CSS paths to load
    """
    for path in get_css_paths():
        app.stylesheet.read(path)
    
    if extra_paths:
        for path in extra_paths:
            app.stylesheet.read(path)


def reload_css(app, *, extra_paths: Iterable[str] | None = None) -> None:
    """Reload all CSS for the app.
    
    Args:
        app: Textual app instance
        extra_paths: Optional additional CSS paths to load
    """
    paths = list(get_css_paths())
    if extra_paths:
        paths.extend(list(extra_paths))
    app.stylesheet.read_all(paths)


def load_bkx_css(app) -> None:
    """Load BKX-specific CSS after parent CSS is loaded.
    
    This is called after mikomn's BaseTuiApp.on_mount() to add
    BKX-specific styles on top of the base styles.
    
    Args:
        app: Textual app instance
    """
    for path in get_css_paths():
        try:
            app.stylesheet.read(path)
        except Exception:
            # Skip files that fail to load
            pass


def reload_bkx_css(app) -> None:
    """Reload BKX-specific CSS.
    
    Args:
        app: Textual app instance
    """
    app.stylesheet.read_all(get_css_paths())


__all__ = [
    "get_css_paths",
    "load_css",
    "reload_css",
    "load_bkx_css",
    "reload_bkx_css",
]

