# Checklist: CLI

## Grammar
- [x] All commands follow `<tool> <noun> <verb>`
- [x] No overloaded commands / implicit verbs

## Responsibilities
- [x] CLI only parses inputs → builds Specs
- [x] CLI calls services only
- [x] CLI maps typed errors → stable exit codes

## Output discipline
- [x] User output is stdout
- [x] Logs are stderr
- [x] JSON mode outputs pure data

## Tests
- [x] Args → Spec builder tests
- [x] Exit code mapping tests
