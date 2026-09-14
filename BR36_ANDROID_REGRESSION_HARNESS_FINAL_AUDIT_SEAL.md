# BR36 — Android Regression Harness and Final Audit Seal

**Overlay 16 of 16 — Devtool target `bkx_android`.**

This target-specific BR36 overlay completes the campaign after the BR36 `bkx` overlay.

Delivered scope:
- executable cold-start ordering/concurrency behavior tests through injected startup steps;
- delayed/out-of-order backend readiness regression coverage;
- removal of the independently mutated `screenStatuses` aggregate in favor of derived canonical status;
- injected, generation-serialized Google Drive authorization lifecycle with deterministic concurrent/late-callback tests;
- Android consumption of BR36 golden bridge fixtures;
- `verifyBkxRegressionHarnessFinalAuditSeal` plus `bkxBr36FinalAuditSeal` Gradle gates;
- Devtool preflight wiring for the BR36 verification task.

## BR36 v2 validation repair

The first Android final-gate attempt ran after the Go half had been rolled back, so its cross-target prerequisite was absent. It also exposed stale verification plumbing. v2 removes BR22–BR24 reads of `.devtool-artifact/manifest.json` because Devtool intentionally excludes artifact metadata from the applied repository, refreshes BR32/BR33 source-token assertions to the current implementation, and makes `bkxBr36FinalAuditSeal` include `lintRelease` directly. The Devtool command can therefore request the single aggregate BR36 task instead of separately scheduling the entire task list.

The final static/package seal deliberately does not force `connectedDebugAndroidTest`; the target already uses Devtool's `when-device-present` instrumentation policy.


## BR36 v4 hotfix note

The Android final gate also seals the staged-file planner Kotlin source by preserving the literal backslash replacement as `replace('\\', '/')`. This prevents the final debug/release Kotlin compile gate from regressing to an invalid `'\'` character literal while leaving staged-path policy behavior unchanged.

## BR36 v5 Android compile-gate repair

The Android final gate now also seals the Kotlin compile blockers exposed by the aggregate `bkxBr36FinalAuditSeal` run: release manifest marker strings use escaped quotes, storage location direct-usability checks are declared before first use in `discoverLocations`, and provider staging sanitizes slash, backslash, and control characters without relying on invalid Kotlin regex escapes.

## BR36 v6 Android compile-gate repair

The v5 final gate reached `:app:compileDebugUnitTestKotlin` and exposed two pre-existing test-source escaping defects outside the new BR36 behavioral harness. v6 keeps the v5 production and regression-harness payload intact and adds only deterministic test-source corrections:

- `ReleasePackagingSigningNativeRuntimeContractTest` now uses raw triple-quoted Kotlin strings when asserting Gradle snippets containing nested quoted literals such as `BKX_BUILD_COMMIT`, `go.sum`, and `bkxBuildCommit`.
- `SavedJobModelsTest` now escapes the expected quoted JSON string values for `backup.snapshot` and `transfer.run` instead of emitting invalid doubled string delimiters.

These changes do not relax the seal; they allow the existing final aggregate to compile and execute the intended assertions.
