"""BKX CLI entrypoint.

Contract rules:
- Grammar is strict noun/verb: ``bkx <noun> <verb>``.
- Base CLI remains usable without interactive/TUI extras.
- All output goes through the shared renderer.
"""

# ruff: noqa: B008 - Typer Argument/Option defaults are intentional and safe
from __future__ import annotations

from importlib.metadata import PackageNotFoundError, version as package_version
import sys
import traceback
from pathlib import Path
from typing import Iterable

import typer
from typer import Argument, Option

from bkx.cli.exit_codes import error_to_exit_code
from bkx.cli.render import get_renderer, render_result
from bkx.cli.spec_builders import (
    build_archive_browse_spec,
    build_archive_info_spec,
    build_archive_list_spec,
    build_archive_spec,
    build_archive_verify_spec,
    build_backup_spec,
    build_restore_spec,
    build_transfer_spec,
)
from bkx.core.config import get_config
from bkx.core.errors import BkxError, OptionalFeatureError, ValidationError
from bkx.core.results.execution_plan import execution_plan_to_dict, plan_for_spec
from bkx.core.services import get_archive_service, get_backup_service, get_restore_service, get_run_service, get_transfer_service, get_tool_service
from bkx.core.specs.tools import ToolDetectSpec, ToolExplainSpec

_ALLOWED_PROGRESS = {"auto", "plain", "off"}

_state = {"json": False, "dry_run": False, "plan": False, "progress": "auto", "verbose": False, "trace": False}

app = typer.Typer(no_args_is_help=True, add_completion=False, rich_markup_mode="markdown")


def _validate_progress(value: str) -> str:
    normalized = value.strip().lower()
    if normalized not in _ALLOWED_PROGRESS:
        raise typer.BadParameter("progress must be one of: auto, plain, off")
    return normalized


def _handle_bkx_error(renderer, error: BkxError) -> None:
    if _state["trace"]:
        traceback.print_exception(type(error), error, error.__traceback__, file=sys.stderr)
    renderer.render_error(str(error), error.details)
    raise typer.Exit(error_to_exit_code(error))


def _render_service_result(result, *, kind: str | None = None) -> None:
    render_result(result, _state["json"], kind=kind)
    if getattr(result, "success", True) is False:
        raise typer.Exit(1)


def _renderer():
    return get_renderer(_state["json"])


def _verbose(message: str) -> None:
    if _state["verbose"]:
        _renderer().render_verbose(message)


def _should_plan() -> bool:
    return bool(_state["dry_run"] or _state["plan"])


def _read_patterns(files: Iterable[Path], inline: Iterable[str] | None = None) -> list[str]:
    patterns: list[str] = []
    if inline:
        patterns.extend(inline)
    for path in files:
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as exc:
            raise ValidationError(msg=f"Could not read pattern file: {path}", details={"path": str(path), "reason": str(exc)}) from exc
        for line in text.splitlines():
            item = line.strip()
            if item and not item.startswith("#"):
                patterns.append(item)
    return patterns


def _optional_tui_runner():
    try:
        from bkx.tui.app import run_tui
    except ModuleNotFoundError as exc:
        dependency = exc.name or "optional TUI dependency"
        raise OptionalFeatureError(
            msg=f"BKX TUI is unavailable because '{dependency}' is not installed. Install 'bkx[tui]'.",
            tool_name=dependency,
            feature="tui",
            extra="tui",
            dependency=dependency,
            details={"feature": "tui", "extra": "bkx[tui]", "dependency": dependency},
        ) from exc
    return run_tui


def _optional_interactive_runner():
    try:
        from bkx.interactive.main import main as run_interactive
    except ModuleNotFoundError as exc:
        dependency = exc.name or "optional interactive dependency"
        raise OptionalFeatureError(
            msg=f"BKX interactive mode is unavailable because '{dependency}' is not installed. Install 'bkx[interactive]'.",
            tool_name=dependency,
            feature="interactive",
            extra="interactive",
            dependency=dependency,
            details={"feature": "interactive", "extra": "bkx[interactive]", "dependency": dependency},
        ) from exc
    return run_interactive


def _bkx_version() -> str:
    try:
        return package_version("bkx")
    except PackageNotFoundError:
        return "0.0.1"


