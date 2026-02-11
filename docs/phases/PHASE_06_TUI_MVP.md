# Phase 06 — TUI MVP (optional extra, Termux-first)

## Goal
A small, stable Termux-first TUI that dispatches Specs to services.
Utilizes [mikomn](src/mikomn) TUI base framework for Textual integration.

## Dependencies
- **mikomn**: TUI base framework with theme-aware widgets and panes
- **textual**: Core TUI framework

## mikomn TUI Integration

### Base App
Use `BaseTuiApp` for theme cycling, layout modes, and command palette:
```python
from mikomn_tui.base import BaseTuiApp

class BkxApp(BaseTuiApp):
    TITLE = "BKX"
    BINDINGS = [
        ("q", "quit", "Quit"),
        ("ctrl+p", "show_command_palette", "Commands"),
    ]
```

### Layout Components
```python
from mikomn_tui.base.panes import (
    HeaderPane,      # Top bar with title
    SidebarPane,     # Navigation sidebar
    ListPane,        # Scrollable list
    PreviewPane,     # Detail preview
    LogPane,         # Run log with tail + filter
    StatusPane,      # Status bar
)
```

### Widgets
```python
from mikomn_tui.base.widgets import (
    AppHeader, AppFooter,
    FileDirPicker,   # Multi-select file/dir picker
    Breadcrumb,      # Path display
    SearchBar,       # Text query input
    KeyHints,        # Footer keybindings
    ConfirmModal,    # Destructive action confirmation
)
```

### Command Palette
```python
from mikomn_tui.base import CommandPalette, Command, CommandSelected
from mikomn_tui.base.overlays import ConfirmModal

# Define commands
commands = [
    Command(id="backup", title="Backup", group="Operations"),
    Command(id="restore", title="Restore", group="Operations"),
]

# Show palette
def action_show_command_palette(self):
    self.push_screen(CommandPalette(commands=commands))
```

### Run Log Panel
```python
from mikomn_tui.base.panes import LogPane

log_pane = LogPane(title="Run Logs", max_lines=1000)
log_pane.write("Starting backup...")
log_pane.pause()  # Toggle streaming
log_pane.clear()
```

### Confirm Modals
```python
from mikomn_tui.base.overlays import ConfirmModal

def confirm_destructive(self):
    self.push_screen(ConfirmModal(
        message="Delete archive?",
        confirm_label="Delete",
        danger=True,
    ))
```

### Platform-Aware Layout
```python
# mikomn auto-detects layout mode based on terminal size
# LayoutMode.EXPANDED  - Desktop with full panes
# LayoutMode.COMPACT   - Small desktop/tablet
# LayoutMode.CONDENSED - Termux/mobile

# Manual override via environment:
# MIKOMN_TUI_LAYOUT=expanded|compact|condensed

# App classes added: termux, desktop, expanded, condensed, compact-mode, compact
```

## Deliverables
- [ ] extra: `.[tui]` with mikomn dependency
- [ ] `ui start --mode tui`
- [ ] header/body/footer layout (AppHeader, LogPane/StatusPane, AppFooter)
- [ ] minimal tabs:
  - [ ] Backups
  - [ ] Archives
  - [ ] Restore
  - [ ] Transfers
  - [ ] Tools
- [ ] command palette (CommandPalette with Command config)
- [ ] run log panel (LogPane with tail + filter)
- [ ] confirm modals for destructive actions (ConfirmModal)
- [ ] FileDirPicker for file selection

## mikomn Features Used
- `BaseTuiApp` - App scaffold with theme cycling
- `CommandPalette` - Fuzzy search command palette
- `LogPane` - Streaming log with pause/clear/copy
- `FileDirPicker` - Visual file/dir picker
- `SidebarPane` - Navigation sidebar
- `ConfirmModal` - Confirmation dialogs
- `KeyHints` - Footer keybinding display
- Theme-aware styling via mikomn themes

## TODOs
- [ ] Implement message schema for dispatcher
- [ ] Implement dispatcher: message → Spec → service
- [ ] Define Command config for command palette
- [ ] Configure tabs using SidebarPane
- [ ] Add CSS (Termux-friendly via mikomn platform detection)

## Acceptance checks
- [ ] no business logic in UI widgets
- [ ] TUI works without modifying core
- [ ] theme cycling works via 't' key
- [ ] command palette accessible via Ctrl+P
- [ ] platform layout adapts to terminal size

