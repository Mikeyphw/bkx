# Mandatory constraints (greenfield)

This is the condensed “do-not-violate” ruleset extracted from `docs.md` and reflected in this guide.

## 1) Architecture direction (dependency rules)
**Allowed direction:**

`interfaces (CLI/interactive/TUI)` → `Specs` → `Services` → `Domain/Ports`

**Forbidden imports:**
- core/domain/services importing UI layers
- services importing Textual / Typer / Rich UI objects
- domain importing transport/request/response models

✅ Core must be framework-free.

## 2) Thin interfaces, thick core
- **Core** owns: domain models, Specs, services, typed errors, ports.
- **Interfaces** do ONLY:
  - parse input → build Specs
  - call services
  - render results
  - map typed errors → exit codes (CLI)

No business logic in:
- CLI parsing
- interactive prompt branches
- TUI key handlers/screens/panes

## 3) Determinism + parity
- Identical input ⇒ identical `Spec`.
- Identical `Spec` ⇒ identical service outcome.
- Optional interfaces must **not** introduce divergent defaults.

## 4) Exit code discipline (public CLI API)
Core raises typed errors only.
CLI maps typed errors to stable exit codes:

| code | meaning |
|---:|---|
| 0 | success |
| 1 | generic error |
| 2 | invalid usage / validation |
| 3 | not found |
| 4 | permission denied |
| 5 | external/system failure |

## 5) Output + logging separation
- User output → **stdout** via a renderer layer
- Logs → **stderr**
- Core never prints, never renders

## 6) Optional external tools
- runtime-detected
- full fallbacks
- never hard dependencies

## 7) Optional interface rules
Interactive and TUI are:
- disabled by default
- installed via extras
- deletable with **no refactor** required
- never required for correctness

