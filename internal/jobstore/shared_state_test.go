package jobstore

import (
	"os"
	"path/filepath"
	"testing"

	"bkx/internal/models"
	"bkx/internal/statepaths"
)

func TestDefaultStoreUsesExplicitSharedStateAndSeedsLegacyDocument(t *testing.T) {
	home := t.TempDir()
	shared := filepath.Join(t.TempDir(), "shared")
	t.Setenv(statepaths.EnvStateDir, shared)

	legacy := New(statepaths.LegacyJobStorePath(home))
	job := models.SavedJob{
		SchemaVersion: models.SavedJobSchemaVersion,
		JobID:         "legacy-job", Name: "Legacy job", Revision: 1, Enabled: true,
		Operation: models.OperationSpecV2{SchemaVersion: models.OperationSpecV2SchemaVersion, Operation: "backup.plan", Parameters: map[string]any{}},
	}
	if _, err := legacy.Create(job); err != nil {
		t.Fatal(err)
	}

	store := Default(home)
	if store.Path != filepath.Join(shared, "jobs", "jobs.json") {
		t.Fatalf("unexpected canonical path: %s", store.Path)
	}
	jobs, err := store.List()
	if err != nil {
		t.Fatal(err)
	}
	if len(jobs) != 1 || jobs[0].JobID != "legacy-job" {
		t.Fatalf("legacy job was not seeded: %#v", jobs)
	}
	if _, err := os.Stat(statepaths.LegacyJobStorePath(home)); err != nil {
		t.Fatalf("legacy source must remain intact: %v", err)
	}
}
