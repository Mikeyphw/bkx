package jobstore

import (
	"encoding/json"
	"os"
	"strings"
	"testing"
	"time"

	"bkx/internal/models"
)

func testClock() func() time.Time {
	times := []time.Time{
		time.Date(2026, 9, 10, 10, 0, 0, 0, time.UTC),
		time.Date(2026, 9, 10, 11, 0, 0, 0, time.UTC),
		time.Date(2026, 9, 10, 12, 0, 0, 0, time.UTC),
	}
	index := 0
	return func() time.Time {
		if index >= len(times) {
			return times[len(times)-1]
		}
		value := times[index]
		index++
		return value
	}
}

func jobDraft(id, name string) models.SavedJob {
	return models.SavedJob{
		JobID:   id,
		Name:    name,
		Enabled: true,
		Operation: models.NewOperationSpecV2("backup.create", map[string]any{
			"sources": []any{"/shared/Documents"},
			"verify":  true,
		}),
	}
}

func TestCreateUpdateRevisionHistoryAndReload(t *testing.T) {
	path := t.TempDir() + "/jobs/jobs.json"
	store := New(path)
	store.Now = testClock()

	created, err := store.Create(jobDraft("phone-backup", "Phone backup"))
	if err != nil {
		t.Fatal(err)
	}
	if created.Revision != 1 || created.CreatedAt != "2026-09-10T10:00:00Z" {
		t.Fatalf("created=%+v", created)
	}

	replacement := jobDraft("phone-backup", "Phone backup nightly")
	replacement.Description = "Reusable incremental-ready phone backup"
	updated, err := store.Update("phone-backup", 1, replacement)
	if err != nil {
		t.Fatal(err)
	}
	if updated.Revision != 2 || updated.CreatedAt != created.CreatedAt || updated.UpdatedAt != "2026-09-10T11:00:00Z" {
		t.Fatalf("updated=%+v", updated)
	}

	revisions, err := store.Revisions("phone-backup")
	if err != nil {
		t.Fatal(err)
	}
	if len(revisions) != 2 || revisions[0].Revision != 1 || revisions[1].Revision != 2 {
		t.Fatalf("revisions=%+v", revisions)
	}

	reloaded := New(path)
	got, err := reloaded.Get("phone-backup")
	if err != nil || got == nil || got.Revision != 2 || got.Name != "Phone backup nightly" {
		t.Fatalf("got=%+v err=%v", got, err)
	}
	st, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm()&0o077 != 0 {
		t.Fatalf("saved job store permissions are too broad: %o", st.Mode().Perm())
	}
}

func TestRevisionConflictIsFailClosed(t *testing.T) {
	store := New(t.TempDir() + "/jobs.json")
	store.Now = testClock()
	if _, err := store.Create(jobDraft("phone-backup", "Phone backup")); err != nil {
		t.Fatal(err)
	}
	_, err := store.Update("phone-backup", 7, jobDraft("phone-backup", "stale"))
	if err == nil || !strings.Contains(err.Error(), "revision conflict") {
		t.Fatalf("expected revision conflict, got %v", err)
	}
}

func TestStoreRejectsInlineCredentialMaterialWithoutDamagingExistingJobs(t *testing.T) {
	path := t.TempDir() + "/jobs.json"
	store := New(path)
	store.Now = testClock()
	if _, err := store.Create(jobDraft("safe", "Safe")); err != nil {
		t.Fatal(err)
	}

	unsafe := jobDraft("unsafe", "Unsafe")
	unsafe.Operation.Parameters["refresh_token"] = "do-not-persist"
	if _, err := store.Create(unsafe); err == nil {
		t.Fatal("inline token unexpectedly persisted")
	}
	jobs, err := store.List()
	if err != nil || len(jobs) != 1 || jobs[0].JobID != "safe" {
		t.Fatalf("jobs=%+v err=%v", jobs, err)
	}
	data, _ := os.ReadFile(path)
	if strings.Contains(string(data), "do-not-persist") {
		t.Fatalf("secret leaked into store: %s", data)
	}
}

func TestLegacyV1DocumentMigratesAtomicallyToV2(t *testing.T) {
	path := t.TempDir() + "/jobs.json"
	legacy := `{
  "schema_version": 1,
  "jobs": [{
    "job_id": "legacy-backup",
    "name": "Legacy backup",
    "enabled": true,
    "operation": "backup.create",
    "parameters": {"sources": ["/shared/Documents"]},
    "repository_ref": "drive-main",
    "created_at": "2026-09-01T00:00:00Z",
    "updated_at": "2026-09-01T00:00:00Z"
  }]
}`
	if err := os.WriteFile(path, []byte(legacy), 0o600); err != nil {
		t.Fatal(err)
	}
	store := New(path)
	jobs, err := store.List()
	if err != nil {
		t.Fatal(err)
	}
	if len(jobs) != 1 || jobs[0].SchemaVersion != 2 || jobs[0].Operation.SchemaVersion != 2 || jobs[0].Revision != 1 {
		t.Fatalf("migrated=%+v", jobs)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var doc map[string]any
	if err := json.Unmarshal(data, &doc); err != nil {
		t.Fatal(err)
	}
	if doc["schema_version"] != float64(2) {
		t.Fatalf("store was not rewritten to v2: %s", data)
	}
}

func TestDeleteRemovesCurrentAndRevisionHistory(t *testing.T) {
	store := New(t.TempDir() + "/jobs.json")
	store.Now = testClock()
	if _, err := store.Create(jobDraft("phone", "Phone")); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Update("phone", 1, jobDraft("phone", "Phone 2")); err != nil {
		t.Fatal(err)
	}
	deleted, err := store.Delete("phone")
	if err != nil || !deleted {
		t.Fatalf("deleted=%v err=%v", deleted, err)
	}
	revisions, err := store.Revisions("phone")
	if err != nil || len(revisions) != 0 {
		t.Fatalf("revisions=%+v err=%v", revisions, err)
	}
}
