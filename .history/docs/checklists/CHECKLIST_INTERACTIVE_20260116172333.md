# Checklist: Interactive mode (optional)

## Removability
- [ ] Interactive package can be deleted without touching core/cli
- [ ] Installed only via extras

## mikomn Integration
- [ ] Uses mikomn for prompts (ask_text, ask_confirm, etc.)
- [ ] Theme-aware styling via mikomn colors
- [ ] banner() for section headers
- [ ] show_menu for noun→verb navigation
- [ ] select_flags for static rich menus

## Parity
- [ ] Interactive uses the same Spec builders as CLI
- [ ] Two-phase prompts: required → optional
- [ ] Preview Spec / Plan before execution

## Pickers
- [ ] Yazi picker works when available
- [ ] FZF picker works when available
- [ ] Rich fallback works without external tools
- [ ] Picker fallback chain: yazi → fzf → rich → numbered

## Retry Logic
- [ ] retry_loop used for validation retry flows
- [ ] User-friendly error messages with theming

## Tests
- [ ] Same selections produce same Spec as CLI

