package publiccli

import (
	"path/filepath"
	"testing"

	"bkx/internal/jobstore"
	"bkx/internal/models"
	"bkx/internal/repositorystore"
)

func snapshotContextTestApp(t *testing.T) *appContext {
	t.Helper()
	dir := t.TempDir()
	return &appContext{
		jobs:         jobstore.New(filepath.Join(dir, "jobs.json")),
		repositories: repositorystore.New(filepath.Join(dir, "repositories.json")),
	}
}

func TestSnapshotContextResolvesSavedJobRepositoryReference(t *testing.T) {
	c := snapshotContextTestApp(t)
	credential := "restic-phone"
	if _, err := c.repositories.Create(models.RepositoryDefinition{
		RepositoryID:  "phone-repo",
		Name:          "Phone repository",
		Engine:        models.BackupEngineRestic,
		Backend:       models.RepositoryBackendLocal,
		Location:      filepath.Join(t.TempDir(), "repo"),
		CredentialRef: &credential,
	}); err != nil {
		t.Fatal(err)
	}
	repositoryRef := "phone-repo"
	if _, err := c.jobs.Create(models.SavedJob{
		JobID:   "phone-backup",
		Name:    "Phone backup",
		Enabled: true,
		Operation: models.OperationSpecV2{
			SchemaVersion: models.OperationSpecV2SchemaVersion,
			Operation:     "backup.snapshot",
			Parameters:    map[string]any{"sources": []any{"/storage/emulated/0/Documents"}},
			RepositoryRef: &repositoryRef,
		},
	}); err != nil {
		t.Fatal(err)
	}

	access, err := snapshotContextAccess(c, "phone-backup", nil)
	if err != nil {
		t.Fatal(err)
	}
	if access.Repository == "" || access.CredentialRef == nil || *access.CredentialRef != credential {
		t.Fatalf("unexpected access: %+v", access)
	}
}

func TestSnapshotContextAcceptsRepositoryIDAndCredentialOverride(t *testing.T) {
	c := snapshotContextTestApp(t)
	credential := "restic-default"
	created, err := c.repositories.Create(models.RepositoryDefinition{
		RepositoryID:  "drive-main",
		Name:          "Drive main",
		Engine:        models.BackupEngineRestic,
		Backend:       models.RepositoryBackendRclone,
		Location:      "rclone://drive/BKX/restic",
		CredentialRef: &credential,
	})
	if err != nil {
		t.Fatal(err)
	}
	override := "restic-override"
	access, err := snapshotContextAccess(c, "drive-main", &override)
	if err != nil {
		t.Fatal(err)
	}
	if access.Repository != created.Location || access.CredentialRef == nil || *access.CredentialRef != override {
		t.Fatalf("unexpected access: %+v", access)
	}
}

func TestSnapshotAccessAndPositionalsRetainsExplicitFlagForm(t *testing.T) {
	c := snapshotContextTestApp(t)
	a := leafArgs{Positionals: []string{"latest"}, Values: map[string][]string{"repository": {"/tmp/repo"}}}
	access, positionals, err := snapshotAccessAndPositionals(c, a, 1)
	if err != nil {
		t.Fatal(err)
	}
	if access.Repository != "/tmp/repo" || len(positionals) != 1 || positionals[0] != "latest" {
		t.Fatalf("access=%+v positionals=%v", access, positionals)
	}
}
