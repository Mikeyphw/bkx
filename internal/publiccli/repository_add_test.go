package publiccli

import (
	"bytes"
	"path/filepath"
	"testing"

	"bkx/internal/models"
	"bkx/internal/repositorystore"
	"bkx/internal/statepaths"
)

func TestRepositoryAddLocalInfersLocalBackend(t *testing.T) {
	home := t.TempDir()
	shared := filepath.Join(t.TempDir(), "shared")
	repositoryPath := filepath.Join(t.TempDir(), "restic")
	t.Setenv("HOME", home)
	t.Setenv(statepaths.EnvStateDir, shared)

	var stdout, stderr bytes.Buffer
	code := Run([]string{"--json", "repository", "add", "local-main", "--location", repositoryPath}, &stdout, &stderr)
	if code != 0 {
		t.Fatalf("code=%d stderr=%q", code, stderr.String())
	}
	def, err := repositorystore.Default(home).Get("local-main")
	if err != nil {
		t.Fatal(err)
	}
	if def == nil || def.Backend != models.RepositoryBackendLocal {
		t.Fatalf("repository=%#v", def)
	}
}
