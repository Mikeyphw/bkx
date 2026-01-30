# TUI screen template

## File
`src/<tool>/tui/screens/<name>.py`

## Rules
- Screens define semantics
- Panes/widgets emit messages only
- Screen handles message → builds Spec → calls service

## Must not
- embed business logic
- do direct IO

