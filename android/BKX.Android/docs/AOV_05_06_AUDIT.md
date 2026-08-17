# AOV-05/AOV-06 Promise Audit

Base: current BKX Android companion after AOV-03/AOV-04 (`bkx_android_aov03_aov04_saf_runs_ui_warnings_v2.zip`, applied by the user as commit `711e421`).

Scope implemented by this overlay:

- AOV-05 — Backup planning UX, AC-025..AC-029.
- AOV-06 — Archive read-only UX, AC-030..AC-033.

Validation is intentionally deferred by workflow rule. Interim application should use `--no-validate`; final-roadmap validation will run after AOV-12.

## AC audit

| AC | Requirement | Implementation evidence | Test/audit evidence |
|---|---|---|---|
| AC-025 | Backup source/destination/filter/compression/split/verify UI model. | `core/backup/BackupModels.kt` defines `BackupPlanDraft`, source/destination selections, filters, compression, split and verification policy. `BkxHomeUiState.backupPlanning` and `BackupPlanningScreen` render it. | `BackupPlanningContractTest.backupDraftCapturesSourceDestinationFiltersCompressionSplitAndVerify`. |
| AC-026 | Generate `backup.plan` request before `backup.create`. | `BackupRequestBuilder.buildPlanRequest` emits `backup.plan`; `buildCreateRequest` requires a `BackupPlanPreview`. | `BackupPlanningContractTest.backupCreateIsGeneratedOnlyFromMatchingPlanPreview`. |
| AC-027 | Render plan warnings and verification expectations before execution. | `BackupPlanPreview` carries `warnings` and `verificationExpectations`; `BackupPlanningScreen` renders them before create is enabled. | `BackupPlanningContractTest.planningStateRendersWarningsAndVerificationExpectationsBeforeExecution`. |
| AC-028 | Do not allow create from invalid or unpreviewed plan state. | `BackupPlanDraft.requireValid` blocks invalid drafts; `buildCreateRequest` rejects null/stale/blocking previews; `BackupPlanningUiState.canCreateBackup` requires matching preview. | `invalidDraftCannotPlan`, `unpreviewedDraftCannotCreate`, and `stalePreviewCannotCreate` tests. |
| AC-029 | Test UI-to-request parity for equivalent CLI backup intent. | `BackupCliIntent` maps CLI-equivalent fields to `BackupPlanDraft.fromCliIntent`; request payload keeps normalized source, destination, filter, compression, split and verify fields. | `BackupPlanningContractTest.uiToRequestPayloadMatchesEquivalentCliBackupIntent`. |
| AC-030 | Archive list/info/browse/verify read-only workflows. | `ArchiveRequestBuilder` emits `archive.list`, `archive.info`, `archive.browse`, and `archive.verify`; `ArchiveReadOnlyUiState` carries list, info, browse and verification state; `ArchiveReadOnlyScreen` renders all four. | `archiveScreensBuildOnlyReadOnlyListInfoBrowseAndVerifyRequests`, `archiveUiStateRepresentsListInfoBrowseAndVerifyWorkflows`, and `BkxAppViewModelTest.backupAndArchiveEventsUpdateTypedScreenState`. |
| AC-031 | Support paged browse results and large archive UI states. | `ArchiveBrowsePageRequest`, `ArchiveBrowsePage`, and `ArchiveReadOnlyUiState` expose page token, page size, has-more and large-archive indicators without using mutating archive operations. | `ArchiveReadOnlyContractTest.browsePageRepresentsPagedAndLargeArchiveState`. |
| AC-032 | Surface corruption/verification failures as failures, not warning-only success. | `BridgeResultEnvelope.toArchiveVerificationUiState` maps `BridgeResultStatus.Failure` to `ArchiveVerificationUiState.Failed`; UI labels it as failed. | `ArchiveReadOnlyContractTest.verificationFailureIsRenderedAsFailureNotWarningOnlySuccess`. |
| AC-033 | Do not allow archive read-only screens to mutate data. | `ArchiveReadOnlyOperation` allowlist and `ArchiveRequestBuilder.requireReadOnlyOperation` reject mutating operation names; archive entry paths stay archive-relative. | `mutatingArchiveOperationsAreRejectedFromReadOnlyScreens`, `archiveEntryPathsMustStayArchiveRelative`, `contentUrisCannotBeUsedAsArchiveStagedPaths`. |

## Thin-interface check

This overlay creates Android-side UI state, request builders, and bridge request payloads only. It does **not** implement BKX backup/archive algorithms in Kotlin. Backup creation and archive inspection still require the Termux/BKX bridge to execute normalized operations through the fixed wrapper introduced by AOV-01/AOV-02.

## Out-of-scope preserved

- Restore extraction gates remain AOV-07.
- Transfer topology and collision handling remain AOV-08.
- Long-running progress/reconnect/cancel state remains AOV-09.
- Final malformed JSON and CLI parity seal remain AOV-10/AOV-12.


## Re-audit fixes in v2

A re-audit found that v1 built `archive.list` and `archive.info` requests but did not keep explicit UI state for the list and info results. v2 adds `ArchiveListItem`, `ArchiveInfoSummary`, generic archive workflow state, ViewModel events for list/info application, screen rendering for list/info state, and static validator checks so AC-030 is not satisfied by request builders alone.
