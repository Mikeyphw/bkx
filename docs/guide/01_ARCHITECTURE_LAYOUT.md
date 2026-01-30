# Canonical repository layout (CLI mandatory, interactive/TUI optional)

This layout is designed to enforce contracts by construction.

```text
project/
├─ pyproject.toml
├─ src/<tool>/
│  ├─ core/
│  │  ├─ domain/          # pure models + invariants
│  │  ├─ specs/           # frozen Specs (normalized intent)
│  │  ├─ services/        # use cases
│  │  ├─ ports/           # interfaces for IO/tooling
│  │  ├─ impl/            # concrete adapters (fs/subprocess/etc)
│  │  ├─ errors.py        # typed errors
│  │  └─ results.py       # result types
│  │
│  ├─ cli/                # REQUIRED
│  │  ├─ app.py
│  │  ├─ nouns/           # noun groups (each contains verbs)
│  │  ├─ render/          # stdout renderer + json schema emit
│  │  ├─ exit_codes.py
│  │  └─ spec_builders.py # args → Spec (shared with interactive)
│  │
│  ├─ interactive/        # OPTIONAL (extra)
│  │  ├─ app.py
│  │  ├─ flows/           # wizards/menus (no logic)
│  │  └─ pickers.py       # runtime tool selection + fallback
│  │
│  └─ tui/                # OPTIONAL (extra)
│     ├─ app.py
│     ├─ messages.py
│     ├─ screens/
│     ├─ panes/
│     ├─ widgets/
│     └─ css/
│
└─ tests/
   ├─ core/
   ├─ cli/
   ├─ interactive/
   ├─ tui/
   └─ parity/
```

## Packaging rules
- Base install includes: `core + cli`
- Extras:
  - `.[interactive]` installs interactive dependencies
  - `.[tui]` installs textual + tui dependencies

## Forbidden shortcuts
- Do not import `interactive` or `tui` from `cli`.
- Do not import `cli` from `core`.

## One Spec builder
All interfaces must call **the same Spec builder functions** to ensure parity.

Recommended pattern:
- `cli/spec_builders.py` contains `build_<verb>_spec(...)` functions
- interactive/tui call those builders (or call into a shared `spec_builders` module)

