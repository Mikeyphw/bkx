from __future__ import annotations

from mikomn.tui.rendering import RenderContext
from mikomn.tui.shell.base import render_component_text

from bkx.tui.app import _set_form_values, build_bkx_tui_app
from bkx.tui.state import TuiState


def test_all_routes_render_for_desktop_mobile_keyboard_and_wide() -> None:
    app = build_bkx_tui_app(state=TuiState())
    contexts = [
        RenderContext.desktop(width=120, height=32),
        RenderContext.termux(width=48, height=22),
        RenderContext.termux(width=48, height=14, keyboard_visible=True),
        RenderContext.wide(width=160, height=48),
    ]

    for route in [
        "/backup",
        "/archive",
        "/restore",
        "/transfer",
        "/tools",
        "/logs",
        "/run-history",
        "/help",
        "/notifications",
        "/diagnostics",
    ]:
        for context in contexts:
            tree = app.render_route_tree(route, context=context)
            assert tree.kind == "page"
            assert tree.props["mode"] == context.mode.value


def test_operation_pages_render_visible_input_summaries() -> None:
    app = build_bkx_tui_app(state=TuiState())

    backup_text = render_component_text(app.render_route("/backup"))
    assert "Current backup inputs" in backup_text
    assert "sources | ." in backup_text
    assert "Run backup" in backup_text

    transfer_text = render_component_text(app.render_route("/transfer"))
    assert "Current transfer inputs" in transfer_text
    assert "overwrite | False" in transfer_text
    assert "Run transfer" in transfer_text


def test_diagnostics_page_renders_platform_context() -> None:
    app = build_bkx_tui_app(state=TuiState())
    text = render_component_text(app.render_route("/diagnostics"))

    assert "Context" in text
    assert "platform" in text
    assert "termux" in text


def test_form_values_persist_into_state_and_rendered_pages() -> None:
    state = TuiState()
    app = build_bkx_tui_app(state=state)
    ctx = app.context()

    _set_form_values(ctx, "archive", {"sources": ".", "output": "/tmp/out.tar.zst"})

    assert state.form_values is not None
    assert state.form_values["archive"]["output"] == "/tmp/out.tar.zst"
    assert "/tmp/out.tar.zst" in render_component_text(app.render_route("/archive"))


def test_app_registers_navigation_and_primary_actions() -> None:
    app = build_bkx_tui_app(state=TuiState())
    action_ids = {action.id for action in app.context().actions.all()}

    assert "backup.create" in action_ids
    assert "archive.create" in action_ids
    assert "restore.extract" in action_ids
    assert "transfer.run" in action_ids
    assert "nav.backup" in action_ids
    assert "diagnostics.export" in action_ids
