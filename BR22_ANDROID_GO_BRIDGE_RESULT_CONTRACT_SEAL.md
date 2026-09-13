# BR22 — Android-Go Bridge, Runtime Session and Result Contract Seal

This overlay implements the second deduped BKX remediation unit from the A01-C17 roadmap. It targets U06 and U20: Android-Go runtime session isolation, protocol/route pinning, final result status handling, and Android reducer separation between transport acceptance and domain success/failure/cancellation.

## Product contracts sealed

- Android publishes the request descriptor before executing long Go work so a running operation has durable evidence even before the final result arrives.
- Progress and cancel probes no longer invalidate the shared Go session or fail unrelated execute calls.
- Reader failure is scoped to the session that actually owned the pending command; an old reader cannot poison a replacement session.
- Go session commands and replies are size-bounded at the Android and Go boundaries.
- The Go bridge session gates concurrent command fanout and reports oversize outbound replies instead of silently writing unbounded JSON.
- Final results are parsed with expected request-id validation; progress and cancel acknowledgements are request-id pinned.
- Android reducers now route backend terminal failure/cancel statuses through failure handlers by default. Call sites that intentionally render terminal failure as content, such as archive verification and transfer result cards, opt in explicitly.
- Repository, saved-job, schedule, snapshot, and history reducers no longer treat a transport-accepted failure result as a successful mutation.

## Covered roadmap units

- U06 — Android-Go runtime session, protocol and route pinning.
- U20 — Bridge result/status contracts and Android reducers.

## Intended apply mode

Intermediate overlay: apply with `--no-validate`. A later gate overlay should run the BR22 task together with unit tests, lint, and Go tests.
