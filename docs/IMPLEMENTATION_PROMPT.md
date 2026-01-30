# AI Implementation Prompt: Build CLI Tool with Optional Interactive/TUI Layers

## Role
You are an expert Python architect and full-stack developer specializing in clean architecture, CLI design, and contract-driven development.

## Context
This is a **contract-controlled project rebuild**. The `docs/` directory contains the authoritative specifications, checklists, and templates that define how to build a CLI tool (`<tool>`) with:
- ✅ **Mandatory**: Core + CLI (always works standalone)
- ➕ **Optional**: Interactive mode (installable via `.[interactive]`)
- ➕ **Optional**: TUI (installable via `.[tui]`)

## Documentation Hierarchy & Relationships

```
docs/
├── guide/                    # SOURCE OF TRUTH contracts and patterns
│   ├── 00_CONTRACTS_SUMMARY.md    ← MANDATORY: Read first - 7 non-negotiable rules
│   ├── 01_ARCHITECTURE_LAYOUT.md  ← MANDATORY: Defines repo structure
│   ├── 02_COMMAND_SURFACE.md      ← MANDATORY: Defines CLI grammar
│   ├── 03_SPECS_SERVICES.md       ← MANDATORY: Defines core boundaries
│   ├── 04_UX_FEATURES.md          ← MANDATORY: Defines polish features
│   ├── README.md                  ← INDEX: How to use the guide pack
│   └── reference/
│       ├── FEATURES_MAPPING.md    ← OPTIONAL: Map existing features to specs
│       └── MIKOMN_USAGE_MAP.md    ← OPTIONAL: mikomn usage patterns
│
├── phases/                   # IMPLEMENTATION ORDER (8 phases)
│   ├── PHASE_00_FOUNDATION.md     ← Start here: skeleton + CI
│   ├── PHASE_01_CORE_SKELETON.md  ← Next: specs + services + results
│   ├── PHASE_02_CLI_MVP.md        ← Next: functional CLI
│   ├── PHASE_03_USECASES_V1.md    ← End-to-end workflows
│   ├── PHASE_04_QOL_FEATURES.md   ← Polish features
│   ├── PHASE_05_INTERACTIVE_MODE.md  ← Optional interactive CLI
│   ├── PHASE_06_TUI_MVP.md        ← Optional TUI
│   ├── PHASE_07_TUI_POLISH.md     ← Optional TUI polish
│   ├── PHASE_08_PARITY_SUITE.md   ← Determinism verification
│   └── templates/                 ← Code templates for each component
│       ├── CLI_COMMAND_TEMPLATE.md
│       ├── SERVICE_TEMPLATE.md
│       ├── SPEC_TEMPLATE.md
│       ├── PORT_TEMPLATE.md
│       ├── RESULT_TEMPLATE.md
│       ├── ERROR_TEMPLATE.md
│       ├── TEST_TEMPLATE.md
│       ├── TUI_SCREEN_TEMPLATE.md
│       ├── INTERACTIVE_FLOW_TEMPLATE.md
│       └── CI_TEMPLATE.md
│
├── checklists/               # VERIFICATION (use after implementing)
│   ├── CHECKLIST_CLI.md           ← Verify CLI layer
│   ├── CHECKLIST_CORE.md          ← Verify core layer
│   ├── CHECKLIST_INTERACTIVE.md   ← Verify interactive (if implemented)
│   ├── CHECKLIST_TUI.md           ← Verify TUI (if implemented)
│   └── CHECKLIST_PARITY.md        ← Verify determinism across interfaces
│
└── IMPLEMENTATION_PROMPT.md  ← This file
```

## How to Use This Prompt

### Step 1: Understand the Contracts (Read in Order)
1. Read `guide/00_CONTRACTS_SUMMARY.md` - these are **non-negotiable**
2. Read `guide/01_ARCHITECTURE_LAYOUT.md` - this is your folder structure
3. Read `guide/02_COMMAND_SURFACE.md` - this is your CLI grammar
4. Read `guide/03_SPECS_SERVICES.md` - this is your core boundary rules

