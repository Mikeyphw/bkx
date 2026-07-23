from __future__ import annotations

import inspect

from mikomn.tui.rendering import RenderContext
from mikomn.tui.shell.base import create_textual_app, render_component_text

from bkx.tui.app import _run_bkx_textual_shell, build_bkx_tui_app
from bkx.tui.state import TuiState


def test_mikomn_interactive_renderer_is_the_runtime_delegate() -> None:
    source = inspect.getsource(create_textual_app)

    assert "mikomn.tui.shell.interactive" in source
    assert "return create_runtime(app)" in source


def test_bkx_runtime_stays_thin_and_delegates_to_mikomn() -> None:
    source = inspect.getsource(_run_bkx_textual_shell)

    assert "app.run()" in source
    assert "from textual" not in source
    assert "Static(" not in source
    assert "render_component_text" not in source


def test_bkx_routes_emit_real_render_nodes_for_shared_renderer() -> None:
    app = build_bkx_tui_app(state=TuiState())
    tree = app.render_route_tree("/backup", context=RenderContext.desktop(width=128, height=36))
    kinds = {node.kind for node in tree.walk()}

    assert tree.kind == "page"
    assert "form" in kinds
    assert "field" in kinds
    assert "action-bar" in kinds
    assert "table" in kinds


def test_text_fallback_remains_diagnostics_only() -> None:
    app = build_bkx_tui_app(state=TuiState())
    text = render_component_text(app.render_route("/backup"))

    assert "Current backup inputs" in text
    assert "Run backup" in text
