from __future__ import annotations

import inspect
import re
import sys
from pathlib import Path


def _add_repo_src_to_path() -> None:
    root = Path(__file__).resolve().parent

    candidates = [
        root / "src",
        root / "bkx" / "src",
    ]

    for c in candidates:
        if (c / "bkx" / "__init__.py").exists():
            sys.path.insert(0, str(c))
            return

    # Fallback: search one level deep for "src/bkx/__init__.py"
    for c in root.glob("**/src"):
        if (c / "bkx" / "__init__.py").exists():
            sys.path.insert(0, str(c))
            return

    raise AssertionError("Could not locate src/ directory containing bkx package")


def test_tui_init_is_import_light() -> None:
    txt = Path("src/bkx/tui/__init__.py").read_text(encoding="utf-8")
    assert "from bkx.tui import app" not in txt
    assert re.search(r"(?m)^\s*(from|import)\s+textual\b", txt) is None


def test_bkx_importable() -> None:
    _add_repo_src_to_path()
    import bkx  # noqa: F401


def test_app_keyword_only_init() -> None:
    _add_repo_src_to_path()
    from bkx.tui.app import BkxTuiApp  # noqa: F401

    sig = inspect.signature(BkxTuiApp.__init__)
    params = list(sig.parameters.values())

    assert params and params[0].name == "self"
    assert all(p.kind != inspect.Parameter.VAR_POSITIONAL for p in params), "no *args allowed"
    assert any(p.kind == inspect.Parameter.VAR_KEYWORD for p in params), "must accept **kwargs"


def test_step3_intents_present() -> None:
    _add_repo_src_to_path()
    from bkx.tui import messages as m

    for name in [
        "ShowCommandPalette",
        "OpenNotificationCenter",
        "ReloadCss",
        "ClearRunLog",
        "ToggleToasts",
        "ToggleDebugOverlay",
        "DumpDebugSnapshot",
        "SetTheme",
    ]:
        assert hasattr(m, name), f"missing {name}"


def test_step3_dispatch_present_in_app_source() -> None:
    txt = Path("src/bkx/tui/app.py").read_text(encoding="utf-8")

    # Keep these checks lightweight: verify intent routing exists.
    for frag in [
        "def on_intent",
        "ShowCommandPalette",
        "OpenNotificationCenter",
        "ReloadCss",
        "ClearRunLog",
        "ToggleToasts",
        "ToggleDebugOverlay",
        "DumpDebugSnapshot",
        "SetTheme",
    ]:
        assert frag in txt


def test_tcss_validator_runs_on_bkx_css() -> None:
    _add_repo_src_to_path()

    try:
        from mikomn_tools.core.validate import validate_path
    except Exception as e:
        raise AssertionError(f"mikomn_tools.core.validate not importable: {e}") from e

    css_dir = Path("src/bkx/tui/css")
    assert css_dir.exists(), "expected src/bkx/tui/css to exist"
    report = validate_path(css_dir)
    assert getattr(report, "error_count", 0) == 0
