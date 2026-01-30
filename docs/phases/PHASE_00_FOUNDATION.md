# Phase 00 — Foundation (contracts + CI green)

## Goal
Repo boots, CI green, and package layout enforces contract boundaries.

## Deliverables
- [ ] Repo skeleton (src/<tool>/core, src/<tool>/cli)
- [ ] Optional packages exist but unused: src/<tool>/interactive, src/<tool>/tui
- [ ] `pyproject.toml` with extras:
  - [ ] `interactive`
  - [ ] `tui`
- [ ] ruff + pyright + pytest configured
- [ ] CLI entrypoint exists and prints help

## TODOs
- [ ] Create package layout
- [ ] Add ruff config (format + lint)
- [ ] Add pyright config
- [ ] Add minimal pytest setup

## Acceptance checks
- [ ] `python -m <tool> --help` works
- [ ] `ruff check .` passes
- [ ] `pyright` passes
- [ ] `pytest` runs (even if empty)

