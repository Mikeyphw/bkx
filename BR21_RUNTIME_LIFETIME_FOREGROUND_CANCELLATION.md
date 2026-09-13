# BR21 Runtime Lifetime, Foreground and Cancellation Seal

Date: 2026-09-13
Target: `bkx_android`
Base: `BKX_AUDIT_HANDOFF_WITH_REPO_20260913` / `bkx_repo_c16`

## Scope

BR21 is the first remediation overlay from the A01-C17 deduped roadmap. It fixes the foundational Android ↔ Go operation-lifetime issues before backup, repository, restore, retention, Tasker and scheduling overlays build on top.

Covered dedupe units:

- U02 — operation runtime persistence and state-machine fences
- U04 — foreground-service ownership, notification and timeout lifecycle
- U05 — cancellation correctness and durable cancel paths

## Implemented fixes

1. Added one Android request-id policy (`BridgeRequestIdPolicy`) and wired `BridgeFileLayout`, `ActiveOperation`, and `OperationProgressEvent` through it.
2. Mirrored the request-id hardening in the Go Android bridge: request IDs are capped at 120 bytes and `.` / `..` are rejected as path segments.
3. Added `OperationClassifier` so foreground and active-work decisions use the protected effective operation, not only the outer bridge verb.
4. Saved Job Run and Run Again now carry `effective_operation` hints from Android so `jobs.run` and `runs.replay` wrappers get backup/transfer/restore foreground protection.
5. `BkxOperationCoordinator` now fingerprints active requests and rejects duplicate request IDs with different payloads instead of coalescing unrelated work.
6. `OperationProgressTracker` now keeps terminal states terminal, keeps cancelling sticky when late progress arrives, and avoids resetting an already-bound active operation.
7. Cancellation now validates target IDs, fails inactive targets closed, marks cancelling before backend submission, and no longer terminally fails the original operation just because cancel submission failed.
8. Progress polling respects `manageForeground=false`, fixing automation isolation while preserving UI foreground lifetime.
9. Foreground service intents, reconnect intents, cancel intents, terminal notifications and planner titles now preserve/display the effective operation.
10. Added `verifyBkxRuntimeLifetimeForegroundCancellationSeal` and wired it into preflight and release seal dependencies.

## Validation commands

Intermediate apply, matching the BKX roadmap convention:

```bash
cd "$HOME/Code/bkx"

gradle --stop || true

devtool --copy --target bkx_android apply overlay \
  /sdcard/Download/bkx_BR21_runtime_lifetime_foreground_cancellation_20260913.tar.gz \
  --no-validate \
  --resource-profile standard \
  --max-workers 4 \
  --cpu-limit 4 \
  --gradle-heap-mb 2048 \
  --gradle-metaspace-mb 768 \
  --memory-guard-mb 0 \
  --parallel
```

Targeted validation after apply:

```bash
cd "$HOME/Code/bkx"

gradle --stop || true

devtool --copy --target bkx_android validate \
  --task :app:verifyBkxRuntimeLifetimeForegroundCancellationSeal \
  --task :app:testDebugUnitTest \
  --task :app:lintDebug \
  --resource-profile standard \
  --max-workers 4 \
  --cpu-limit 4 \
  --gradle-heap-mb 2048 \
  --gradle-metaspace-mb 768 \
  --memory-guard-mb 0 \
  --parallel
```

Optional Go bridge-only validation after dependencies are available:

```bash
cd "$HOME/Code/bkx"
go test ./internal/bridge
```

## Sandbox validation status

- Static BR21 contract token validation: PASS.
- Go test attempted: blocked by sandbox network/DNS while downloading `github.com/klauspost/compress v1.18.4`.
- Android Gradle tests were not executable in this sandbox because the Android Gradle wrapper / Termux Android SDK environment was not present.