@app.callback(invoke_without_command=True)
def main_callback(
    ctx: typer.Context,
    json: bool = Option(False, "--json", help="Output exactly one JSON document"),
    dry_run: bool = Option(False, "--dry-run", help="Show what would be done without executing"),
    plan: bool = Option(False, "--plan", help="Alias for --dry-run plan rendering"),
    progress: str = Option("auto", "--progress", help="Progress display: auto|plain|off", callback=_validate_progress),
    verbose: bool = Option(False, "--verbose", "-v", help="Emit diagnostics to stderr"),
    trace: bool | None = Option(None, "--trace/--no-trace", help="Show full stack traces on typed errors without changing exit codes"),
    version: bool = Option(False, "--version", help="Show version and exit"),
) -> None:
    _state.update(json=bool(json), dry_run=bool(dry_run or plan), plan=bool(plan), progress=progress, verbose=bool(verbose), trace=bool(trace) if trace is not None else False)
    renderer = _renderer()
    try:
        config = get_config()
    except BkxError as error:
        _handle_bkx_error(renderer, error)
    _state["trace"] = bool(trace) if trace is not None else config.trace
    if version:
        renderer.render_message(f"bkx {_bkx_version()}")
        raise typer.Exit()
    if ctx.invoked_subcommand is None:
        typer.echo(ctx.get_help())
        raise typer.Exit()


backup_app = typer.Typer(help="Backup operations")
app.add_typer(backup_app, name="backup")


@backup_app.command("create")
def backup_create(
    sources: list[Path] = Argument(..., help="Source paths to back up"),
    destination: Path | None = Option(None, "-d", "--destination", help="Final backup destination directory"),
    exclude: list[str] = Option([], "-e", "--exclude", help="Exclude glob pattern"),
    include: list[str] = Option([], "-i", "--include", help="Include glob pattern"),
    exclude_from: list[Path] = Option([], "--exclude-from", help="Read exclude patterns from file"),
    include_from: list[Path] = Option([], "--include-from", help="Read include patterns from file"),
    name: str | None = Option(None, "-n", "--name", help="Archive name"),
    algorithm: str | None = Option(None, "--compression", help="Compression algorithm"),
    level: int | None = Option(None, "--compression-level", help="Compression level"),
    split_size: int | None = Option(None, "--split-size", help="Split archive size in bytes"),
    verify: bool | None = Option(None, "--verify/--no-verify", help="Verify archive and transfer"),
) -> None:
    renderer = _renderer()
    try:
        spec = build_backup_spec(
            sources=sources,
            destination=destination,
            archive_name=name,
            excludes=_read_patterns(exclude_from, exclude),
            includes=_read_patterns(include_from, include),
            split_size=split_size,
            algorithm=algorithm,
            level=level,
            dry_run=_should_plan(),
            verify=verify,
        )
        if _should_plan():
            renderer.render_plan("backup", execution_plan_to_dict(plan_for_spec(spec, command="backup create")))
            return
        _verbose("executing backup create")
        _render_service_result(get_backup_service().create(spec), kind="backup")
    except BkxError as error:
        _handle_bkx_error(renderer, error)


@backup_app.command("plan")
def backup_plan(sources: list[Path] = Argument(...), destination: Path | None = Option(None, "-d", "--destination")) -> None:
    renderer = _renderer()
    try:
        spec = build_backup_spec(sources=sources, destination=destination, dry_run=True)
        renderer.render_plan("backup", execution_plan_to_dict(plan_for_spec(spec, command="backup plan")))
    except BkxError as error:
        _handle_bkx_error(renderer, error)


archive_app = typer.Typer(help="Archive operations")
app.add_typer(archive_app, name="archive")


@archive_app.command("create")
def archive_create(
    sources: list[Path] = Argument(...),
    output: Path = Option(..., "-o", "--output"),
    exclude: list[str] = Option([], "-e", "--exclude"),
    include: list[str] = Option([], "-i", "--include"),
    exclude_from: list[Path] = Option([], "--exclude-from"),
    include_from: list[Path] = Option([], "--include-from"),
    algorithm: str | None = Option(None, "--compression"),
    level: int | None = Option(None, "--compression-level"),
    split_size: int | None = Option(None, "--split-size"),
    verify: bool | None = Option(None, "--verify/--no-verify"),
) -> None:
    renderer = _renderer()
    try:
        spec = build_archive_spec(sources=sources, output=output, excludes=_read_patterns(exclude_from, exclude), includes=_read_patterns(include_from, include), split_size=split_size, algorithm=algorithm, level=level, dry_run=_should_plan(), verify=verify)
        if _should_plan():
            renderer.render_plan("archive", execution_plan_to_dict(plan_for_spec(spec, command="archive create")))
            return
        _verbose("executing archive create")
        _render_service_result(get_archive_service().create(spec), kind="archive")
    except BkxError as error:
        _handle_bkx_error(renderer, error)