### Step 2: Implement Phase-by-Phase
Follow the order in `phases/`:
1. **PHASE_00**: Create repo skeleton, CI, and package layout
2. **PHASE_01**: Build core (specs, services, results, errors, ports)
3. **PHASE_02**: Build CLI MVP with exit codes and render discipline
4. **PHASE_03**: Implement end-to-end use cases
5. **PHASE_04**: Add Qo. **PHASEL features
6_05**: (Optional) Implement interactive mode
7. **PHASE_06**: (Optional) Implement TUI MVP
8. **PHASE_07**: (Optional) Polish TUI
9. **PHASE_08**: Add parity tests

### Step 3: Use Templates for Each Component
When implementing, use the corresponding template:
- New CLI command → `phases/templates/CLI_COMMAND_TEMPLATE.md`
- New service → `phases/templates/SERVICE_TEMPLATE.md`
- New spec → `phases/templates/SPEC_TEMPLATE.md`
- New port → `phases/templates/PORT_TEMPLATE.md`
- New result type → `phases/templates/RESULT_TEMPLATE.md`
- New error type → `phases/templates/ERROR_TEMPLATE.md`
- New test → `phases/templates/TEST_TEMPLATE.md`
- New TUI screen → `phases/templates/TUI_SCREEN_TEMPLATE.md`
- New interactive flow → `phases/templates/INTERACTIVE_FLOW_TEMPLATE.md`
- Update CI → `phases/templates/CI_TEMPLATE.md`

### Step 4: Verify with Checklists
After implementing each layer, verify compliance:
- CLI layer → `checklists/CHECKLIST_CLI.md`
- Core layer → `checklists/CHECKLIST_CORE.md`
- Interactive layer → `checklists/CHECKLIST_INTERACTIVE.md`
- TUI layer → `checklists/CHECKLIST_TUI.md`
- Overall determinism → `checklists/CHECKLIST_PARITY.md`

## Core Architecture Rules (from `00_CONTRACTS_SUMMARY.md`)

### Dependency Direction (Allowed)
```
interfaces (CLI/interactive/TUI) → Specs → Services → Domain/Ports
```

### Forbidden Imports
- ❌ core/domain/services importing UI layers
- ❌ services importing Textual/Typer/Rich UI objects
- ❌ domain importing transport/request/response models

### Core Must Be Framework-Free
- No CLI imports
- No rendering logic
- No UI imports

### Exit Code Discipline
| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Generic error |
| 2 | Invalid usage / validation |
| 3 | Not found |
| 4 | Permission denied |
| 5 | External/system failure |

### Output + Logging
- User output → **stdout** via renderer
- Logs → **stderr**
- Core never prints, never renders

## Optional Layer Rules

### Interactive Mode
- Disabled by default
- Installed via `.[interactive]`
- Must use same Spec builders as CLI
- Can be deleted with **no refactor** required
- Never required for correctness

### TUI
- Disabled by default
- Installed via `.[tui]`
- Message-driven architecture
- Can be deleted with **no refactor** required
- Termux-first design
- No business logic in widgets/panes

## Key Success Criteria

- [ ] CLI works without extras installed
- [ ] Interactive/TUI can be deleted without breaking core+CLI
- [ ] Same inputs → same Spec → same service outcome (parity)
- [ ] Core raises typed errors only; CLI maps to stable exit codes
- [ ] No business logic in CLI parsing, interactive prompts, or TUI handlers
- [ ] Optional tools detected at runtime with full fallbacks

## Task

Implement the complete project following the phases in order, using templates for each component, and verifying compliance with checklists after each phase.

Start with **PHASE_00_FOUNDATION.md** and proceed sequentially through all phases. Use templates when creating new files. Verify with checklists after completing each phase.

Remember: **Contracts are non-negotiable. The guide is your source of truth.**

