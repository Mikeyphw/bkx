# Phase 07 — TUI polish (optional)

## Goal
Make TUI feel complete without adding core complexity.
Build upon mikomn TUI base widgets for polished UI.

## Dependencies
- **mikomn**: Enhanced TUI widgets (FilterableListPane, StatusCards, etc.)

## mikomn Polish Features

### Archive Browse/Search UI
Use FilterableListPane for searchable archive lists:
```python
from mikomn.tui import ui
from mikomn.tui import ui

archives_pane = FilterableListPane[str](
    title="Archives",
    filter_placeholder="Search archives...",
)

def handle_filter_changed(msg: FilterableListPane.FilterChanged):
    print(f"Filter: {msg.filter_text}, {msg.filtered_count}/{msg.total_count}")

archives_pane.set_items([
    ("backup-2024.zst", "/backups/backup-2024.zst"),
    ("logs-2024.tar", "/backups/logs-2024.tar"),
])
```

### Restore Pick Subset UI
Combine FilterableListPane with PreviewPane for file selection:
```python
from mikomn.tui import ui
from mikomn.tui.app.services import FilePickerService

class RestoreSubsetPane(ScrollablePaneBase):
    """Pane for selecting files to restore from archive."""
    
    def compose_body(self):
        yield FilterableListPane(
            title="Select files",
            filter_placeholder="Filter files...",
            id="file-list",
        )
        yield PreviewPane(
            title="Preview",
            placeholder="Select a file to preview",
            id="file-preview",
        )
```

### Transfer Progress UI
Use GlobalProgressBar and StatusBar for progress tracking:
```python
from mikomn.tui import ui

progress_bar = GlobalProgressBar()
status_bar = StatusBar()

# Update progress
progress_bar.advance(10)  # Advance by 10%
progress_bar.total = 100

# Show status message
status_bar.display_status(StatusMessage(
    text="Transferring files...",
    severity="info",
))
```

### Tools/Settings UI
Use StatusCards/Dashboard for settings display:
```python
from mikomn.tui import ui
from mikomn.tui import ui

cards = StatusCards(
    cards=[
        StatusCardData(
            id="theme",
            title="Theme",
            value="sweet-dracula",
            subtitle="Current color scheme",
            icon="🎨",
            status="info",
        ),
        StatusCardData(
            id="compression",
            title="Compression",
            value="zstd",
            subtitle="Default algorithm",
            icon="📦",
            status="success",
        ),
    ],
    layout="grid",
)
```

### Toast Notifications
Use ToastManager for transient notifications:
```python
from mikomn.tui.app.services import NotificationService

toast = ToastManager()
toast.show(Toast(
    message="Backup completed!",
    severity="success",
    duration=3.0,
))
```

## Deliverables
- [ ] archive browse/search UI (FilterableListPane + PreviewPane)
- [ ] restore pick subset UI (multi-select file picker)
- [ ] transfer progress UI (GlobalProgressBar + StatusBar)
- [ ] tools/settings UI (StatusCards/Dashboard)
- [ ] toast notifications (ToastManager)
- [ ] empty states (EmptyState widget)

## mikomn Features Used
- `FilterableListPane` - Searchable list with filter
- `PreviewPane` - Detail preview panel
- `StatusCards` - Dashboard-style status display
- `GlobalProgressBar` - Progress tracking
- `StatusBar` - Status messages
- `ToastManager` - Transient notifications
- `EmptyState` - Placeholder states
- `MultiSelectList` - Multi-selection list

## TODOs
- [ ] Add selection widgets with message-based intents
- [ ] Add consistent status recap components (StatusCards)
- [ ] Implement toast notifications
- [ ] Add empty state handling

## Acceptance checks
- [ ] TUI workflows mirror CLI behavior
- [ ] Search/filter works across all list views
- [ ] Progress indicators show during operations
- [ ] Settings display with StatusCards

