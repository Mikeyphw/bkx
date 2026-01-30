# Checklist: Core (domain/specs/services)

## Contracts
- [ ] Core imports NO CLI/interactive/TUI modules
- [ ] Core prints nothing, renders nothing
- [ ] Services accept Specs only
- [ ] Services return Results only
- [ ] All failures are typed errors (no sentinel returns)

## Determinism
- [ ] All Specs normalize defaults in one place
- [ ] Spec normalization is pure / repeatable
- [ ] Optional tools are selected via ToolDetectorPort

## Ports
- [ ] External tools are behind ports
- [ ] Full fallback exists for each port
- [ ] No hard deps on optional tools

## Tests
- [ ] Spec normalization tested
- [ ] Services tested with fake ports

