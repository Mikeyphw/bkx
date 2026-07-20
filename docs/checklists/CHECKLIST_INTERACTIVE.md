# Checklist: Interactive mode (optional)

## Removability
- [x] Interactive package can be deleted without touching core/cli
- [x] Installed only via extras (`pip install .[interactive]`)

## mikomn Integration
- [x] Uses mikomn for prompts (`ask_text`, `ask_confirm`, `ask_path`, `ask_int`, `ask_float`)
- [x] Theme-aware styling via mikomn colors
- [x] `banner()` for section headers
- [x] `show_menu` for noun→verb navigation with `MenuItem` and `MenuConfig`
- [ ] `select_flags` for static rich menus (future enhancement)

## Parity
- [x] Interactive uses the same Spec builders as CLI (`build_backup_spec`, `build_archive_spec`, etc.)
- [x] Two-phase prompts: required → optional (e.g., destination required, archive_name optional)
- [x] Preview Spec / Plan before execution with themed rendering

## Pickers
- [x] Yazi picker works when available (`YaziAdapter` in fallback chain)
- [x] FZF picker works when available (`FzfAdapter` in fallback chain)
- [x] Rich fallback works without external tools (via `FilePicker` default)
- [x] Picker fallback chain: yazi → fzf → rich (via `_get_available_picker()`)

## Retry Logic
- [ ] `retry_loop` used for validation retry flows (can be added)
- [x] User-friendly error messages with theming via `themed_message()`

## Tests
- [ ] Same selections produce same Spec as CLI (integration test)

## Implementation Status
| Feature | Status | Location |
|---------|--------|----------|
| Theme-aware prompts | ✅ Complete | `src/bkx/interactive/main.py` |
| Noun→verb menu | ✅ Complete | `show_menu()` + `MenuItem` |
| File pickers | ✅ Complete | `src/bkx/interactive/pickers.py` |
| Yazi integration | ✅ Complete | `YaziAdapter` |
| FZF integration | ✅ Complete | `FzfAdapter` |
| Banner headers | ✅ Complete | `banner()` function |
| TUI integration | ✅ Complete | `src/bkx/tui/app.py` extends `TuiApp` |

