# UX + feature improvements (consolidated)

This is the complete “nice personal-project” feature list. Keep it simple but polished.

## CLI UX
- Plan mode everywhere: `--plan` / `--dry-run`
- Human + JSON output parity
- Final recap summary after every run (paths, bytes, duration)
- Progress modes: `auto|plain|off`
- Tool detection: `tools detect` + `tools explain`
- Recent runs: `run list`, `run show <id>`
- Safer restore defaults:
  - restore into fresh dir unless `--force`
  - `--yes/--confirm` for overwrite
- Include/exclude via files: `--exclude-from`, `--include-from`

## Interactive mode UX (optional)
- Menu: noun → verb selection
- Two-phase prompts: required → optional
- Edit-before-run step
- Picker fallback chain:
  - fzf/yazi if present → rich picker → numbered prompt

## TUI UX (optional, Termux-first)
- Minimal top-level tabs:
  - Backups, Archives, Restore, Transfers, Tools
- Command palette as “power mode”
- Run log panel (tail + filter)
- Confirm modals for destructive actions

## Core/code improvements
- Spec normalization step (single source of truth)
- ExecutionPlan object (used for --plan)
- Stable run_id + run metadata record
- Strict output/log separation

