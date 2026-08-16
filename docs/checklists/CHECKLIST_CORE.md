# Checklist: Core (domain/specs/services)

## Contracts
- [x] Core imports NO CLI/interactive/TUI modules
- [x] Core prints nothing, renders nothing
- [x] Services accept Specs only
- [x] Services return Results only
- [x] All expected failures are typed errors or failed Results with preserved evidence

## Determinism
- [x] All Specs normalize defaults in one place
- [x] Spec normalization is pure / repeatable except explicitly derived runtime destinations
- [x] Optional tools are selected via ToolDetectorPort/ToolService

## Ports
- [x] External tools are behind ports
- [x] Full fallback exists for each required base port
- [x] No hard deps on optional UI tools

## Tests
- [x] Spec normalization tested
- [x] Services tested with fake ports and adversarial failure paths
