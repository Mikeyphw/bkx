package services

import (
	"path/filepath"
	"testing"

	"bkx/internal/jobstore"
	"bkx/internal/repositorystore"
)

func TestSavedJobBridgeCrudUsesSharedStoreAndKeepsRevisions(t *testing.T) {
	root := t.TempDir()
	d := NewWithStores(
		mustStore(t, filepath.Join(root, "runs.jsonl")),
		repositorystore.New(filepath.Join(root, "repositories.json")),
		jobstore.New(filepath.Join(root, "jobs.json")),
	)

	create := map[string]any{
		"job_id":  "phone-backup",
		"name":    "Phone backup",
		"enabled": true,
		"operation": map[string]any{
			"schema_version":  2,
			"operation":       "backup.snapshot",
			"parameters":      map[string]any{"engine": "restic", "sources": []string{"/tmp/source"}},
			"repository_ref":  "phone-drive",
			"credential_refs": []map[string]any{{"id": "restic-password"}},
		},
	}
	created, err := d.Dispatch(OperationContext{}, "jobs.create", raw(create))
	if err != nil {
		t.Fatal(err)
	}
	if len(created.Artifacts) != 1 || created.Artifacts[0].Label != "saved_job" {
		t.Fatalf("created=%+v", created)
	}
	if created.Artifacts[0].Metadata["revision"] != "1" {
		t.Fatalf("created revision=%q", created.Artifacts[0].Metadata["revision"])
	}
	if created.Artifacts[0].Metadata["saved_job_json"] == "" {
		t.Fatal("missing complete non-secret saved_job_json")
	}

	listed, err := d.Dispatch(OperationContext{}, "jobs.list", raw(map[string]any{}))
	if err != nil {
		t.Fatal(err)
	}
	if len(listed.Artifacts) != 1 || listed.Artifacts[0].Path != "phone-backup" {
		t.Fatalf("listed=%+v", listed)
	}

	update := create
	update["name"] = "Phone backup updated"
	update["expected_revision"] = 1
	updated, err := d.Dispatch(OperationContext{}, "jobs.update", raw(update))
	if err != nil {
		t.Fatal(err)
	}
	if updated.Artifacts[0].Metadata["revision"] != "2" {
		t.Fatalf("updated revision=%q", updated.Artifacts[0].Metadata["revision"])
	}

	cloned, err := d.Dispatch(OperationContext{}, "jobs.clone", raw(map[string]any{
		"source_job_id": "phone-backup", "target_job_id": "phone-backup-copy", "name": "Phone backup copy",
	}))
	if err != nil {
		t.Fatal(err)
	}
	if cloned.Artifacts[0].Path != "phone-backup-copy" {
		t.Fatalf("cloned=%+v", cloned)
	}

	deleted, err := d.Dispatch(OperationContext{}, "jobs.delete", raw(map[string]any{"job_id": "phone-backup-copy"}))
	if err != nil {
		t.Fatal(err)
	}
	if deleted.Artifacts[0].Label != "saved_job_deleted" {
		t.Fatalf("deleted=%+v", deleted)
	}
}

func TestSavedJobBridgeRejectsInlineSecretMaterial(t *testing.T) {
	root := t.TempDir()
	d := NewWithStores(
		mustStore(t, filepath.Join(root, "runs.jsonl")),
		repositorystore.New(filepath.Join(root, "repositories.json")),
		jobstore.New(filepath.Join(root, "jobs.json")),
	)
	_, err := d.Dispatch(OperationContext{}, "jobs.create", raw(map[string]any{
		"job_id": "bad", "name": "Bad job",
		"operation": map[string]any{
			"schema_version": 2,
			"operation":      "backup.snapshot",
			"parameters":     map[string]any{"password": "must-not-persist"},
		},
	}))
	if err == nil {
		t.Fatal("expected inline secret rejection")
	}
}