@archive_app.command("list")
def archive_list(
    archive: Path = Argument(...),
    filter_pattern: str | None = Option(None, "--filter"),
    page: int = Option(0, "--page"),
    page_size: int = Option(100, "--page-size"),
) -> None:
    renderer = _renderer()
    try:
        result = get_archive_service().list(build_archive_list_spec(archive, filter_pattern=filter_pattern, page=page, page_size=page_size))
        _render_service_result(result, kind="archive_list")
    except BkxError as error:
        _handle_bkx_error(renderer, error)


@archive_app.command("info")
def archive_info(archive: Path = Argument(...)) -> None:
    renderer = _renderer()
    try:
        _render_service_result(get_archive_service().info(build_archive_info_spec(archive)), kind="archive_info")
    except BkxError as error:
        _handle_bkx_error(renderer, error)


@archive_app.command("browse")
def archive_browse(
    archive: Path = Argument(...),
    query: str | None = Option(None, "-q", "--query"),
    file_type: str | None = Option(None, "-t", "--type"),
    min_size: int | None = Option(None, "--min-size"),
    max_size: int | None = Option(None, "--max-size"),
    limit: int = Option(50, "-l", "--limit"),
    offset: int = Option(0, "--offset"),
    sort_by: str = Option("path", "-s", "--sort"),
    sort_order: str = Option("asc", "-o", "--order"),
) -> None:
    renderer = _renderer()
    try:
        spec = build_archive_browse_spec(archive=archive, query=query, file_type=file_type, min_size=min_size, max_size=max_size, limit=limit, offset=offset, sort_by=sort_by, sort_order=sort_order)
        _render_service_result(get_archive_service().browse(spec), kind="archive_browse")
    except BkxError as error:
        _handle_bkx_error(renderer, error)


@archive_app.command("verify")
def archive_verify(archive: Path = Argument(...)) -> None:
    renderer = _renderer()
    try:
        _render_service_result(get_archive_service().verify(build_archive_verify_spec(archive)), kind="archive_verify")
    except BkxError as error:
        _handle_bkx_error(renderer, error)


transfer_app = typer.Typer(help="Transfer operations")
app.add_typer(transfer_app, name="transfer")


@transfer_app.command("push")
def transfer_push(source: str = Argument(...), destination: str = Argument(...), verify: bool | None = Option(None, "--verify/--no-verify"), overwrite: bool = Option(False, "--overwrite")) -> None:
    renderer = _renderer()
    try:
        spec = build_transfer_spec(source=source, destination=destination, direction="push", dry_run=_should_plan(), verify=verify, overwrite=overwrite)
        if _should_plan():
            renderer.render_plan("transfer", execution_plan_to_dict(plan_for_spec(spec, command="transfer push")))
            return
        _render_service_result(get_transfer_service().push(spec), kind="transfer")
    except BkxError as error:
        _handle_bkx_error(renderer, error)


@transfer_app.command("pull")
def transfer_pull(source: str = Argument(...), destination: str = Argument(...), verify: bool | None = Option(None, "--verify/--no-verify"), overwrite: bool = Option(False, "--overwrite")) -> None:
    renderer = _renderer()
    try:
        spec = build_transfer_spec(source=source, destination=destination, direction="pull", dry_run=_should_plan(), verify=verify, overwrite=overwrite)
        if _should_plan():
            renderer.render_plan("transfer", execution_plan_to_dict(plan_for_spec(spec, command="transfer pull")))
            return
        _render_service_result(get_transfer_service().pull(spec), kind="transfer")
    except BkxError as error:
        _handle_bkx_error(renderer, error)


restore_app = typer.Typer(help="Restore operations")
app.add_typer(restore_app, name="restore")


