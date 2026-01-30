# Phase 05 — Interactive mode (optional extra)

## Goal
Interactive CLI is a Spec-builder UX layer, parity-safe and removable.
Utilizes [mikomn](src/mikomn) for Rich-based interactive prompts, menus, and pickers.

## Dependencies
- **mikomn**: Theme-aware interactive primitives (prompts, menus, pickers)
- Optional: `yazi` (visual file picker), `fzf` (alternative picker)

## Deliverables
- [ ] extra: `.[interactive]` with mikomn dependency
- [ ] `ui start --mode interactive`
- [ ] noun→verb menu (using `show_menu` from mikomn)
- [ ] two-phase prompts (required → optional)
- [ ] spec/plan preview with theme-aware styling
- [ ] edit-before-run loop (using mikomn's retry_loop)
- [ ] picker fallback chain (yazi → fzf → rich → numbered)
- [ ] static rich menu for flag selection (using `select_flags`)

## mikomn Integration

### Theme-Aware Prompts
Use mikomn's prompt helpers for consistent styling:
```python
from mikomn.cli_base.interactive import (
    ask_text, ask_confirm, ask_int, ask_float, ask_path,
    banner, accent, primary, foreground,
)
```

### Fancy Section Headers
```python
banner("=== Interactive Backup ===")
```

### Noun→Verb Menu with show_menu
```python
from mikomn.cli_base.interactive import MenuItem, MenuConfig, show_menu

items = [
    MenuItem(value="backup", label="Backup", description="Create backup archives"),
    MenuItem(value="restore", label="Restore", description="Restore from archives"),
]
config = MenuConfig(title="What would you like to do?", picker="auto")
result = show_menu(items, config=config)
```

### Static Rich Menu for Flags
```python
from mikomn.cli_base.menus import select_flags, make_flag_option

flags = [
    make_flag_option("--algorithm", "Compression algorithm", default="zstd"),
    make_flag_option("--level", "Compression level", default=3, input_type="int"),
]
values = select_flags(flags)
```

### Yazi Picker Integration
```python
from mikomn.cli_base.pickers import FilePicker, PickOptions, PickerType, YaziAdapter

picker = FilePicker(
    PickOptions(title="Select sources", mode=SelectionMode.MULTIPLE),
    yazi=YaziAdapter(),
)
# Falls back to fzf then manual if yazi unavailable
paths = picker.pick(PickerType.YAZI)
```

### Retry Loop for Validation
```python
from mikomn.cli_base.interactive import retry_loop

result = retry_loop(
    attempt=validate_input,
    is_retryable=lambda e: isinstance(e, ValidationError),
    on_error=lambda e: console.print(f"[error]{e}[/]"),
)
```

## TODOs
- [ ] Build interactive orchestrator that calls shared Spec builders
- [ ] Add tool detection for yazi/fzf pickers
- [ ] Integrate mikomn theme (set via `load_theme`)
- [ ] Ensure interactive never bypasses validation
- [ ] Use mikomn's banner() for section headers
- [ ] Implement flag selection with select_flags()

## Acceptance checks
- [ ] same selection produces same Spec as CLI
- [ ] interactive works without fzf/yazi installed (rich fallback)
- [ ] theming applies consistently via mikomn
- [ ] banner() provides fancy section headers

