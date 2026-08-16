# Checklist: Interactive mode (optional)

## Removability
- [x] Interactive package can be deleted without touching core/cli
- [x] Installed only via extras (`pip install .[interactive]`)
- [x] Missing extra fails cleanly through `bkx ui start --mode interactive`

## mikomn Integration
- [x] Uses mikomn for prompts (`ask_text`, `ask_confirm`, `ask_path`)
- [x] Theme-aware styling via mikomn colors
- [x] `banner()` for section headers
- [x] `show_menu` for noun→verb navigation with `MenuItem` and `MenuConfig`
- [ ] `select_flags` for static rich menus (future enhancement)

## Parity
- [x] Interactive values normalize through the shared interface Spec adapter
- [x] Two-phase prompts: required → optional
- [x] Preview Spec / Plan before execution with themed rendering

## Pickers
- [x] Yazi picker works when available (`YaziAdapter` in fallback chain)
- [x] FZF picker works when available (`FzfAdapter` in fallback chain)
- [x] Rich/manual fallback works without external tools
- [x] Picker fallback chain: yazi → fzf → manual/rich

## Retry Logic
- [ ] `retry_loop` used for validation retry flows (future enhancement)
- [x] User-friendly error messages with theming

## Tests
- [x] Same selections produce same Spec as CLI/shared interface adapter
