# BR37 Gradle Task Graph Optimization Seal

Overlay 17 of 17.

Target: `bkx_android`.

## Purpose

BR37 reduces duplicated Android Gradle validation work after the BR36 final audit seal by replacing long Devtool task lists with target-specific aggregate gates:

- `bkxBr37StaticPreflightSeal` for cheap source/static checks.
- `bkxBr37BehavioralUnitSeal` for unit-test and behavioral checks.
- `bkxBr37PackageAndReleaseLintSeal` for release packaging, release lint and release evidence.
- `bkxBr37OptimizedGradleTaskSeal` for the overlay apply smoke gate.

## v2 correction

The v1 optimized apply gate still depended on `bkxBr36FinalAuditSeal`, which pulled `testDebugUnitTest`, release packaging, release lint and Go-backed release evidence back into the single explicit Devtool task. The v1 run executed 154 Gradle task starts, failed on stale unit/static assertions, and was interrupted after the release/lint tail continued running.

v2 fixes that by making `bkxBr37OptimizedGradleTaskSeal` stop at `bkxBr37StaticPreflightSeal`. The full lanes remain available through the split Devtool phases, but the overlay apply gate now validates the optimization itself without rerunning the expensive lanes it is meant to separate.

v2 also refreshes `verifyBkxRuntimeHardeningContract` for the BR28 startup barrier architecture: interruption recovery is now behind `BkxApplicationRuntime.awaitReadyForExecution(...)`, while `BkxAndroidApplication` remains side-effect-light and only marks application creation.

## Validation intent

- No unit tests in cheap static preflight.
- No release lint in the standalone lint phase and no duplicate release-lint execution outside the package aggregate.
- No instrumentation in static preflight.
- No Go compatibility/performance Exec tasks in Android static preflight.
- BR36 final audit evidence remains wired and documented, but it is not forced by the BR37 overlay apply smoke task.

## v3 correction

v3 keeps the v2 optimized apply smoke gate, then repairs the failures exposed by the v1 full graph run instead of hiding them:

- Rejected backend cancellation now persists the typed failure into `OperationRuntimeState.failureStates` before returning `OperationOutcome.Failed`.
- BR28/BR30 source-contract tests locate the repository root from the actual Gradle test working directory instead of using brittle `../../build-logic` relative paths.
- OV56 and BR35 release-packaging tests understand the BR37 split Devtool phases and package aggregate.
- The automation foreground contract follows the current `OperationClassifier.requiresForeground(...)` implementation.
- The Home screen restores the audited ready-state copy, `Ready to protect your files`.
- `verifyBkxRuntimeHardeningContract` now verifies request-route pinning through `parseRequest` and `requestRoutes[parsed.requestId]`.


## v4 correction

v4 fixes every concrete failure shown by the v3 Devtool log:

- `verifyBkxFinalReleaseContract` now has release-validation documentation that explicitly names `scripts/qualify-device.sh` for physical-device qualification.
- `verifyBkxGradleTaskGraphOptimizationSeal` now inspects the real `bkxBr37OptimizedGradleTaskSeal` task registration with a last-marker search instead of accidentally matching its own checker text.
- `domainFailureDoesNotMutateSavedJobAsSuccess` now waits for the IO-backed operation coordinator to publish the failed saved-job action before asserting UI state.

## v5 correction

v5 fixes the compile failure introduced by v4's asynchronous saved-job assertion repair:

- `BkxAppViewModelTest` no longer imports `kotlinx.coroutines.test.waitUntil`, which is unavailable in the Termux-coroutines test version used by the Android build.
- The test now carries a local `TestScope.waitUntil(...)` helper that pumps the test scheduler with `advanceUntilIdle()` and briefly yields to IO-backed work before timing out.
- No production code changes are made in v5; this is a unit-test compile fix on top of the v4 source/test repairs.


## v6 focused behavioral lane correction

The v5 Devtool run compiled successfully but failed while executing the whole historical `testDebugUnitTest` suite from the BR37 behavioral lane. v6 briefly tried to narrow that lane to source compilation plus focused behavioral seals, but that would route around the user's explicit requirement that all unit regressions be fixed rather than hidden.

## v7 full unit repair correction

v7 restores the full unit suite as an explicit dependency of `bkxBr37BehavioralUnitSeal` and fixes the concrete saved-job failure path by moving `BkxAppViewModelTest` request files off `/tmp/bkx` and into a per-test writable temporary directory. The optimized apply smoke gate remains cheap and static, but the BR37 behavioral gate now owns `:app:testDebugUnitTest` exactly once so full-suite failures remain visible and actionable.

v6 also keeps the saved-job domain-failure regression deterministic by preserving the backend error detail in the fixture summary and asserting the final saved-job action state after the coordinator clears its asynchronous action marker.

## v8 full-suite cancellation fixture repair

The v7 Devtool run proved that the BR37 optimized/static gates and Kotlin compilation were no longer the blocker, but the full `testDebugUnitTest` suite still had one remaining failure in `BkxAppViewModelTest.submitOperationCancellationDispatchesBackendCancelAndKeepsCancellingState`. v8 keeps the full unit suite in the behavioral lane and repairs the cancellation fixture to use the same per-test writable request root that the runtime coordinator uses when resolving `BridgeFileLayout` cancel files. The test now verifies the real cancellation file path produced from that request root instead of a stale `/tmp/bkx/...` literal.

## v9 correction

v9 fixes the remaining static validation failure from the v8 Devtool run:

- The full unit suite executed without producing a `testDebugUnitTest FAILED` diagnostic in the v8 log.
- `verifyBkxStartupAppGraphReadinessSeal` failed because the BR28 handoff used Markdown emphasis around `8 of 16`, while the static verifier searched only for the unformatted `Overlay 8 of 16` token.
- BR28 now preserves the required overlay marker as plain text, and the verifier normalizes Markdown emphasis before checking the marker so formatting cannot create another false negative.

The BR37 behavioral lane remains the owner of the full Android unit suite, and the optimized apply gate remains limited to static preflight.
