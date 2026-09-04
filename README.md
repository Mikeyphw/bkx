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

Hosted CI validates the base package without requiring machine-local checkouts.
Optional Interactive/TUI validation is explicit and local: set `MIKOMN_ROOT` and run
`scripts/validate-optional-ui.sh`.

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

Hosted CI builds and installs a wheel, smokes both CLI entrypoints, verifies packaged schemas/CSS assets, and runs the base test suite across Python 3.11–3.13. Optional UI parity remains an explicit local gate because it depends on local UI dependencies; base parity tests self-skip only the TUI-specific class when Textual is absent.
## Python → Go migration

GO00–GO06 establish a compatibility-first migration lane without changing the
production backend. `migration/contracts/v1/` freezes the current Python CLI,
protocol-v1 Android bridge, core configuration/error/run-history contracts, and
canonical operation schemas. The Go tree now contains the migration scaffold plus
an in-place JSONL run-history implementation, frozen model shapes, typed errors,
configuration layering, and shared validation/path rules.

GO02/GO03 are guarded by a differential test: Python writes production-format run
history and configuration state, Go reads/mutates it, and Python reopens the result.
The Go executable still deliberately rejects normal backup/archive/restore/transfer
commands until later operation/service parity gates are complete.

Use `devtool --target bkx_go validate` for the Go lane or
`scripts/validate-go06.sh` for the complete migration gate. The Android app
remains Kotlin/Compose and continues to use the production embedded-Python engine.
See `docs/migration/python-to-go.md`.


### Go migration GO04

GO04 ports the migration-only filesystem/traversal/hash/tool-detection foundation and
adds deterministic Android ARM64 ELF/build-metadata validation. Python remains the
production backend; Go product commands still fail closed until later parity gates.


### Go migration GO05

GO05 ports read-only archive operations (`list`, `info`, `browse`, and `verify`) into
the migration-only Go backend, with Python-Go parity across ZIP, TAR, TAR.GZ, and
TAR.BZ2 fixtures plus corruption and unsafe-member checks. Normal `bkx archive ...`
commands remain Python-owned and fail closed in the Go scaffold.


### Go migration GO06

GO06 ports archive creation into the migration-only Go backend. Publication is
transactional (same-directory staging, optional verification, fsync, rename, cleanup
on failure), reuses GO04 traversal/filter/symlink safety, and preserves Python's
implicit-default compression compatibility. TAR and ZIP creation support none/gzip
directly and BZIP2/XZ through explicitly detected optional tools where required.
The normal Go `bkx archive create` product command remains fail-closed.
