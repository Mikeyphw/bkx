"""Model-first BKX TUI built on the current ``mikomn.tui`` API."""

from __future__ import annotations

import inspect
from pathlib import Path
from typing import Any

from mikomn.tui import TuiApp, ui
from mikomn.tui.app.context import AppContext
from mikomn.tui.app.services import Notification

from bkx.cli.spec_builders import (
    build_archive_spec,
    build_backup_spec,
    build_restore_spec,
    build_transfer_spec,
)
from bkx.core.errors import BkxError
from bkx.core.services import (
    get_archive_service,
    get_backup_service,
    get_restore_service,
    get_transfer_service,
)
from bkx.tui.state import TuiState, load_state, save_state
from bkx.tui.support_bundle import export_support_bundle
from bkx.ui.theme import get_current_theme_name, set_theme

ROUTES: tuple[tuple[str, str, str, str], ...] = (
    ("/backup", "Backup", "Main", "Create a backup archive"),
    ("/archive", "Archive", "Main", "Create or inspect archives"),
    ("/restore", "Restore", "Main", "Restore an archive"),
    ("/transfer", "Transfer", "Main", "Copy archives between locations"),
    ("/tools", "Tools", "System", "Settings and environment status"),
    ("/logs", "Logs", "System", "Recent notifications and task logs"),
    ("/run-history", "Run History", "System", "Saved operation history"),
    ("/help", "Help", "System", "Generated command help"),
    ("/notifications", "Notifications", "System", "Notification center"),
    ("/diagnostics", "Diagnostics", "System", "Debug payload and support bundle"),
)


class BkxTuiApp(TuiApp):
    """Concrete BKX TUI app type for callers that import the old name."""

    def __init__(self, *, state: TuiState | None = None) -> None:
        super().__init__(
            "BKX",
            app_id="dev.bkx.tui",
            termux_first=True,
            state=state or load_state(),
        )


def _state(ctx: AppContext) -> TuiState:
    if isinstance(ctx.state, TuiState):
        return ctx.state
    return TuiState()


def _form_values(ctx: AppContext, name: str) -> dict[str, Any]:
    values = getattr(_state(ctx), "form_values", None)
    if isinstance(values, dict):
        value = values.get(name, {})
        return value if isinstance(value, dict) else {}
    raw = ctx.settings.get(f"{name}.form", {})
    return raw if isinstance(raw, dict) else {}


def _set_form_values(ctx: AppContext, name: str, values: dict[str, Any]) -> None:
    state = _state(ctx)
    if state.form_values is None:
        state.form_values = {}
    state.form_values[name] = dict(values)
    if isinstance(ctx.state, TuiState):
        ctx.state.form_values = state.form_values
        save_state(ctx.state)


def _field_summary(title: str, values: dict[str, Any], defaults: dict[str, Any]) -> ui.Table:
    rows = [
        {
            "field": key.replace("_", " "),
            "value": str(values.get(key, default) if values.get(key, default) not in (None, "") else "(empty)"),
        }
        for key, default in defaults.items()
    ]
    return ui.Table.from_rows(rows, title=title, compact_as_cards=True)


async def _notify(ctx: AppContext, level: str, message: str, **meta: Any) -> Notification:
    notifier = getattr(ctx.notify, level, None)
    if callable(notifier):
        return await notifier(message, source="bkx", **meta)
    return await ctx.notify.info(message, source="bkx", **meta)


def _coerce_paths(value: Any, default: tuple[Path, ...] = ()) -> list[Path]:
    if value in (None, "", (), []):
        return list(default)
    if isinstance(value, (str, Path)):
        parts = str(value).split()
    else:
        parts = [str(item) for item in value]
    return [Path(part).expanduser() for part in parts if part]


def _coerce_path(value: Any, default: Path | None = None) -> Path | None:
    if value in (None, ""):
        return default
    return Path(str(value)).expanduser()


def _coerce_words(value: Any) -> list[str]:
    if value in (None, "", (), []):
        return []
    if isinstance(value, str):
        return [part for part in value.split() if part]
    return [str(item) for item in value]


async def _run_service_action(ctx: AppContext, title: str, func) -> None:
    async def _runner():
        value = func()
        if inspect.isawaitable(value):
            return await value
        return value

    result = await ctx.tasks.run_callable(title, _runner)
    if result.status.value == "succeeded":
        await _notify(ctx, "success", f"{title} complete")
    else:
        await _notify(ctx, "error", f"{title} failed: {result.error or result.status.value}")


