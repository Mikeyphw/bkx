# BKX Android AOV-07/AOV-08 audit

Scope: AC-034 through AC-042.

## AOV-07 / Restore safety gates

- AC-034: `RestoreDraft`, `RestoreDestinationSelection`, `RestoreSubsetSelection`, `RestoreUiState`, and `RestoreSafetyScreen` represent archive selection, destination selection, subset selection, and preview workflow.
- AC-035: `RestoreDraft.outputMode` defaults to `RestoreOutputMode.FreshDirectory`; `RestoreRequestBuilder.buildExtractRequest` refuses extraction without a matching `RestorePreviewSummary`.
- AC-036: `RestorePreviewSummary`, `RestoreOverwriteSummary`, and `RestorePathSafetySummary` render overwrite, link, special-file, verification, and path-safety information derived from `BridgeResultEnvelope` fields.
- AC-037: destructive restore extraction requires `RestoreExtractionConfirmation` with the fixed `RESTORE` phrase, matching draft fingerprint, matching plan token, and matching overwrite count, and serialized bridge confirmation evidence.
- AC-038: `RestoreRequestBuilder` recomputes the current draft fingerprint over archive, destination, subset, output mode, and verify policy before extraction, so navigation or output-mode changes cannot bypass the preview/confirmation gate.

## AOV-08 / Transfer safety gates

- AC-039: `TransferTopologyDraft`, `TransferMode.Push/Pull/Sync`, `TransferEndpoint`, and `TransferSafetyScreen` represent push/pull/sync topology and mapping summaries.
- AC-040: `TransferPreviewSummary`, `TransferCollisionSummary`, `TransferFilePolicy`, and `TransferVerificationPlan` render collisions, overlaps, symlink/special-file policy, and verification plans before execution.
- AC-041: `BridgeResultEnvelope.toTransferResultUiState()` maps partial failures and verification/checksum failures to typed UI failure states instead of success or warning-only states.
- AC-042: `TransferRequestBuilder.buildRunRequest` refuses transfer execution without a valid topology and matching `TransferPreviewSummary`; the preview request uses the handoff protocol operation `transfer.plan`.

## Thin-interface proof

The Android code builds bridge request envelopes and renders state only. It does not implement BKX restore extraction, transfer copy/sync, collision detection, archive verification, or filesystem publication semantics in Kotlin.

## Interim validation policy

This overlay is applied with `--no-validate`. Full Devtool/Gradle validation is intentionally deferred until the final roadmap overlay.

## v2 corrective audit

- Fixed AC-037 bridge evidence: destructive restore extraction now sets `confirm_overwrite=true` after local confirmation and includes structured `confirmation_evidence` with phrase, timestamp, overwrite count, draft fingerprint, and plan token.
- Fixed AC-042 protocol parity: transfer preview state is now requested through the handoff protocol operation `transfer.plan`, while `transfer.run` still requires the matching plan/preview request id and fingerprint.
- Strengthened static checks so these two gaps cannot pass with local-only confirmation or a non-schema transfer operation.
