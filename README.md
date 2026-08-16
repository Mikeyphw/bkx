# BKX

BKX is a contract-controlled, CLI-first backup/archive tool. The base package
keeps runtime backup/archive/restore/transfer logic independent from optional
Interactive and TUI frontends.

## Current verified capabilities

- Base install/import works without optional UI dependencies.
- `bkx` and `python -m bkx` both bootstrap the CLI.
- Strict noun/verb CLI grammar is used for backup, archive, restore, transfer,
  tools, run history, and optional UI launch.
- Specs normalize inputs deterministically and are validated against packaged
  JSON Schemas.
- Archive create/list/browse/info/verify, restore extract/pick, transfer
  push/pull, tool detection/explanation, backup orchestration, and run history
  are routed through service boundaries.
- JSON output is emitted through one shared renderer as exactly one JSON
  document per command.
- Interactive and TUI modules are optional extras. Missing extras fail with a
  typed, user-actionable message instead of breaking the base CLI.
- Packaged wheels include core JSON Schemas and TUI `.tcss` assets.

## Install

```bash
python -m pip install .
```

Optional interfaces require their extras:

```bash
python -m pip install '.[interactive]'
python -m pip install '.[tui]'
```

Local full validation of optional interfaces also requires `MIKOMN_ROOT` to
point at the local mikomn checkout used by the validator.

## Examples

```bash
bkx --help
python -m bkx --help
bkx --json --plan backup create ./src --exclude '*.pyc'
bkx archive list backup.tar --page 0 --page-size 50
bkx restore pick backup.tar docs/readme.md --destination ./restore --yes
bkx ui start --mode tui
```

## Validation

The release gate builds and installs a wheel, smokes both CLI entrypoints,
verifies packaged schemas/CSS assets, runs base tests across Python 3.11–3.13,
and runs optional UI tests when local mikomn dependencies are available.
