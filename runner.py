#!/usr/bin/env python3
"""
apply_and_verify.py

Reusable runner for rc_manager step overlays.

Usage:
  python apply_and_verify.py path/to/step_spec.py

Step spec format (Python file; executed locally):
  FILES_TO_WRITE: dict[str, str]   # repo-relative path -> full content
  PATCHES: list[str]              # unified diffs applied via: patch -p1 --forward --batch
  FILES_TO_REMOVE: list[str]      # repo-relative paths to delete

Optional:
  TCSS_VALIDATE_IGNORE: list[str] # glob patterns (repo-relative). Defaults include "**/mikomn/**/tokens.tcss"

Verification (stop on first failure):
  1) pytest -q verify_apply.py
  2) pyright <touched .py> -> pyright.out
  3) ruff format <touched .py>
  4) ruff check --fix <touched .py>
  5) ruff check <touched .py> -> ruff.out
  6) mikomn-tools tcss validate <each touched .tcss>

Notes:
- "Touched files" are computed from the SPEC (not from this runner).
- This runner never lints itself, only touched files from the spec.
"""

from __future__ import annotations

import argparse
import fnmatch
import os
import re
import runpy
import shlex
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

try:
    from rich.console import Console
    from rich.panel import Panel
    from rich.table import Table
    from rich.text import Text
except Exception:  # pragma: no cover
    Console = None  # type: ignore[assignment]
    Panel = None  # type: ignore[assignment]
    Table = None  # type: ignore[assignment]
    Text = None  # type: ignore[assignment]

console = Console(highlight=False) if Console else None


@dataclass(frozen=True)
class Spec:
    files_to_write: dict[str, str]
    patches: list[str]
    files_to_remove: list[str]
    tcss_validate_ignore: list[str]


@dataclass(frozen=True)
class CmdResult:
    code: int
    tail: str


def _die(msg: str, code: int = 2) -> None:
    if console:
        console.print(f"[bold red]ERROR[/bold red]: {msg}")
    else:
        print(f"ERROR: {msg}", file=sys.stderr)
    raise SystemExit(code)


def _rule(title: str) -> None:
    if console and Text:
        console.rule(Text(title, style="bold"))
    else:
        print(f"\n=== {title} ===", flush=True)


def _p(msg: str) -> None:
    if console:
        console.print(msg)
    else:
        print(msg, flush=True)


def _cmd_str(cmd: list[str]) -> str:
    return " ".join(shlex.quote(c) for c in cmd)


