# BR36 Regression Harness / Final Audit Defect Ledger

**Roadmap position:** Overlay 16 of 16 — final gate.

BR36 closes the four residual findings assigned to U32 with behavioral regression coverage and fixed-source assertions. This ledger is intentionally shared by the `bkx` and `bkx_android` Devtool target overlays so the final Android gate can prove that the Go-side regression harness was applied first.

| Finding | Severity | Remediation sealed by BR36 | Regression evidence |
| --- | --- | --- | --- |
| A01-24 | Medium | Startup ordering is exercised through an injectable `BkxStartupSteps` barrier rather than source-string-only assertions. Concurrent ingress coalesces behind one cold-start sequence; a failed step is isolated and later startup work continues. | `BkxApplicationRuntimeBehaviorTest.kt`, `BkxBackendOutOfOrderBehaviorTest.kt` |
| A02-17 | Medium | `BkxHomeUiState.screenStatuses` is removed as independently mutable aggregate state. `screenStatus(destination)` derives display status from canonical readiness/archive/activity state. | `BkxHomeUiStateStatusTest.kt`, `NavigationAndToolsContractTest.kt` |
| A12-21 | Medium | Google Drive authorization uses an injected authorization client/credential store and generation-serialized coordinator. Tests deterministically reorder concurrent authorize, disconnect/revoke, late callbacks, and failed authorization. | `GoogleDriveAuthorizationCoordinatorTest.kt` |
| C06-02 | High | Restic snapshot ordering parses RFC3339 timestamps to `time.Time`, orders by absolute instant, rejects malformed timestamps, and uses snapshot ID as a deterministic equal-instant tie-break. | `br36_snapshot_ordering_test.go`, `maintenance.go` |

## Cross-target protocol fixtures

The Go and Android test suites consume equivalent BR36 bridge request/result golden fixtures. This makes protocol regressions visible from both sides of the Android↔Go boundary instead of relying only on source-token checks.

## Final-gate policy

1. Apply the BR36 `bkx` overlay first. Its normal Devtool Go validation must run `go vet ./...`, `go test ./...`, and build `./cmd/bkx` according to `.devtool.toml`.
2. Apply the BR36 `bkx_android` overlay second. It adds the final Android verification task and validates that the Go BR36 regression files are present.
3. Android final validation includes BR21–BR36 contract tasks, debug unit tests, debug/release lint, and release packaging/native-page-size checks. Device instrumentation remains governed by the target's existing `instrumentation = "when-device-present"` policy rather than being unconditionally forced by the static final-gate task.

## BR36 v2 final-gate repair ledger

The first BR36 validation attempt was intentionally rollback-safe and exposed additional gate defects before a commit was created. v2 closes them as part of the same final overlay number rather than creating a new roadmap item.

| Gate finding | Repair in BR36 v2 | Evidence |
| --- | --- | --- |
| Termux test destinations under `/data/data/com.termux/...` were rejected as generic protected Android data roots. | The path policy recognizes only the current process owner's Android private package root as locally writable; other app/system private roots remain protected. | `path_policy.go`, `path_policy_test.go` |
| `go vet ./...` found duplicate package-level BR36 audit helper names. | BR36 helpers are uniquely prefixed (`br36RepoRoot`, `br36Read`). | `br36_final_gate_test.go` |
| Archive create regression expected six entries while its fixture and diagnostic required five. | Corrected the stale expected count to five. | `create_test.go` |
| Split-archive regression used a short-period blob that compressed below the split threshold. | Replaced it with a deterministic high-entropy xorshift stream so split semantics, not fixture compressibility, are exercised. | `create_test.go` |
| A pending `confirm_overwrite=true` restore request could survive bridge parsing until the later execution guard. | Reject the pending confirmation flag at `restoreSpecFromBridge` for non-dry runs. | `dispatcher.go`, BR24 contract tests |
| Frozen pre-BR31 Python oracle differed only because Go now exposes additive `error_type`/`exit_code` fields. | Oracle canonicalization strips only those additive error fields; BR31 tests remain authoritative for them. | `oracle_test.go` |
| BR22–BR24 Gradle checks read `.devtool-artifact/manifest.json`, which Devtool intentionally does not apply into the repo. | Removed unused artifact-metadata inputs/reads from those verification tasks. | `android-verification.gradle.kts` |
| BR32/BR33 static token checks described older source spellings/shapes. | Refreshed assertions to current checksum, retention model, and retention CLI implementation tokens. | `android-verification.gradle.kts` |
| Android validation duplicated the full BR21–BR36 task list in the command. | `bkxBr36FinalAuditSeal` now owns release lint as well as the existing unit/static/package dependencies, so one aggregate task is sufficient. | `android-verification.gradle.kts` |

## BR36 v5 full Go-gate repair ledger

The v4 Devtool run reached the complete Go test suite and exposed seven remaining test-contract regressions across four packages. v5 fixes the entire observed batch in the same final overlay rather than adding a seventeenth roadmap item.

| Gate finding | Repair in BR36 v5 | Evidence |
| --- | --- | --- |
| Corrupt zstd ZIP tests treated the FreshDir parent directory as the publication target. | Assert the parent contains no generated destination or staging entry after failure; the parent itself may legitimately be created by `ExtractRestore`. | `zstd_integrity_test.go` |
| Local split-backup fixture still used a short-period arithmetic byte pattern. | Use the seeded high-entropy PRNG fixture already exercised successfully by remote multipart tests. | `backup_test.go`, `backup_remote_test.go` |
| Frozen V1 oracle rejected additive BR26 transfer mappings and BR24/BR32 restore preview precision. | Normalize only those named post-V1 additions for the three affected frozen cases; dedicated later-overlay tests remain authoritative. | `oracle_test.go` |
| Backup integration test called `backup.create` without matching plan evidence. | Bind `plan_request_id` and `plan_token` from the emitted `backup_plan` artifact. | `dispatcher_test.go` |
| Transfer integration/cancellation tests assumed a one-artifact plan and omitted matching plan evidence. | Accept bounded mapping artifacts and bind evidence for each run/cancellation plan. | `dispatcher_test.go` |
| Local and remote restore integration tests called `restore.extract` without preview evidence. | Bind `preview_request_id` and `plan_token` from the matching `restore_plan` artifact. | `dispatcher_test.go` |
| Restore integration tests assumed FreshDir writes directly into the caller-selected parent. | Read the authoritative returned `restored_path` artifact instead of reconstructing the generated child directory. | `dispatcher_test.go` |
