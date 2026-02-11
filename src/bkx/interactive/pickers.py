"""File pickers using mikomn's picker chain (yazi -> fzf -> manual).

bkx wraps mikomn's ``FilePicker`` and normalizes return types.

Notes:
- mikomn SelectionMode (v0.3+) does not currently include single-file/dir kinds.
  bkx emulates single selection by returning the first result.
- When ``must_exist=False``, bkx falls back to prompt-based input because picker
  backends inherently enumerate existing filesystem entries.
"""

from __future__ import annotations

import shutil
from pathlib import Path

from mikomn_cli.base.interactive.models import PickerType, PickOptions, SelectionMode
from mikomn_cli.base.interactive.prompts import ask_path, ask_text
from mikomn_cli.base.pickers import FilePicker
from mikomn_cli.base.pickers.adapters import FzfAdapter, YaziAdapter
from mikomn_cli.base.ui2.exceptions import NonInteractiveInput, UserCancelled


def _preferred_picker() -> PickerType:
    if shutil.which("yazi"):
        return PickerType.YAZI
    if shutil.which("fzf"):
        return PickerType.FZF
    return PickerType.MANUAL


def _maybe_adapter(adapter_cls):
    adapter = adapter_cls()
    return adapter if adapter.is_available() else None


def pick_files(
    title: str = "Select files",
    allow_multi: bool = True,
    must_exist: bool = True,
    base_dir: Path | None = None,
) -> list[Path]:
    """Pick file paths.

    Returns an empty list if cancelled or non-interactive.
    """
    if not must_exist:
        # Picker backends enumerate existing entries; use prompt input instead.
        try:
            raw = ask_text(f"{title} (comma-separated paths)", default="")
        except (UserCancelled, NonInteractiveInput):
            return []
        paths = [Path(p.strip()).expanduser() for p in raw.split(",") if p.strip()]
        return paths if allow_multi else paths[:1]

    options = PickOptions(
        title=title,
        base_path=base_dir or Path("."),
        mode=SelectionMode.FILES_ONLY,
        picker=_preferred_picker(),
    )
    picker = FilePicker(
        options,
        yazi=_maybe_adapter(YaziAdapter),
        fzf=_maybe_adapter(FzfAdapter),
    )
    try:
        results = picker.pick()
    except (UserCancelled, NonInteractiveInput):
        return []
    return results if allow_multi else results[:1]


def pick_directories(
    title: str = "Select directories",
    allow_multi: bool = True,
    must_exist: bool = True,
    base_dir: Path | None = None,
) -> list[Path]:
    """Pick directory paths.

    Returns an empty list if cancelled or non-interactive.
    """
    if not must_exist:
        try:
            raw = ask_text(f"{title} (comma-separated paths)", default="")
        except (UserCancelled, NonInteractiveInput):
            return []
        paths = [Path(p.strip()).expanduser() for p in raw.split(",") if p.strip()]
        return paths if allow_multi else paths[:1]

    options = PickOptions(
        title=title,
        base_path=base_dir or Path("."),
        mode=SelectionMode.DIRS_ONLY,
        picker=_preferred_picker(),
    )
    picker = FilePicker(
        options,
        yazi=_maybe_adapter(YaziAdapter),
        fzf=_maybe_adapter(FzfAdapter),
    )
    try:
        results = picker.pick()
    except (UserCancelled, NonInteractiveInput):
        return []
    return results if allow_multi else results[:1]


def pick_file(
    title: str = "Select a file",
    must_exist: bool = True,
    base_dir: Path | None = None,
) -> Path | None:
    """Pick a single file path."""
    results = pick_files(title=title, allow_multi=False, must_exist=must_exist, base_dir=base_dir)
    return results[0] if results else None


def pick_directory(
    title: str = "Select a directory",
    must_exist: bool = True,
    base_dir: Path | None = None,
) -> Path | None:
    """Pick a single directory path."""
    results = pick_directories(title=title, allow_multi=False, must_exist=must_exist, base_dir=base_dir)
    return results[0] if results else None


def prompt_for_path(
    title: str,
    default: Path | None = None,
    must_exist: bool = True,
) -> Path | None:
    """Prompt for a path (supports must_exist=False)."""
    try:
        return ask_path(title, default=default, must_exist=must_exist)
    except (UserCancelled, NonInteractiveInput):
        return None
