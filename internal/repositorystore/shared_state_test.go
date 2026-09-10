package repositorystore

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

	legacy := New(statepaths.LegacyRepositoryStorePath(home))
	repo := models.RepositoryDefinition{
		SchemaVersion: models.RepositoryDefinitionSchemaVersion,
		RepositoryID:  "legacy-repo", Name: "Legacy repository", Engine: "restic", Backend: models.RepositoryBackendLocal,
		Location: filepath.Join(t.TempDir(), "repo"),
	}
	if _, err := legacy.Create(repo); err != nil {
		t.Fatal(err)
	}

	store := Default(home)
	if store.Path != filepath.Join(shared, "repositories", "repositories.json") {
		t.Fatalf("unexpected canonical path: %s", store.Path)
	}
	repos, err := store.List()
	if err != nil {
		t.Fatal(err)
	}
	if len(repos) != 1 || repos[0].RepositoryID != "legacy-repo" {
		t.Fatalf("legacy repository was not seeded: %#v", repos)
	}
	if _, err := os.Stat(statepaths.LegacyRepositoryStorePath(home)); err != nil {
		t.Fatalf("legacy source must remain intact: %v", err)
	}
}
