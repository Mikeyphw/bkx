# Optional interactive UI boundary checklist

- [x] Interactive UI is not part of the BKX production repository/runtime.
- [x] `bkx ui start --mode interactive` delegates only to the external `bkx-interactive` executable.
- [x] `BKX_INTERACTIVE_COMMAND` may override the executable path/name; it is never interpreted as a shell command string.
- [x] Missing plugin returns the typed optional-feature exit path (exit 5) with actionable metadata.
- [x] Core operations and validation remain Go-owned independently of any optional UI plugin.
