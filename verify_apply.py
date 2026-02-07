from __future__ import annotations

from pathlib import Path


def test_no_preview_pane_in_src() -> None:
    repo_root = Path(__file__).resolve().parent
    src = repo_root / "src" / "rcm"
    assert src.exists(), "Missing src/rcm"

    offenders: list[str] = []
    for p in src.rglob("*.py"):
        txt = p.read_text(encoding="utf-8")
        if "PreviewPane" in txt or "preview_pane" in txt:
            offenders.append(str(p.relative_to(repo_root)))

    assert not offenders, f"Legacy PreviewPane usage found: {offenders}"


def test_settings_and_recent_locations_use_inspector() -> None:
    repo_root = Path(__file__).resolve().parent

    settings = (repo_root / "src/rcm/tui/views/settings.py").read_text(encoding="utf-8")
    recent = (repo_root / "src/rcm/tui/overlays/recent_locations.py").read_text(encoding="utf-8")

    assert "InspectorPane" in settings and "PreviewPane" not in settings
    assert "InspectorPane" in recent and "PreviewPane" not in recent


def test_runner_artifacts_removed() -> None:
    repo_root = Path(__file__).resolve().parent
    assert not (repo_root / "spec.py").exists(), "Remove repo-root spec.py runner artifact"
    assert not (repo_root / "p.patch").exists(), "Remove repo-root p.patch runner artifact"
