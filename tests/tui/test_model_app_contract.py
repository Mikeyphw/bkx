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


def test_shared_renderer_commits_form_edits_to_bkx_state(tmp_path, monkeypatch) -> None:
    import asyncio

    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    state = TuiState(last_route="backup")
    app = build_bkx_tui_app(state=state)

    asyncio.run(app.commit_form_values("/backup", {"sources": "/srv/data", "verify": False}))

    assert state.form_values is not None
    assert state.form_values["backup"] == {"sources": "/srv/data", "verify": False}
    assert (tmp_path / "bkx" / "tui_state.json").exists()


def test_bkx_delegates_to_mikomn_runtime_without_parallel_shell() -> None:
    import inspect

    from bkx.tui.app import _run_bkx_textual_shell

    source = inspect.getsource(_run_bkx_textual_shell)
    assert "app.run()" in source
    assert "from textual" not in source
    assert "render_component_text" not in source


def test_root_alias_is_hidden_from_shared_navigation() -> None:
    app = build_bkx_tui_app(state=TuiState())
    visible_routes = {spec.route for spec in app.context().routes.visible()}

    assert "/" not in visible_routes
    assert "/backup" in visible_routes
