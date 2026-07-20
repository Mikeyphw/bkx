# Checklist: TUI (optional)

## Removability
- [x] TUI package deletable without touching core/cli
- [x] Installed only via extras (`pip install .[tui]`)

## mikomn Integration
- [x] Uses TuiApp as app scaffold (`src/bkx/tui/app.py`)
- [x] Theme cycling works via 't' key (inherited from TuiApp)
- [x] Command palette accessible via Ctrl+P (inherited from TuiApp)
- [x] Platform layout adapts to terminal size (inherited from TuiApp)
- [x] Uses mikomn panes - Custom implementations (NavigationPane, RunLogPane, StatusFooterPane)
- [x] Uses mikomn widgets - Built-in Textual widgets with mikomn styling

## Message-driven
- [x] Widgets/panes emit messages (Intents: BackupIntent, ArchiveIntent, etc.)
- [x] Screens handle messages (forms emit Intents on button press)
- [x] App dispatches Specs to services (on_intent handler → spec builders → services)

## No logic in UI
- [x] No business logic in screens/panes/key handlers
- [x] No direct IO/subprocess in widgets
- [x] All operations go through intent dispatch

## Scroll rules
- [x] Screen does not scroll (containers use fixed layouts)
- [x] Panes declare scroll intent explicitly (RunLogPane with max_lines)

## Tests
- [x] Test message → dispatch behavior, not widget layout (`tests/tui/test_messages.py`, `tests/tui/test_dispatch.py`)

## Phase 6 Implementation Status

| Component | Status | Location |
|-----------|--------|----------|
| App Scaffold | ✅ Complete | `src/bkx/tui/app.py` extends `TuiApp` |
| Theme Integration | ✅ Complete | `cycle_theme()`, TCSS generation |
| Key Bindings | ✅ Complete | b/a/r/t/l navigation keys |
| Message Schema | ✅ Complete | `src/bkx/tui/messages.py` |
| Navigation Pane | ✅ Complete | `src/bkx/tui/panes/navigation.py` |
| Log Pane | ✅ Complete | `src/bkx/tui/panes/log.py` |
| Status Pane | ✅ Complete | `src/bkx/tui/panes/status.py` |
| Backup Screen | ✅ Complete | `src/bkx/tui/screens/backup.py` |
| Archive Screen | ✅ Complete | `src/bkx/tui/screens/archive.py` |
| Restore Screen | ✅ Complete | `src/bkx/tui/screens/restore.py` |
| Transfer Screen | ✅ Complete | `src/bkx/tui/screens/transfer.py` |
| Tools Screen | ✅ Complete | `src/bkx/tui/screens/tools.py` |
| Logs Screen | ✅ Complete | `src/bkx/tui/screens/logs.py` |
| Intent Dispatch | ✅ Complete | `on_intent()` → spec builders → services |

## Phase 7 (TUI Polish) - Pending
- [ ] mikomn FileDirPicker integration
- [ ] mikomn ConfirmModal for destructive actions
- [ ] Command palette configuration with BKX commands
- [ ] Custom TCSS styling
- [ ] Animation polish
- [ ] Progress indicators for long operations

