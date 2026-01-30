# Phase 02 — CLI MVP (contract-clean public API)

## Goal
Mandatory CLI becomes functional skeleton with stable exit codes + render discipline.

## Deliverables
- [ ] `<tool> <noun> <verb>` grammar implemented
- [ ] `cli/exit_codes.py` typed error → stable exit code mapping
- [ ] Renderer layer (stdout) + logs (stderr)
- [ ] Global flags:
  - [ ] `--json`
  - [ ] `--plan` / `--dry-run`
  - [ ] `--progress auto|plain|off`
  - [ ] `--verbose`
  - [ ] `--trace`

## TODOs
- [ ] Implement CLI root app + noun groups
- [ ] Implement Spec builders for each command
- [ ] Implement JSON schema structure for results
- [ ] Ensure no `print()` used in core/services

## Acceptance checks
- [ ] CLI help shows commands grouped by noun
- [ ] typed error thrown in core maps to correct exit code
- [ ] JSON output parses cleanly

