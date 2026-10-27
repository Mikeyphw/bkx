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


## BR36-ANDROID-V4 — Staged file planner compile seal

- Finding: the Android final gate exposed an invalid Kotlin backslash character literal in `StagedFilePlanner.kt`, reported by both debug and release Kotlin compilation.
- Fix: keep the existing path normalization behavior but encode the backslash literal as `replace('\\', '/')`.
- Evidence: `:app:compileDebugKotlin` and `:app:compileReleaseKotlin` can now parse the staged-file planner source; no production policy semantics were changed.

## BR36 v5 Android compile-gate repair

The Android final gate now also seals the Kotlin compile blockers exposed by the aggregate `bkxBr36FinalAuditSeal` run: release manifest marker strings use escaped quotes, storage location direct-usability checks are declared before first use in `discoverLocations`, and provider staging sanitizes slash, backslash, and control characters without relying on invalid Kotlin regex escapes.

## API-03 / BKX native Skybind consumer (2026-10-26)

- **Finding API03-BKX-01 — consumer trust boundary:** BKX's Android app-private rclone runtime and config cannot be transparently replaced with Skybind's root-private `/data/adb/skybind/config/rclone.conf`. Its existing R1 consumer is separate and strictly read-only; using the root-native config from a non-root BKX PID would either fail or create an unsafe privilege expansion.
- **Delivered source remediation:** explicit `BKX_RCLONE_PROVIDER=skybind` for an independently authorized **root BKX CLI**, with pinned module launcher/native-engine/alias checks, root-owner and permissions checks, canonical private configuration, fail-closed invalid/ambiguous overrides, focused behavioral regression tests and read-only Devtool/CI gates. Existing app-bundled provider and R1 client unchanged.
- **Affected repositories:** `Mikeyphw/bkx`, `Mikeyphw/skybind` (producer API-01/02 contract), and future chrootctl/BKX interoperability.
- **Devtool improvement:** require identity-specific negative tests for optional privileged providers, not only file-presence checks; keep source-only passing results separate from Android SELinux/provider acceptance.
- **Status:** Consumer root-CLI adapter SOURCE IMPLEMENTED. Hosted full suite, on-device principal and Skybind config reconciliation OPEN. BKX non-root Android managed-feature consumer transport is **NOT IMPLEMENTED**; R1 and bundled rclone must not be retired.


### API03-BKX-02 — inactive Skybind module selected from direct path (2026-10-26)
Evidence: root opt-in provider resolved `/data/adb/modules/skybind/system/bin/rclone` even when a module-manager `disable` or `remove` marker was present; direct file existence alone does not mean an active installed module. Fix: require trusted module.prop with exact `id=skybind` and refuse disabled/pending-removal markers, regression-tested with fake root-owned module fixtures. Device/root-manager qualification remains OPEN; existing app-private BKX runtime and R1 are untouched.


### API03-BKX-03 — unprivileged Termux fixture cannot emulate root ownership (2026-10-26)
Evidence: Devtool transaction `af27fac12cd44663ae2b5dc20e753ccf`, `internal/rcloneops/skybind_provider_test.go:38`, build artifact PASS, root provider fixture FAIL `untrusted Skybind directory owner/mode`. Root-owned production state must not be downgraded to the test UID. R3 fix: an internal fixture-only owner argument while the production resolver remains UID0-pinned, with negative production-owner test and writable-directory rejection. Status: SOURCE REPAIRED; Devtool rerun pending.

### API03-BKX-04 — Go suite process I/O completion delay (2026-10-26)
Evidence: same Devtool transaction, `go test -json -p 2 ./...` reported `exec: WaitDelay expired before I/O complete` for internal/config, internal/publiccli and internal/services. May involve inherited child-process descriptors or test-runner output lifetime; unproven. Recommended: retain full tests as mandatory, isolate package subprocesses/pipe inheritance and run a serial or package-level diagnostic with exact receipt. No skip flags or silent success classification. Status: OPEN; independent from provider fixture.
