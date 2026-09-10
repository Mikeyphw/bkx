package publiccli

import (
	"bytes"
	"path/filepath"
	"strings"
	"testing"

	"bkx/internal/executioncontrol"
	"bkx/internal/jobstore"
	"bkx/internal/models"
	"bkx/internal/schedulestore"
	"bkx/internal/statepaths"
)

func TestScheduleCLIStoresSharedJobSchedule(t *testing.T) {
	home := t.TempDir()
	shared := filepath.Join(t.TempDir(), "shared")
	t.Setenv("HOME", home)
	t.Setenv(statepaths.EnvStateDir, shared)
	jobs := jobstore.Default(home)
	_, err := jobs.Create(models.SavedJob{JobID: "phone", Name: "Phone", Enabled: true, Operation: models.NewOperationSpecV2("backup.snapshot", map[string]any{})})
	if err != nil {
		t.Fatal(err)
	}

	var stdout, stderr bytes.Buffer
	code := Run([]string{"--json", "schedule", "add", "nightly", "--job", "phone", "--every", "60", "--network", "unmetered", "--charging"}, &stdout, &stderr)
	if code != 0 {
		t.Fatalf("code=%d stderr=%q", code, stderr.String())
	}
	schedule, err := schedulestore.Default(home).Get("nightly")
	if err != nil {
		t.Fatal(err)
	}
	if schedule == nil || schedule.JobID != "phone" || !schedule.RequireCharging || schedule.Network != "unmetered" {
		t.Fatalf("schedule=%#v", schedule)
	}
}

func TestRunCancelWritesCrossProcessMarker(t *testing.T) {
	home := t.TempDir()
	shared := filepath.Join(t.TempDir(), "shared")
	t.Setenv("HOME", home)
	t.Setenv(statepaths.EnvStateDir, shared)
	var stdout, stderr bytes.Buffer
	code := Run([]string{"--json", "run", "cancel", "request-123"}, &stdout, &stderr)
	if code != 0 {
		t.Fatalf("code=%d stderr=%q", code, stderr.String())
	}
	if !executioncontrol.Default(home).CancelRequested("request-123") {
		t.Fatal("cancellation marker not persisted")
	}
	if !strings.Contains(stdout.String(), "cancellation_requested") {
		t.Fatalf("stdout=%q", stdout.String())
	}
}

func TestScheduleCLIDefaultsToNoNetworkRequirement(t *testing.T) {
	home := t.TempDir()
	shared := filepath.Join(t.TempDir(), "shared")
	t.Setenv("HOME", home)
	t.Setenv(statepaths.EnvStateDir, shared)
	jobs := jobstore.Default(home)
	_, err := jobs.Create(models.SavedJob{JobID: "local", Name: "Local", Enabled: true, Operation: models.NewOperationSpecV2("backup.create", map[string]any{})})
	if err != nil {
		t.Fatal(err)
	}
	var stdout, stderr bytes.Buffer
	code := Run([]string{"--json", "schedule", "add", "local-hourly", "--job", "local", "--every", "60"}, &stdout, &stderr)
	if code != 0 {
		t.Fatalf("code=%d stderr=%q", code, stderr.String())
	}
	schedule, err := schedulestore.Default(home).Get("local-hourly")
	if err != nil {
		t.Fatal(err)
	}
	if schedule == nil || schedule.Network != "none" {
		t.Fatalf("schedule=%#v", schedule)
	}
}
