# CI template

## Base job (always)
- ruff check
- ruff format --check
- pyright (core+cli)
- pytest (core+cli)

## Optional jobs
- install extras `interactive` then run tests
- install extras `tui` then run tests

