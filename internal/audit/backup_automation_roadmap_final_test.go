package audit

import (
	"strings"
	"testing"
)

func TestBR19BR20BackupAutomationRoadmapFinalSeal(t *testing.T) {
	root := repoRoot(t)

	cli := string(read(t, root, "internal/publiccli/app.go"))
	for _, token := range []string{
		`"job run"`, `"run replay"`, `"repository add"`, `"repository status"`, `"snapshot browse"`,
		`"retention apply"`, `"schedule add"`, `"state status"`,
	} {
		if !strings.Contains(cli, token) {
			t.Fatalf("final CLI roadmap surface missing %s", token)
		}
	}

	statePaths := string(read(t, root, "internal/statepaths/statepaths.go"))
	for _, token := range []string{
		"JobStorePath", "RepositoryStorePath", "ScheduleStorePath",
		"ExecutionControlPath", "RunHistoryPath", "SharedAndroidRoot",
	} {
		if !strings.Contains(statePaths, token) {
			t.Fatalf("shared control-plane path missing %s", token)
		}
	}

	runStore := string(read(t, root, "internal/runstore/store.go"))
	mustContain(t, runStore,
		"MergeLegacyHistory", "canonical record", "LegacyRunHistoryPath",
		"RecordStartWithOrigin", "RecordPhase", "ReplayOperation",
		`"cancelled": true`, `"interrupted": true`,
	)
	modelsText := string(read(t, root, "internal/models/models.go"))
	mustContain(t, modelsText, `json:"phase,omitempty"`, `json:"job_id,omitempty"`, `json:"job_revision,omitempty"`, `json:"operation_spec_snapshot,omitempty"`)

	resticMaintenance := string(read(t, root, "internal/resticops/maintenance.go"))
	mustContain(t, resticMaintenance, `strings.HasPrefix(value, "latest~")`, "strconv.Atoi(rawOffset)")
	resticRuntime := string(read(t, root, "internal/resticops/restic.go"))
	mustContain(t, resticRuntime, "runWithStaleLockRecovery", `append(unlockArgs, "unlock")`, "recovering_stale_lock")
	if strings.Contains(resticRuntime, `unlock", "--remove-all`) || strings.Contains(resticRuntime, `"unlock", "--remove-all"`) {
		t.Fatal("stale-lock recovery must never remove live Restic locks indiscriminately")
	}

	dispatcher := string(read(t, root, "internal/services/dispatcher.go"))
	mustContain(t, dispatcher,
		`"backup.snapshot"`, `"repositories.add"`, `"snapshots.browse"`,
		`"snapshots.restore"`, `"repository.prune"`, `"retention.apply"`,
		`"jobs.run"`, `"runs.replay"`, `"schedules.put"`, `"bytes_reused"`,
		`json:"duration_ms,omitempty"`, `json:"run_id,omitempty"`, `json:"job_id,omitempty"`,
	)
	savedJobService := string(read(t, root, "internal/services/saved_jobs.go"))
	mustContain(t, savedJobService,
		"applyOperationExecutionOverrides", `case "source":`, `case "repository", "repository_ref":`,
		"RunOrigin: origin", `"data_added"`, `"bytes_reused"`,
	)

	gateway := string(read(t, root, "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt"))
	mustContain(t, gateway,
		`desiredStateRoot.resolve("runs").resolve("runs.jsonl")`,
		`BKX_LEGACY_RUN_HISTORY`,
		`BkxSharedStateContract.ENVIRONMENT`,
	)

	for _, rel := range []string{
		"android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/runs/RunHistoryBridge.kt",
		"android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/backup/SavedJobsScreen.kt",
		"android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/backup/RepositoriesScreen.kt",
		"android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/backup/SnapshotsScreen.kt",
		"android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/automation/BkxSavedJobScheduler.kt",
		"android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/automation/BkxAutomationRecoveryReceiver.kt",
	} {
		_ = read(t, root, rel)
	}

	automation := string(read(t, root, "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/automation/AutomationContract.kt"))
	mustContain(t, automation,
		"RunSavedJob", "CreateSnapshot", "RepositoryCheck", "RetentionApply",
		"RepositoryPrune", "SnapshotRestore", "%bkx_job_id", "%bkx_run_id",
		"%bkx_repository_id", "%bkx_snapshot_id", "%bkx_files_processed",
		"%bkx_bytes_processed", "%bkx_bytes_added", "%bkx_bytes_reused", "%bkx_duration_ms",
		"%bkx_success", "%bkx_message",
	)

	runBridge := string(read(t, root, "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/runs/RunHistoryBridge.kt"))
	mustContain(t, runBridge, "runs.list", "run_json", "Instant.parse", "LocalDateTime.parse", "ZoneId.systemDefault")
	viewModel := string(read(t, root, "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/BkxAppViewModel.kt"))
	mustContain(t, viewModel, "RunHistoryBridgeCodec.decodeArtifacts", "RunHistoryRequestBuilder.list", "ReplayRun", "refreshRuns")
	backupScreen := string(read(t, root, "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/backup/BackupScreen.kt"))
	activityScreen := string(read(t, root, "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/activity/ActivityScreen.kt"))
	mustContain(t, backupScreen, "BackupRoute.History", "Run again")
	mustContain(t, activityScreen, "Run again")
	coordinator := string(read(t, root, "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/runtime/BkxOperationCoordinator.kt"))
	if strings.Contains(coordinator, "foregroundOperationMutex") {
		t.Fatal("Android must not globally serialize unrelated repositories")
	}
	mustContain(t, coordinator, "executeOwnedConcurrent", "stopForegroundShellIfIdle", "anotherForegroundOperationIsActive")
	taskerEditor := string(read(t, root, "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/automation/BkxTaskerEditActivity.kt"))
	mustContain(t, taskerEditor, "Repository override", "source=/path", "repository=repo-id")
	taskerEngine := string(read(t, root, "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/automation/BkxAutomationEngine.kt"))
	mustContain(t, taskerEngine, "savedJobRepositoryOverride", "bytesAdded", "bytesReused", "durationMillis")
	jobEditor := string(read(t, root, "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/backup/SavedJobsScreen.kt"))
	mustContain(t, jobEditor, "Backup type", "Sources", "Exclusions", "Repository", "Verify after backup", "Advanced settings", "Operation parameters (JSON)")

	manifest := string(read(t, root, "android/BKX.Android/app/src/main/AndroidManifest.xml"))
	mustContain(t, manifest, "RECEIVE_BOOT_COMPLETED", "BkxSavedJobScheduleService", "BkxAutomationRecoveryReceiver", "MY_PACKAGE_REPLACED")

	snapshotCLI := string(read(t, root, "internal/publiccli/snapshot.go"))
	mustContain(t, snapshotCLI, "snapshotContextAccess", "snapshotAccessAndPositionals", "saved-job/repository context")
	repositoryCLI := string(read(t, root, "internal/publiccli/repository.go"))
	mustContain(t, repositoryCLI, "func repositoryStatus", "SnapshotCount", "LatestSnapshot")
	jobsCLI := string(read(t, root, "internal/publiccli/jobs.go"))
	mustContain(t, jobsCLI, `operation = "jobs.plan"`, `"--dry-run"`)

	bridgeProgress := string(read(t, root, "internal/bridge/bridge.go"))
	mustContain(t, bridgeProgress, `json:"run_id,omitempty"`, `json:"job_id,omitempty"`, "p.RunID", "p.JobID")

	finalDoc := string(read(t, root, "docs/BR19_BR20_FINAL_BACKUP_AUTOMATION_SEAL.md"))
	mustContain(t, finalDoc, "BR01+02", "BR19+20", "latest~N", "run_id", "overwrite=never", "full-validation overlay", "v6 complete-roadmap parity closure")
}
