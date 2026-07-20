# Mikomn usage map (what to use where)

This is the “use mikomn wherever possible” checklist.

## CLI
Use mikomn for:
- output rendering patterns (human + JSON)
- command registry/scaffolding (if you keep it)

## Interactive
Use mikomn for:
- wizards/menus
- prompt helpers
- retry loop
- picker adapters (fzf/yazi/etc)

## TUI
Use mikomn for:
- TuiApp and layout scaffolds
- command palette overlay
- shared panes/widgets when applicable

## Glue code rules
- Glue must be thin
- Glue must never move business logic out of core

