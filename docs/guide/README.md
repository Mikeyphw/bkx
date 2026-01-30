# Contract-Controlled Rebuild Guide (CLI + Optional Interactive + Optional TUI)

This folder is a **manual implementation playbook** to rebuild your project from scratch under **contract control**.

## Scope
- ✅ **Mandatory:** CLI product (always works standalone)
- ➕ **Optional:** Interactive mode (interactive CLI)
- ➕ **Optional:** Textual TUI
- ❌ **Excluded:** WebUI (explicitly out of scope)

## Authority
Treat these as source-of-truth contracts/inputs (you provide them):
- `docs.md` (contracts/checklists pack)
- `mikomn.md` (authoritative mikomn source)
- `FEATURES.md` (target feature set / product idea)

This guide **does not implement code**. It tells you exactly *what to implement*, *where*, and *how to validate it*.

## How to use this pack
1. Read `00_CONTRACTS_SUMMARY.md` (non-negotiables)
2. Set your repo structure following `01_ARCHITECTURE_LAYOUT.md`
3. Implement phase-by-phase under `phases/`
4. Use `checklists/` to verify you didn’t violate the rules
5. Use `templates/` to keep files consistent

## Success criteria
- CLI works without extras installed.
- Interactive/TUI can be deleted without breaking core+CLI.
- Same inputs → same Spec → same service outcome (parity).
- Core raises typed errors only; CLI maps to stable exit codes.

---

## Recommended build flow
Start here:
- `phases/PHASE_00_FOUNDATION.md`
- `phases/PHASE_01_CORE_SKELETON.md`
- `phases/PHASE_02_CLI_MVP.md`

Optional later:
- `phases/PHASE_05_INTERACTIVE_MODE.md`
- `phases/PHASE_06_TUI_MVP.md`