def _restore_common(archive: Path, destination: Path | None, paths: list[str], overwrite: bool, yes: bool, fresh_dir: bool | None, verify: bool | None, command: str, *, pick: bool = False) -> None:
    renderer = _renderer()
    try:
        spec = build_restore_spec(archive=archive, destination=destination, paths=paths, dry_run=_should_plan(), fresh_dir=fresh_dir, confirm_overwrite=False if yes else None, overwrite=overwrite, verify=verify)
        if _should_plan():
            renderer.render_plan("restore", execution_plan_to_dict(plan_for_spec(spec, command=command)))
            return
        service = get_restore_service()
        result = service.pick(spec) if pick else service.extract(spec)
        _render_service_result(result, kind="restore")
    except BkxError as error:
        _handle_bkx_error(renderer, error)


@restore_app.command("extract")
def restore_extract(archive: Path = Argument(...), destination: Path | None = Option(None, "-d", "--destination"), overwrite: bool = Option(False, "-o", "--overwrite"), yes: bool = Option(False, "-y", "--yes"), fresh_dir: bool | None = Option(None, "--fresh-dir/--no-fresh-dir"), verify: bool | None = Option(None, "--verify/--no-verify")) -> None:
    _restore_common(archive, destination, [], overwrite, yes, fresh_dir, verify, "restore extract")


@restore_app.command("pick")
def restore_pick(archive: Path = Argument(...), paths: list[str] = Argument(..., help="Archive member paths to restore"), destination: Path | None = Option(None, "-d", "--destination"), overwrite: bool = Option(False, "-o", "--overwrite"), yes: bool = Option(False, "-y", "--yes"), fresh_dir: bool | None = Option(None, "--fresh-dir/--no-fresh-dir"), verify: bool | None = Option(None, "--verify/--no-verify")) -> None:
    _restore_common(archive, destination, paths, overwrite, yes, fresh_dir, verify, "restore pick", pick=True)


tools_app = typer.Typer(help="Tool management")
app.add_typer(tools_app, name="tools")


@tools_app.command("detect")
def tools_detect(tool_name: list[str] = Argument(None, help="Optional tool names to detect")) -> None:
    renderer = _renderer()
    try:
        names = tuple(tool_name) if tool_name else ToolDetectSpec().tool_names
        _render_service_result(get_tool_service().detect(ToolDetectSpec(tool_names=names)), kind="tools_detect")
    except BkxError as error:
        _handle_bkx_error(renderer, error)


@tools_app.command("explain")
def tools_explain(category: list[str] = Argument(None, help="Optional categories to explain")) -> None:
    renderer = _renderer()
    try:
        cats = tuple(category) if category else ToolExplainSpec().categories
        _render_service_result(get_tool_service().explain(ToolExplainSpec(categories=cats)), kind="tools_explain")
    except BkxError as error:
        _handle_bkx_error(renderer, error)


run_app = typer.Typer(help="View run history")
app.add_typer(run_app, name="run")


@run_app.command("list")
def run_list(limit: int = Option(20, "-l", "--limit"), operation: str | None = Option(None, "-o", "--operation")) -> None:
    renderer = _renderer()
    try:
        _render_service_result(get_run_service().list(limit=limit, operation=operation), kind="run_list")
    except BkxError as error:
        _handle_bkx_error(renderer, error)


@run_app.command("show")
def run_show(run_id: str = Argument(...)) -> None:
    renderer = _renderer()
    try:
        _render_service_result(get_run_service().show(run_id), kind="run_show")
    except BkxError as error:
        _handle_bkx_error(renderer, error)


ui_app = typer.Typer(help="Optional user interfaces")
app.add_typer(ui_app, name="ui")


@ui_app.command("start")
def ui_start(mode: str = Option("tui", "--mode", help="Interface mode: tui|interactive")) -> None:
    renderer = _renderer()
    try:
        normalized = mode.strip().lower()
        if normalized == "tui":
            _optional_tui_runner()()
        elif normalized == "interactive":
            raise typer.Exit(_optional_interactive_runner()())
        else:
            raise ValidationError(msg="Unsupported UI mode", details={"mode": mode, "valid": ["tui", "interactive"]})
    except BkxError as error:
        _handle_bkx_error(renderer, error)


def main() -> None:
    app()
