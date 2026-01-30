# Command surface (strict grammar)

Contract grammar:

`<tool> <noun> <verb> [OPTIONS]`

No overloaded commands.
No implicit verbs.

## Proposed nouns + verbs

### backup
- `backup create` — full workflow (archive → transfer → verify)
- `backup plan` — preview execution plan and resolved Spec

### archive
- `archive create` — create archive
- `archive list` — list archives
- `archive info` — show metadata
- `archive browse` — list/search inside archive
- `archive verify` — verify integrity

### transfer
- `transfer push` — move artifacts to destination
- `transfer pull` — bring artifacts to local

### restore
- `restore extract` — restore full archive
- `restore pick` — restore a subset of paths

### tools
- `tools detect` — show available optional tools
- `tools explain` — explain which tools will be used and why

### run
- `run list` — recent runs
- `run show` — show run recap / logs

### ui (optional extension launcher)
- `ui start --mode interactive`
- `ui start --mode tui`

## Global flags (recommended)
- `--json` output
- `--plan` / `--dry-run`
- `--progress auto|plain|off`
- `--verbose`
- `--trace` (show stack traces)

