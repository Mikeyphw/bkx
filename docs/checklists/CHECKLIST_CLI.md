# Checklist: CLI

## Grammar
- [ ] All commands follow `<tool> <noun> <verb>`
- [ ] No overloaded commands / implicit verbs

## Responsibilities
- [ ] CLI only parses inputs → builds Specs
- [ ] CLI calls services only
- [ ] CLI maps typed errors → stable exit codes

## Output discipline
- [ ] User output is stdout
- [ ] Logs are stderr
- [ ] JSON mode outputs pure data

## Tests
- [ ] Args → Spec builder tests
- [ ] Exit code mapping tests

