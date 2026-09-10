package services

import (
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"

	"bkx/internal/models"
	"bkx/internal/repositorystore"
)

func TestNamedRepositoryServiceLifecycleAndSnapshotResolution(t *testing.T) {
	store := repositorystore.New(filepath.Join(t.TempDir(), "repositories.json"))
	dispatcher := NewWithRepositories(nil, store)

	add := map[string]any{
		"repository_id":  "phone-drive",
		"name":           "Phone Drive",
		"engine":         models.BackupEngineRestic,
		"backend":        models.RepositoryBackendManagedGoogleDrive,
		"location":       "rclone://bkx-google-drive/BKX/restic/phone",
		"credential_ref": "phone-restic-password",
	}
	raw, _ := json.Marshal(add)
	result, err := dispatcher.Dispatch(OperationContext{RequestID: "add-1"}, "repositories.add", raw)
	if err != nil || result.Status != StatusSuccess || len(result.Artifacts) != 1 {
		t.Fatalf("unexpected add result: %#v %v", result, err)
	}
	artifact := result.Artifacts[0]
	if artifact.Metadata["repository_id"] != "phone-drive" || artifact.Metadata["credential_ref"] != "phone-restic-password" {
		t.Fatalf("repository metadata lost reference identity: %#v", artifact)
	}
	for _, value := range []string{artifact.Path, artifact.Metadata["credential_ref"]} {
		if strings.Contains(value, "access_token") || strings.Contains(value, "Bearer ") {
			t.Fatalf("repository result leaked cloud credential material: %q", value)
		}
	}

	source := t.TempDir()
	snapshotPayload, _ := json.Marshal(map[string]any{
		"engine":         "restic",
		"repository_ref": "phone-drive",
		"sources":        []string{source},
		"dry_run":        true,
	})
	snapshot, err := dispatcher.Dispatch(OperationContext{RequestID: "plan-1"}, "backup.snapshot", snapshotPayload)
	if err != nil || snapshot.Status != StatusSuccess || len(snapshot.Artifacts) != 1 {
		t.Fatalf("named repository did not resolve for snapshot planning: %#v %v", snapshot, err)
	}
	if !strings.Contains(snapshot.Artifacts[0].Path, "snapshot-plan") {
		t.Fatalf("unexpected snapshot plan artifact: %#v", snapshot.Artifacts[0])
	}

	list, err := dispatcher.Dispatch(OperationContext{RequestID: "list-1"}, "repositories.list", json.RawMessage(`{}`))
	if err != nil || len(list.Artifacts) != 1 || list.Artifacts[0].Metadata["backend"] != models.RepositoryBackendManagedGoogleDrive {
		t.Fatalf("unexpected repository list: %#v %v", list, err)
	}
}
