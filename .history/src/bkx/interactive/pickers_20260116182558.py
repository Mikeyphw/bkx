"""File pickers using mikomn's FilePicker with Yazi/FZF fallback chain.

Contract rule:
- Optional external tools runtime-detected.
- yazi -> fzf -> rich picker -> numbered prompt.

This module uses mikomn's FilePicker which provides:
- YaziAdapter (primary, if available)
- FzfAdapter (fallback, if available)
- Rich console picker (final fallback)
"""

from __future__ import annotations

import shutil
from pathlib import Path
from typing import Optional

from mikomn.cli_base.pickers import FilePicker
from mikomn.cli_base.pickers.adapters import YaziAdapter, FzfAdapter
from mikomn.cli_base.interactive.models import PickOptions, SelectionMode


def _get_available_picker() -> Optional[str]:
    """Check which picker is available.
    
    Returns:
        "yazi", "fzf", "rich", or None if no pickers available
    """
    if shutil.which("yazi"):
        return "yazi"
    if shutil.which("fzf"):
        return "fzf"
    return "rich"


def pick_files(
    title: str = "Select files",
    allow_multi: bool = True,
    must_exist: bool = True,
    base_dir: Optional[Path] = None,
) -> list[Path]:
    """Pick files using the best available picker.
    
    Args:
        title: Title for the picker
        allow_multi: Allow multiple selections
        must_exist: Selected paths must exist
        base_dir: Base directory for the picker
        
    Returns:
        List of selected file paths (empty if cancelled)
    """
    picker_type = _get_available_picker()
    
    # Build pick options
    mode = SelectionMode.MULTI if allow_multi else SelectionMode.SINGLE
    options = PickOptions(
        title=title,
        mode=mode,
        must_exist=must_exist,
        base_dir=str(base_dir) if base_dir else None,
    )
    
    # Create adapters for fallback chain
    adapters = []
    if shutil.which("yazi"):
        adapters.append(YaziAdapter())
    if shutil.which("fzf"):
        adapters.append(FzfAdapter())
    
    # Create picker with adapters (falls back to rich if no adapters work)
    picker = FilePicker(options, *adapters)
    
    # Execute picker
    result = picker.pick()
    
    # Convert to Path objects
    if isinstance(result, str):
        return [Path(result)]
    elif isinstance(result, list):
        return [Path(p) for p in result]
    return []


def pick_directories(
    title: str = "Select directories",
    allow_multi: bool = True,
    must_exist: bool = True,
    base_dir: Optional[Path] = None,
) -> list[Path]:
    """Pick directories using the best available picker.
    
    Args:
        title: Title for the picker
        allow_multi: Allow multiple selections
        must_exist: Selected paths must exist
        base_dir: Base directory for the picker
        
    Returns:
        List of selected directory paths (empty if cancelled)
    """
    picker_type = _get_available_picker()
    
    # Build pick options for directories
    mode = SelectionMode.DIRECTORIES if allow_multi else SelectionMode.SINGLE_DIR
    options = PickOptions(
        title=title,
        mode=mode,
        must_exist=must_exist,
        base_dir=str(base_dir) if base_dir else None,
    )
    
    # Create adapters for fallback chain
    adapters = []
    if shutil.which("yazi"):
        adapters.append(YaziAdapter())
    if shutil.which("fzf"):
        adapters.append(FzfAdapter())
    
    # Create picker with adapters
    picker = FilePicker(options, *adapters)
    
    # Execute picker
    result = picker.pick()
    
    # Convert to Path objects
    if isinstance(result, str):
        return [Path(result)]
    elif isinstance(result, list):
        return [Path(p) for p in result]
    return []


def pick_file(
    title: str = "Select a file",
    must_exist: bool = True,
    base_dir: Optional[Path] = None,
) -> Optional[Path]:
    """Pick a single file.
    
    Args:
        title: Title for the picker
        must_exist: Selected path must exist
        base_dir: Base directory for the picker
        
    Returns:
        Selected file path or None if cancelled
    """
    results = pick_files(
        title=title,
        allow_multi=False,
        must_exist=must_exist,
        base_dir=base_dir,
    )
    return results[0] if results else None


def pick_directory(
    title: str = "Select a directory",
    must_exist: bool = True,
    base_dir: Optional[Path] = None,
) -> Optional[Path]:
    """Pick a single directory.
    
    Args:
        title: Title for the picker
        must_exist: Selected path must exist
        base_dir: Base directory for the picker
        
    Returns:
        Selected directory path or None if cancelled
    """
    results = pick_directories(
        title=title,
        allow_multi=False,
        must_exist=must_exist,
        base_dir=base_dir,
    )
    return results[0] if results else None


# Convenience function to show picker info
def get_picker_info() -> dict:
    """Get information about available pickers.
    
    Returns:
        Dict with picker availability info
    """
    return {
        "yazi_available": bool(shutil.which("yazi")),
        "fzf_available": bool(shutil.which("fzf")),
        "active_picker": _get_available_picker(),
    }

