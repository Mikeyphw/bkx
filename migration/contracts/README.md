# Frozen migration contracts

`migration/contracts/v1/` is the immutable GO00 compatibility snapshot used by the
Go contract checker. It records the historical CLI/config/protocol/release baseline
that the migration was required to preserve.

The original exporter belonged to the Python migration lane and was retired in GO18.
Its accepted GO16 source and tooling are preserved only inside the deterministic audit
archive under `migration/archive/`; they are not executable production or validation
paths.

Do not regenerate `v1` during normal development. Validate it with the production Go
CLI or the canonical maintenance seal:

```bash
go run ./cmd/bkx contract check migration/contracts/v1
bash ./scripts/validate.sh
```

A future intentional contract revision must be introduced as a new versioned contract
directory rather than rewriting the accepted `v1` evidence.