def _backup_page(ctx: AppContext):
    values = _form_values(ctx, "backup")
    defaults = {
        "sources": ".",
        "destination": "",
        "archive_name": "",
        "excludes": "*.pyc __pycache__",
        "algorithm": "zstd",
        "level": 3,
        "verify": True,
    }
    return ui.Page(
        ui.Title("Backup", "Create a verified archive from one or more source paths."),
        _field_summary("Current backup inputs", values, defaults),
        ui.Form(
            ui.PathField("sources", "Sources", mode="any", value=values.get("sources", "."), required=True),
            ui.PathField("destination", "Destination", mode="directory", value=values.get("destination")),
            ui.TextField("archive_name", "Archive name", value=values.get("archive_name"), placeholder="auto-generated"),
            ui.TextField("excludes", "Excludes", value=values.get("excludes", "*.pyc __pycache__")),
            ui.SelectField(
                "algorithm", "Compression", options=("zstd", "gzip", "lz4", "xz", "bzip2"), value=values.get("algorithm", "zstd")
            ),
            ui.NumberField("level", "Level", min_value=1, max_value=22, value=values.get("level", 3)),
            ui.ToggleField("verify", "Verify after creation", value=bool(values.get("verify", True))),
            title="Backup plan",
            submit_action="backup.create",
        ),
        actions=(ui.Action("backup.create", "Run backup", variant="primary"),),
        title="Backup",
    )


def _archive_page(ctx: AppContext):
    values = _form_values(ctx, "archive")
    defaults = {
        "sources": ".",
        "output": "",
        "excludes": "*.pyc __pycache__",
        "algorithm": "zstd",
        "verify": True,
    }
    return ui.Page(
        ui.Title("Archive", "Create a standalone archive file."),
        _field_summary("Current archive inputs", values, defaults),
        ui.Form(
            ui.PathField("sources", "Sources", mode="any", value=values.get("sources", "."), required=True),
            ui.PathField("output", "Output archive", mode="file", value=values.get("output"), required=True),
            ui.TextField("excludes", "Excludes", value=values.get("excludes", "*.pyc __pycache__")),
            ui.SelectField("algorithm", "Compression", options=("zstd", "gzip", "xz"), value=values.get("algorithm", "zstd")),
            ui.ToggleField("verify", "Verify after creation", value=bool(values.get("verify", True))),
            title="Archive plan",
            submit_action="archive.create",
        ),
        actions=(ui.Action("archive.create", "Create archive", variant="primary"),),
        title="Archive",
    )


def _restore_page(ctx: AppContext):
    values = _form_values(ctx, "restore")
    defaults = {
        "archive": "",
        "destination": "",
        "overwrite": False,
        "fresh_dir": False,
    }
    return ui.Page(
        ui.Title("Restore", "Extract an archive into a destination directory."),
        _field_summary("Current restore inputs", values, defaults),
        ui.Form(
            ui.PathField("archive", "Archive", mode="file", value=values.get("archive"), required=True),
            ui.PathField("destination", "Destination", mode="directory", value=values.get("destination")),
            ui.ToggleField("overwrite", "Overwrite existing files", value=bool(values.get("overwrite", False))),
            ui.ToggleField("fresh_dir", "Restore into fresh directory", value=bool(values.get("fresh_dir", False))),
            title="Restore plan",
            submit_action="restore.extract",
        ),
        actions=(ui.Action("restore.extract", "Restore", variant="primary"),),
        title="Restore",
    )


def _transfer_page(ctx: AppContext):
    values = _form_values(ctx, "transfer")
    defaults = {
        "source": "",
        "destination": "",
        "direction": "push",
        "overwrite": False,
    }
    return ui.Page(
        ui.Title("Transfer", "Push or pull files between local paths."),
        _field_summary("Current transfer inputs", values, defaults),
        ui.Form(
            ui.PathField("source", "Source", mode="any", value=values.get("source"), required=True),
            ui.PathField("destination", "Destination", mode="any", value=values.get("destination"), required=True),
            ui.SelectField("direction", "Direction", options=("push", "pull"), value=values.get("direction", "push")),
            ui.ToggleField("overwrite", "Overwrite existing files", value=bool(values.get("overwrite", False))),
            title="Transfer plan",
            submit_action="transfer.run",
        ),
        actions=(ui.Action("transfer.run", "Run transfer", variant="primary"),),
        title="Transfer",
    )


def _tools_page(ctx: AppContext):
    return ui.Page(
        ui.Title("Tools", "BKX runtime settings"),
        ui.DashboardGrid(
            ui.StatCard("Theme", get_current_theme_name()),
            ui.StatCard("Notifications", len(ctx.notify.items())),
            ui.StatCard("Tasks", len(ctx.tasks.all())),
            title="Status",
        ),
        ui.ActionBar(
            ui.Action("theme.next", "Next theme"),
            ui.Action("diagnostics.export", "Export support bundle"),
        ),
        title="Tools",
    )


