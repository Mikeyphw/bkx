# AOV-09/AOV-10 Audit

Scope: AC-043 through AC-049.

## AC-043 progress polling/event consumption keyed by request id

Implemented by `OperationProgressEvent`, `OperationProgressTracker.consume`, `OperationRuntimeState.progressEvents`, and `OperationRequestBuilder.buildReconnectStatusRequest`. Progress events are accepted only for an active matching request id and must advance sequence numbers.

## AC-044 reconnect after rotation/process recreation

Implemented by `OperationRuntimeState`, `OperationReconnectSnapshot`, `OperationStateStore`, `InMemoryOperationStateStore`, `SharedPreferencesOperationStateStore`, `BkxAppViewModel` reload on init, and the active-operation card. The persisted state stores request ids and last progress, not BKX business state.

## AC-045 explicit request-id cancellation and cancellation result rendering

Implemented by `OperationRequestBuilder.buildCancelRequest`, `BridgeCancelEnvelope`, `OperationProgressTracker.markCancelling`, `TermuxRunCommandInvocationFactory.createCancel`, and `TermuxBridgeAdapter.cancel`. Cancellation stays request-id based through the fixed wrapper.

## AC-046 backend timeout/quota/process death typed failures

Implemented by `OperationFailureKind`, `OperationFailureUiState`, `OperationFailureMapper.timeout`, `quota`, `processDied`, bridge error mapping, and operation result rendering.

## AC-047 malformed/multiple/partial JSON rejection

Implemented by `BkxBridgeResponseParser.parseStrictFinalResponse` and legacy `BridgeJson.parseFinalResult` tests. The schema parser rejects empty, malformed, multiple, partial, unknown-field, and unsupported-version responses.

## AC-048 BKX typed error mapping

Implemented by `OperationFailureMapper`, `BkxResultRenderer`, and `OperationResultUiState`. Stable UI actions are attached to backend timeout, quota, process death, permission, backend-not-ready, SAF grant loss, cancellation, malformed result, and verification failure states.

## AC-049 CLI parity snapshots

Implemented by `CliParitySnapshots` and `CliParitySnapshotTest` for representative `backup.plan`, `restore.preview`, and `transfer.plan` requests. The tests assert operation names, JSON output mode, and semantic payload keys that correspond to BKX CLI JSON intent.

## Thin-interface guard

No Kotlin backup/archive/restore/transfer engine logic was added. Android tracks runtime, reconnect, cancellation, parsing, and rendering only.


## Double-check fixes in v2

- AC-043 now includes `OperationProgressPoller`, which parses NDJSON/status-file progress snapshots and rejects events whose `request_id` does not match the active operation.
- AC-045 now has an app-level `SubmitOperationCancellation` path that calls `BackendPort.cancel(requestId, cancelFilePath)` instead of only marking local state as cancelling.
- AC-049 now snapshots representative bridge result semantics in addition to representative request semantics.
- The embedded validator checks for these runtime paths so a request-builder-only or UI-state-only implementation cannot pass the promise audit.
