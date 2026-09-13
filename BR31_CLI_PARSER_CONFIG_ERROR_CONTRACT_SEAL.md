# BR31 — CLI Parser, Config and Error Contract Seal

**Overlay 11 of 16** in the BR21–BR36 BKX remediation roadmap.

## Scope

BR31 closes the deduped CLI parser/config/error boundary units U21 and U27. It repairs parser exits, JSON error rendering, side-effect-free help, real scalar YAML config compatibility, wrapped typed-error classification, JSON number stability, output write failures, and the dead `--progress` parser surface.

## Fixed contracts

- Unknown/malformed leaf options now return usage exit code instead of success.
- `--json` parser and dispatch errors render exactly one JSON error document on stdout with no extra stderr usage banner.
- Group and leaf help are served before config/store initialization, including `state status --help`.
- Long options accept standard `--option=value` form; root `--progress=plain|auto|off` is parsed deterministically.
- Config loading supports YAML scalar compatibility needed by BKX (`---`, `...`, quoted scalars, inline comments, yes/no/on/off booleans) and rejects nested mappings instead of silently flattening them.
- Config permission errors map to permission exit class.
- Wrapped `berror.Error` values keep their stable public exit code and details.
- JSON normalization keeps large integers as `json.Number` and no longer fabricates fake `[0, duration]` timing arrays.
- Error JSON includes stable `error_type`, `exit_code`, details and typed metadata such as tool name/tool exit/stderr/feature/dependency.
- Renderer write failures now propagate to an external/system exit rather than being swallowed.

## Deferred validation gate

Future gate task:

```text
:app:verifyBkxCliParserConfigErrorContractSeal
```

Recommended gate coverage:

```bash
go test ./internal/berror ./internal/config ./internal/publiccli ./cmd/bkx
```

Full validation stays deferred because this is an intermediate overlay.