def _logs_page(ctx: AppContext):
    rows = [item.as_dict() for item in ctx.notify.items()]
    return ui.Page(
        ui.Title("Logs", "Recent notifications"),
        ui.Table.from_rows(rows, title="Notifications")
        if rows
        else ui.EmptyState("No notifications", "Run an action to create log entries."),
        actions=(ui.Action("notifications.clear", "Clear notifications"),),
        title="Logs",
    )


def _history_page(ctx: AppContext):
    rows = [
        {
            "id": task.id,
            "title": task.title,
            "status": task.status.value,
            "progress": f"{task.progress:.0%}",
        }
        for task in ctx.tasks.all()
    ]
    return ui.Page(
        ui.Title("Run History", "Task records for this TUI session"),
        ui.Table.from_rows(rows, title="Tasks") if rows else ui.EmptyState("No runs yet", "Run a backup, archive, restore, or transfer."),
        title="Run History",
    )


def _help_page(ctx: AppContext):
    entries = [
        {"id": action.id, "title": action.title, "category": action.category} for action in ctx.actions.all() if action.is_visible(ctx)
    ]
    return ui.Page(ui.Title("Help"), ui.Table.from_rows(entries, title="Actions"), title="Help")


def _notifications_page(ctx: AppContext):
    return ui.Page(
        ui.Title("Notifications"),
        ui.NotificationCenter(),
        ui.Table.from_rows(ctx.notify.export_json(), title="History") if ctx.notify.items() else ui.EmptyState("No notifications"),
        title="Notifications",
    )


def _diagnostics_page(ctx: AppContext):
    rows = [
        {"key": "app", "value": ctx.app.name},
        {"key": "route", "value": ctx.nav.current},
        {"key": "theme", "value": get_current_theme_name()},
        {"key": "platform", "value": ctx.platform.env_name},
        {"key": "os", "value": ctx.platform.os_name},
        {"key": "termux", "value": str(ctx.platform.is_termux)},
    ]
    return ui.Page(
        ui.Title("Diagnostics"),
        ui.Table.from_rows(rows, title="Context"),
        ui.ActionBar(ui.Action("diagnostics.export", "Export support bundle", variant="primary")),
        title="Diagnostics",
    )


async def _backup_action(ctx: AppContext) -> None:
    values = _form_values(ctx, "backup")

    def run():
        spec = build_backup_spec(
            sources=_coerce_paths(values.get("sources"), (Path("."),)),
            destination=_coerce_path(values.get("destination")),
            archive_name=values.get("archive_name") or None,
            excludes=_coerce_words(values.get("excludes")),
            algorithm=str(values.get("algorithm") or "zstd"),
            level=int(values.get("level") or 3),
            verify=bool(values.get("verify", True)),
        )
        return get_backup_service().create(spec)

    await _run_service_action(ctx, "Backup", run)


async def _archive_action(ctx: AppContext) -> None:
    values = _form_values(ctx, "archive")

    def run():
        output = _coerce_path(values.get("output"))
        if output is None:
            raise BkxError("Archive output is required")
        spec = build_archive_spec(
            sources=_coerce_paths(values.get("sources"), (Path("."),)),
            output=output,
            excludes=_coerce_words(values.get("excludes")),
            algorithm=str(values.get("algorithm") or "zstd"),
            verify=bool(values.get("verify", True)),
        )
        return get_archive_service().create(spec)

    await _run_service_action(ctx, "Archive", run)


async def _restore_action(ctx: AppContext) -> None:
    values = _form_values(ctx, "restore")

    def run():
        archive = _coerce_path(values.get("archive"))
        if archive is None:
            raise BkxError("Archive path is required")
        spec = build_restore_spec(
            archive=archive,
            destination=_coerce_path(values.get("destination")),
            overwrite=bool(values.get("overwrite", False)),
            fresh_dir=bool(values.get("fresh_dir", False)),
        )
        return get_restore_service().extract(spec)

    await _run_service_action(ctx, "Restore", run)


async def _transfer_action(ctx: AppContext) -> None:
    values = _form_values(ctx, "transfer")

    def run():
        source = _coerce_path(values.get("source"))
        destination = _coerce_path(values.get("destination"))
        if source is None or destination is None:
            raise BkxError("Source and destination are required")
        spec = build_transfer_spec(
            source=source,
            destination=destination,
            direction=str(values.get("direction") or "push"),
            overwrite=bool(values.get("overwrite", False)),
        )
        service = get_transfer_service()
        transfer = getattr(service, "transfer", None) or getattr(service, "run", None)
        if callable(transfer):
            return transfer(spec)
        raise BkxError("Transfer service does not expose a transfer method")

    await _run_service_action(ctx, "Transfer", run)


