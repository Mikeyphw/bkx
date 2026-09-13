package services

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"bkx/internal/backupops"
)

func TestBR24BackupCreateRequiresMatchingPlanEvidence(t *testing.T) {
	tmp := t.TempDir()
	source := filepath.Join(tmp, "source")
	destination := filepath.Join(tmp, "dest")
	if err := os.MkdirAll(source, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(source, "photo.txt"), []byte("bkx"), 0o644); err != nil {
		t.Fatal(err)
	}
	payload := br24BackupPayload(source, destination)
	spec, err := backupSpecFromBridge(payload, true)
	if err != nil {
		t.Fatal(err)
	}
	plan, err := backupops.PlanBackup(spec)
	if err != nil {
		t.Fatal(err)
	}
	plan.Command = "backup.plan"
	expected, err := planToken(plan)
	if err != nil {
		t.Fatal(err)
	}

	payload["plan_request_id"] = "backup-plan-1"
	payload["plan_token"] = expected
	if err := requirePlanEvidence(payload, "backup.create", "plan_request_id", "plan_token", expected); err != nil {
		t.Fatalf("matching plan evidence was rejected: %v", err)
	}

	payload["plan_token"] = strings.Repeat("0", 64)
	if err := requirePlanEvidence(payload, "backup.create", "plan_request_id", "plan_token", expected); err == nil {
		t.Fatal("stale or tampered backup plan token was accepted")
	}
}

func TestBR24RestorePreviewTokenSurvivesConfirmedOverwriteExtraction(t *testing.T) {
	archive := filepath.Join(t.TempDir(), "photos.bakery")
	destination := filepath.Join(t.TempDir(), "restore")
	previewPayload := br24RestorePayload(archive, destination, false)
	previewSpec, err := restoreSpecFromBridge(previewPayload, true)
	if err != nil {
		t.Fatal(err)
	}
	expected, err := token(restorePlan(restorePreviewTokenSpec(previewSpec)))
	if err != nil {
		t.Fatal(err)
	}

	extractPayload := br24RestorePayload(archive, destination, true)
	extractPayload["preview_request_id"] = "restore-preview-1"
	extractPayload["plan_token"] = expected
	extractPayload["destructive_confirmation_accepted"] = true
	extractPayload["confirmation_evidence"] = map[string]any{
		"phrase":                       "RESTORE",
		"draft_fingerprint":            "android-draft-fingerprint",
		"plan_token":                   expected,
		"acknowledged_overwrite_count": 1,
	}
	extractSpec, err := restoreSpecFromBridge(extractPayload, false)
	if err != nil {
		t.Fatal(err)
	}
	actual, err := token(restorePlan(restorePreviewTokenSpec(extractSpec)))
	if err != nil {
		t.Fatal(err)
	}
	if actual != expected {
		t.Fatalf("preview token changed between preview and confirmed extract: %s != %s", actual, expected)
	}
	if err := requirePlanEvidence(extractPayload, "restore.extract", "preview_request_id", "plan_token", expected); err != nil {
		t.Fatalf("restore extract rejected matching preview evidence: %v", err)
	}
	if !hasConfirmationEvidence(extractPayload) {
		t.Fatal("matching restore confirmation evidence was not recognized")
	}
}

func TestBR24RestoreExtractRejectsPendingConfirmationFlag(t *testing.T) {
	payload := br24RestorePayload("/tmp/archive.bakery", "/tmp/restore", true)
	payload["confirm_overwrite"] = true
	if _, err := restoreSpecFromBridge(payload, false); err == nil {
		t.Fatal("legacy overwrite+confirm_overwrite extract payload was accepted")
	}
}

func TestBR24ArchiveArtifactsCarrySelectedArchiveIdentity(t *testing.T) {
	identity, err := archiveIdentity(map[string]any{
		"archive": map[string]any{
			"archive_id":   "archive-1",
			"display_name": "photos.bakery",
			"staged_path":  "/tmp/photos.bakery",
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	artifact := archiveBoundArtifact("archive_entry", "Pictures/a.jpg", "", identity)
	if artifact.Metadata["archive_id"] != "archive-1" || artifact.Metadata["archive_path"] != "/tmp/photos.bakery" {
		t.Fatalf("archive identity metadata was not preserved: %#v", artifact.Metadata)
	}
}

func br24BackupPayload(source, destination string) map[string]any {
	return map[string]any{
		"operation_mode": "plan_then_create",
		"source": map[string]any{
			"source_id":   "src-1",
			"label":       "Source",
			"staged_path": source,
		},
		"destination": map[string]any{
			"destination_id":        "dst-1",
			"label":                 "Destination",
			"staged_directory_path": destination,
		},
		"filters": map[string]any{
			"include_patterns": []any{},
			"exclude_patterns": []any{},
			"follow_symlinks":  false,
		},
		"archive": map[string]any{
			"archive_name": "photos",
			"compression":  "gzip",
			"split": map[string]any{
				"enabled": false,
			},
			"verification": map[string]any{
				"verify_after_create": true,
			},
		},
	}
}

func br24RestorePayload(archive, destination string, overwrite bool) map[string]any {
	return map[string]any{
		"archive":           archive,
		"archive_id":        "archive-1",
		"archive_label":     "photos.bakery",
		"destination_path":  destination,
		"output_mode":       "existing_directory",
		"fresh_dir":         false,
		"verify":            true,
		"dry_run":           !overwrite,
		"overwrite":         overwrite,
		"confirm_overwrite": false,
		"paths":             []any{"Pictures/a.jpg"},
	}
}
