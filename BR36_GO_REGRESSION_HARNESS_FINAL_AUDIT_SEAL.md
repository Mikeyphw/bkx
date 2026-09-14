# BR36 — Go Regression Harness and Final Audit Seal

**Overlay 16 of 16 — Devtool target `bkx`.**

This target-specific BR36 overlay adds the Go half of the final regression harness before the Android final gate is applied.

Delivered scope:
- cross-language golden bridge request/result fixtures and Go contract tests;
- absolute-time Restic snapshot ordering regressions for RFC3339 offsets;
- deterministic equal-instant snapshot ID tie-break coverage;
- malformed timestamp fail-closed coverage (no lexical fallback);
- a stdlib-only final audit package that checks the BR36 defect ledger and fixed-source invariants;
- Android private-data path policy that permits only the current process owner's package sandbox (including Termux test temp paths) while retaining protected-root rejection for other app/system roots;
- BR24 restore parsing that rejects a still-pending destructive `confirm_overwrite=true` request before execution;
- frozen Python-oracle compatibility normalization for BR31's additive structured error metadata;
- final-gate regression cleanup for the stale archive entry-count assertion, compressible split fixture, and BR36 audit helper-name collision.

## BR36 v2 validation repair

The first BR36 Go final-gate run exposed validation-only defects before commit and Devtool rolled the overlay back. v2 incorporates those findings into the overlay itself: Termux-owned private storage is modeled safely, `go vet` no longer sees duplicate audit helpers, the stale archive fixture count is corrected, the restore confirmation contract is enforced at parsing, and the immutable Python oracle ignores only BR31's later additive error metadata.

The companion `bkx_android` BR36 overlay must be applied after this overlay so its final seal can verify both target halves are present.

## BR36 v5 full Go-gate repair

The v4 final-gate run completed the build and exposed the remaining stale regression-harness assumptions across four Go packages. v5 repairs the complete observed set without weakening production safety contracts:

- corrupt zstd ZIP restore tests now assert that the FreshDir parent is empty after failure instead of incorrectly requiring the parent directory itself to remain absent;
- the local split-backup fixture now uses the same seeded high-entropy PRNG pattern already proven by the remote multipart tests;
- the immutable V1 oracle normalizes only additive post-V1 plan details introduced by BR24/BR26/BR32 (bounded transfer mappings and precise FreshDir restore preview metadata/counts);
- service integration tests now carry the plan/preview request id and token emitted by the matching plan artifact before invoking `backup.create`, `transfer.run`, or `restore.extract`;
- restore integration assertions consume the returned `restored_path` artifacts so FreshDir's resolved child directory remains authoritative.

No production token validation, FreshDir publication, zstd integrity, or split semantics are relaxed by these repairs.

## BR36 v6 run-history-page regression repair

The v5 Go final-gate run passed the previously failing archive, backup, compatibility, and tokenized service assertions. It exposed one remaining stale service integration assertion: `runs.list` now deliberately returns both the concrete `run` artifact and the paginated `run_history_page` cursor artifact. v6 updates the regression harness to verify both artifacts and their identities instead of requiring a single-artifact response.

This remains a test-contract repair only; run history pagination, redacted run payload publication, and replay semantics are unchanged.
