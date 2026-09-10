package services

import (
	"encoding/json"
	"path/filepath"
	"testing"

	"bkx/internal/jobstore"
	"bkx/internal/models"
	"bkx/internal/repositorystore"
	"bkx/internal/schedulestore"
)

func TestScheduleServiceUsesSharedStoreAndRequiresExistingJob(t *testing.T) {
	root := t.TempDir()
	jobs := &jobstore.Store{Path: filepath.Join(root, "jobs.json")}
	repos := &repositorystore.Store{Path: filepath.Join(root, "repositories.json")}
	schedules := &schedulestore.Store{Path: filepath.Join(root, "schedules.json")}
	d := NewWithStores(nil, repos, jobs)
	d.Schedules = schedules
	_, err := jobs.Create(models.SavedJob{JobID: "phone", Name: "Phone", Enabled: true, Operation: models.OperationSpecV2{SchemaVersion: 2, Operation: "backup.snapshot", Parameters: map[string]any{}}})
	if err != nil {
		t.Fatal(err)
	}

	raw := json.RawMessage(`{"schedule_id":"nightly","job_id":"phone","interval_minutes":60,"require_charging":true,"network":"unmetered"}`)
	result, err := d.Dispatch(OperationContext{RequestID: "schedule-create"}, "schedules.put", raw)
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Artifacts) != 1 || result.Artifacts[0].Metadata["schedule_id"] != "nightly" {
		t.Fatalf("unexpected result: %#v", result)
	}

	listed, err := d.Dispatch(OperationContext{RequestID: "schedule-list"}, "schedules.list", json.RawMessage(`{}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(listed.Artifacts) != 1 || listed.Artifacts[0].Metadata["job_id"] != "phone" {
		t.Fatalf("unexpected list: %#v", listed)
	}

	if _, err := d.Dispatch(OperationContext{RequestID: "missing"}, "schedules.put", json.RawMessage(`{"schedule_id":"bad","job_id":"missing","interval_minutes":60}`)); err == nil {
		t.Fatal("expected missing job to fail")
	}
}
