# Optional TUI boundary checklist

- [x] TUI is not part of the BKX production repository/runtime.
- [x] `bkx ui start --mode tui` delegates only to the external `bkx-tui` executable.
- [x] `BKX_TUI_COMMAND` may override the executable path/name; it is never interpreted as a shell command string.
- [x] Missing plugin returns the typed optional-feature exit path (exit 5) with actionable metadata.
- [x] Core backup/archive/restore/transfer logic remains in the Go production CLI; an optional UI is only a client.
