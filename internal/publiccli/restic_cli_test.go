package publiccli

import (
	"testing"

	"bkx/internal/models"
)

func TestBR0506CommandSurfaceOwnsResticRepositoryAndSnapshotReplay(t *testing.T) {
	if _, ok := commandHandlers["repository init"]; !ok {
		t.Fatal("repository init command missing")
	}
	record := models.RunRecord{
		Operation: "backup",
		SpecSnapshot: map[string]any{
			"engine":         "restic",
			"repository":     "/tmp/repo",
			"sources":        []any{"/tmp/source"},
			"credential_ref": "phone-repo",
			"verify":         false,
			"dry_run":        false,
		},
	}
	op, err := replayOperation(record)
	if err != nil {
		t.Fatal(err)
	}
	if op.Operation != "backup.snapshot" {
		t.Fatalf("operation=%s", op.Operation)
	}
	if len(op.CredentialRefs) != 1 || op.CredentialRefs[0].ID != "phone-repo" {
		t.Fatalf("credential refs=%+v", op.CredentialRefs)
	}
	if _, leaked := op.Parameters["credential_ref"]; leaked {
		t.Fatal("credential ref should be lifted out of replay parameters")
	}
}