def _run_cmd(
    cmd: list[str],
    cwd: Path,
    *,
    tee_path: Path | None = None,
) -> CmdResult:
    _rule(_cmd_str(cmd))
    _p(f"[dim]cwd[/dim]: {cwd}" if console else f"cwd: {cwd}")

    tail_lines: list[str] = []
    tail_keep = 180

    tee_file = tee_path.open("w", encoding="utf-8") if tee_path else None
    try:
        proc = subprocess.Popen(
            cmd,
            cwd=str(cwd),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        assert proc.stdout is not None
        for line in proc.stdout:
            if console:
                console.print(line, end="", markup=False)
            else:
                print(line, end="", flush=True)
            if tee_file:
                tee_file.write(line)
                tee_file.flush()
            tail_lines.append(line.rstrip("\n"))
            if len(tail_lines) > tail_keep:
                tail_lines = tail_lines[-tail_keep:]
        code = proc.wait()
    finally:
        if tee_file:
            tee_file.close()

    tail = "\n".join(tail_lines[-tail_keep:])
    if code != 0:
        if console and Panel:
            console.print(f"\n[bold red]ERROR[/bold red]: exit code {code}")
            if tail.strip():
                console.print(Panel(tail, title="last output", border_style="red"))
        else:
            print(f"\nERROR: exit code {code}\n{tail}", flush=True)
        raise SystemExit(code)

    return CmdResult(code=code, tail=tail)


def _find_repo_root(start: Path) -> Path:
    cur = start.resolve()
    for candidate in (cur, *cur.parents):
        if (candidate / "pyproject.toml").exists():
            return candidate
    _die("Could not find pyproject.toml in this directory or any parent.")
    raise AssertionError


def _load_spec(spec_path: Path) -> Spec:
    if not spec_path.exists():
        _die(f"Spec file not found: {spec_path}")

    ns = runpy.run_path(str(spec_path))
    files_to_write = ns.get("FILES_TO_WRITE")
    patches = ns.get("PATCHES")
    files_to_remove = ns.get("FILES_TO_REMOVE")

    if not isinstance(files_to_write, dict) or not all(
        isinstance(k, str) and isinstance(v, str) for k, v in files_to_write.items()
    ):
        _die("Spec must define FILES_TO_WRITE: dict[str, str]")

    if not isinstance(patches, list) or not all(isinstance(p, str) for p in patches):
        _die("Spec must define PATCHES: list[str]")

    if not isinstance(files_to_remove, list) or not all(isinstance(p, str) for p in files_to_remove):
        _die("Spec must define FILES_TO_REMOVE: list[str]")

    ignore = ns.get("TCSS_VALIDATE_IGNORE", ["**/mikomn/**/tokens.tcss"])
    if not isinstance(ignore, list) or not all(isinstance(p, str) for p in ignore):
        _die("TCSS_VALIDATE_IGNORE must be list[str] if provided")

    return Spec(
        files_to_write=files_to_write,
        patches=patches,
        files_to_remove=files_to_remove,
        tcss_validate_ignore=ignore,
    )


def _write_if_changed(repo_root: Path, relpath: str, content: str) -> None:
    path = repo_root / relpath
    path.parent.mkdir(parents=True, exist_ok=True)
    new_text = content if content.endswith("\n") else content + "\n"
    if path.exists() and path.read_text(encoding="utf-8") == new_text:
        return
    path.write_text(new_text, encoding="utf-8")


def _parse_paths_from_patch(patch_text: str) -> set[str]:
    paths: set[str] = set()
    for line in patch_text.splitlines():
        if line.startswith("--- ") or line.startswith("+++ "):
            tok = line.split(maxsplit=1)[1].strip()
            if tok == "/dev/null":
                continue
            tok = re.sub(r"^(a/|b/)", "", tok)
            paths.add(tok)
    return paths


def _apply_patch(repo_root: Path, patch_text: str) -> None:
    cmd = ["patch", "-p1", "--forward", "--batch"]
    _rule(_cmd_str(cmd))
    _p(f"[dim]cwd[/dim]: {repo_root}" if console else f"cwd: {repo_root}")

    tail_lines: list[str] = []
    tail_keep = 240

    proc = subprocess.Popen(
        cmd,
        cwd=str(repo_root),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    assert proc.stdin is not None
    assert proc.stdout is not None

    patch_payload = patch_text if patch_text.endswith("\n") else patch_text + "\n"
    proc.stdin.write(patch_payload)
    proc.stdin.close()

    for line in proc.stdout:
        if console:
            console.print(line, end="", markup=False)
        else:
            print(line, end="", flush=True)
        tail_lines.append(line.rstrip("\n"))
        if len(tail_lines) > tail_keep:
            tail_lines = tail_lines[-tail_keep:]

    code = proc.wait()
    tail = "\n".join(tail_lines[-tail_keep:])

    if code == 0:
        return

    already_applied_markers = [
        "Reversed (or previously applied) patch detected",
        "Skipping patch",
        "patch detected!  Skipping",
        "previously applied",
    ]
    if any(m in tail for m in already_applied_markers):
        _p("[yellow]Patch already applied; continuing.[/yellow]" if console else "Patch already applied; continuing.")
        return

    if console and Panel:
        console.print(f"\n[bold red]ERROR[/bold red]: patch failed (exit code {code})")
        if tail.strip():
            console.print(Panel(tail, title="patch output", border_style="red"))
    else:
        print(f"\nERROR: patch failed (exit {code})\n{tail}", flush=True)
    raise SystemExit(code)


def _remove_path(path: Path) -> None:
    if not path.exists():
        return
    if path.is_dir():
        for sub in sorted(path.rglob("*"), reverse=True):
            if sub.is_file():
                sub.unlink()
            elif sub.is_dir():
                sub.rmdir()
        path.rmdir()
    else:
        path.unlink()


def _compute_touched_files(repo_root: Path, spec: Spec) -> list[str]:
    touched: set[str] = set(spec.files_to_write.keys()) | set(spec.files_to_remove)
    for p in spec.patches:
        touched |= _parse_paths_from_patch(p)

    removed = set(spec.files_to_remove)
    existing_after: list[str] = []
    for rel in sorted(touched):
        if rel in removed:
            continue
        if (repo_root / rel).exists():
            existing_after.append(rel)
    return existing_after


def _matches_any_glob(relpath: str, patterns: Iterable[str]) -> bool:
    return any(fnmatch.fnmatch(relpath, pat) for pat in patterns)


def _render_summary(repo_root: Path, spec_path: Path, touched: list[str]) -> None:
    if console and Table and Panel:
        meta = Table(show_header=False, box=None, pad_edge=False)
        meta.add_column(style="dim")
        meta.add_column()
        meta.add_row("repo_root", str(repo_root))
        meta.add_row("spec", str(spec_path))
        meta.add_row("touched_files", str(len(touched)))
        console.print(Panel(meta, title="Apply summary", border_style="bright_blue"))

        t = Table(title="Touched files", show_header=True, header_style="bold magenta", show_lines=True)
        t.add_column("#", justify="right", style="dim")
        t.add_column("path")
        for i, p in enumerate(touched, start=1):
            t.add_row(str(i), p)
        console.print(t)
    else:
        print(f"repo_root: {repo_root}\nspec: {spec_path}\ntouched_files: {len(touched)}", flush=True)
        for p in touched:
            print(f"- {p}", flush=True)


def _verify(repo_root: Path, spec: Spec, touched: list[str]) -> None:
    py_files = [p for p in touched if p.endswith(".py")]
    tcss_files = [p for p in touched if p.endswith(".tcss") and not _matches_any_glob(p, spec.tcss_validate_ignore)]

    if not (repo_root / "verify_apply.py").exists():
        _die("verify_apply.py is required at repo root but was not found after apply.")

    _run_cmd(["pytest", "-q", "verify_apply.py"], cwd=repo_root)

    pyright_out = repo_root / "pyright.out"
    ruff_out = repo_root / "ruff.out"

    if py_files:
        _run_cmd(["pyright", *py_files], cwd=repo_root, tee_path=pyright_out)
        _run_cmd(["ruff", "format", *py_files], cwd=repo_root)
        _run_cmd(["ruff", "check", "--fix", *py_files], cwd=repo_root)
        _run_cmd(["ruff", "check", *py_files], cwd=repo_root, tee_path=ruff_out)
    else:
        pyright_out.write_text("", encoding="utf-8")
        ruff_out.write_text("", encoding="utf-8")

    for f in tcss_files:
        _run_cmd(["mikomn-tools", "tcss", "validate", f], cwd=repo_root)

    if console:
        console.print("\n[bold green]ALL GREEN[/bold green]")
        console.print(f"[dim]repo_root[/dim]: {repo_root}")
        console.print(f"[dim]touched_files[/dim]: {len(touched)}")
        console.print(f"[dim]pyright.out[/dim]: {pyright_out}")
        console.print(f"[dim]ruff.out[/dim]: {ruff_out}")
    else:
        print("\nALL GREEN", flush=True)
        print(f"repo_root: {repo_root}", flush=True)
        print(f"touched_files: {len(touched)}", flush=True)
        print(f"pyright.out: {pyright_out}", flush=True)
        print(f"ruff.out: {ruff_out}", flush=True)


def main() -> None:
    ap = argparse.ArgumentParser(prog="apply_and_verify.py")
    ap.add_argument("spec", type=str, help="Path to step spec Python file")
    args = ap.parse_args()

    repo_root = _find_repo_root(Path.cwd())
    os.chdir(repo_root)

    spec_path = Path(args.spec).expanduser()
    if not spec_path.is_absolute():
        spec_path = (Path.cwd() / spec_path).resolve()

    spec = _load_spec(spec_path)

    if console and Panel and Text:
        console.print(
            Panel.fit(
                Text.assemble(
                    ("apply_and_verify.py\n", "bold"),
                    ("Reusable step runner\n", "white"),
                    ("repo: ", "dim"),
                    (str(repo_root), "cyan"),
                    ("\n", "white"),
                    ("spec: ", "dim"),
                    (str(spec_path), "cyan"),
                ),
                border_style="bright_blue",
                title="rc_manager",
            )
        )

    for rel, content in spec.files_to_write.items():
        _write_if_changed(repo_root, rel, content)

    for patch_text in spec.patches:
        _apply_patch(repo_root, patch_text)

    for rel in spec.files_to_remove:
        _remove_path(repo_root / rel)

    touched = _compute_touched_files(repo_root, spec)
    _render_summary(repo_root, spec_path, touched)
    _verify(repo_root, spec, touched)


if __name__ == "__main__":
    main()