async def _set_theme_action(ctx: AppContext, theme_name: str) -> None:
    if not set_theme(theme_name):
        await _notify(ctx, "warning", f"Unknown theme: {theme_name}")
        return
    await ctx.theme.set(theme_name)
    await _notify(ctx, "success", f"Theme switched to {theme_name}")


async def _export_support_bundle(ctx: AppContext) -> None:
    path, message = export_support_bundle()
    if path is not None and path.exists():
        ctx.diagnostics.record("info", "support bundle exported", source="bkx", details={"path": str(path)})
        await _notify(ctx, "success", message)
    else:
        await _notify(ctx, "error", message)


def _register_screens(app: TuiApp) -> None:
    app.screen("/", title="Backup", category="Main", description="Default BKX screen", hidden=True)(_backup_page)
    factories = {
        "/backup": _backup_page,
        "/archive": _archive_page,
        "/restore": _restore_page,
        "/transfer": _transfer_page,
        "/tools": _tools_page,
        "/logs": _logs_page,
        "/run-history": _history_page,
        "/help": _help_page,
        "/notifications": _notifications_page,
        "/diagnostics": _diagnostics_page,
    }
    for route, title, category, description in ROUTES:
        app.screen(route, title=title, category=category, description=description)(factories[route])


def _register_actions(app: TuiApp) -> None:
    app.action("backup.create", title="Run backup", keys=("1",), category="Backup")(_backup_action)
    app.action("archive.create", title="Create archive", keys=("2",), category="Archive")(_archive_action)
    app.action("restore.extract", title="Restore archive", keys=("3",), category="Restore")(_restore_action)
    app.action("transfer.run", title="Run transfer", keys=("4",), category="Transfer")(_transfer_action)
    app.action("diagnostics.export", title="Export support bundle", category="Diagnostics")(_export_support_bundle)

    for route, title, category, _description in ROUTES:
        action_id = f"nav{route.replace('/', '.')}"

        async def go(ctx: AppContext, target: str = route) -> None:
            ctx.nav.go(target)

        app.action(action_id, title=f"Go to {title}", category=category)(go)

    from bkx.ui.theme import get_theme_names

    for theme_name in get_theme_names():

        async def set_named_theme(ctx: AppContext, name: str = theme_name) -> None:
            await _set_theme_action(ctx, name)

        app.action(f"theme.set.{theme_name}", title=f"Set theme: {theme_name}", category="Theme")(set_named_theme)


def build_bkx_tui_app(*, state: TuiState | None = None) -> TuiApp:
    """Build the BKX TUI using the current public ``mikomn.tui`` API."""
    app = BkxTuiApp(state=state)
    _register_screens(app)
    _register_actions(app)

    @app.on_form_change
    def persist_form(ctx: AppContext, route: str, values: dict[str, Any]) -> None:
        form_name = _form_name_for_route(route)
        if form_name is not None:
            _set_form_values(ctx, form_name, values)

    @app.on_shutdown
    def persist(ctx: AppContext) -> None:
        if isinstance(ctx.state, TuiState):
            try:
                ctx.state.last_route = ctx.nav.current.strip("/") or "backup"
                save_state(ctx.state)
            except Exception:
                return

    return app


def _form_name_for_route(route: str) -> str | None:
    return {
        "/": "backup",
        "/backup": "backup",
        "/archive": "archive",
        "/restore": "restore",
        "/transfer": "transfer",
    }.get(route)


def _iter_form_fields(component: Any):
    if type(component).__name__ == "Form":
        yield from getattr(component, "fields", ())
        return
    for child in getattr(component, "items", ()) or getattr(component, "children", ()) or ():
        yield from _iter_form_fields(child)


def _field_label(field: Any) -> str:
    return str(getattr(field, "label", None) or getattr(field, "name", "value")).strip()


def _field_value(field: Any) -> str:
    value = getattr(field, "value", None)
    if value is None:
        value = getattr(field, "placeholder", "")
    return "" if value is None else str(value)


def _run_bkx_textual_shell(app: TuiApp) -> None:
    """Run BKX through Mikomn's canonical interactive renderer.

    BKX no longer maintains a parallel text-only Textual shell. Its declarative
    forms, tables, route navigation, action bars, dialogs, and command palette
    are rendered by the shared Mikomn runtime.
    """

    app.run()


def run_tui() -> int:
    """Run the BKX TUI and return a process-style exit code."""
    _run_bkx_textual_shell(build_bkx_tui_app())
    return 0


if __name__ == "__main__":
    run_tui()
