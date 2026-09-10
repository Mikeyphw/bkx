package models

import (
	"encoding/json"
	"strings"
	"testing"
)

func validSavedJob() SavedJob {
	return SavedJob{
		SchemaVersion: SavedJobSchemaVersion,
		JobID:         "phone-backup",
		Name:          "Phone backup",
		Revision:      1,
		Enabled:       true,
		Operation: OperationSpecV2{
			SchemaVersion: OperationSpecV2SchemaVersion,
			Operation:     "backup.create",
			Parameters: map[string]any{
				"sources": []any{"/storage/emulated/0/Documents"},
				"verify":  true,
			},
			RepositoryRef: stringPtr("drive-main"),
			CredentialRefs: []SecretReference{
				{ID: "restic-drive-main", Purpose: "repository encryption"},
			},
		},
		CreatedAt: "2026-09-10T10:00:00Z",
		UpdatedAt: "2026-09-10T10:00:00Z",
	}
}

func stringPtr(value string) *string { return &value }

func TestSavedJobV2JSONContract(t *testing.T) {
	job := validSavedJob()
	if err := ValidateSavedJob(job); err != nil {
		t.Fatal(err)
	}
	data, err := json.Marshal(job)
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	for _, key := range []string{
		"schema_version", "job_id", "name", "revision", "enabled", "operation",
		"parameters", "repository_ref", "credential_refs", "created_at", "updated_at",
	} {
		if !strings.Contains(text, `"`+key+`"`) {
			t.Fatalf("missing JSON key %s in %s", key, text)
		}
	}
	if strings.Contains(text, "repository-password") {
		t.Fatalf("unexpected secret material in %s", text)
	}
}

func TestSavedOperationRejectsInlineSecretsRecursively(t *testing.T) {
	cases := []map[string]any{
		{"password": "secret"},
		{"auth": map[string]any{"access_token": "secret"}},
		{"nested": []any{map[string]any{"client-secret": "secret"}}},
		{"credentials": map[string]any{"user": "mikey"}},
	}
	for _, parameters := range cases {
		spec := NewOperationSpecV2("backup.create", parameters)
		if err := ValidateOperationSpecV2(spec); err == nil || !strings.Contains(err.Error(), "credential_refs") {
			t.Fatalf("inline secret unexpectedly accepted: %#v err=%v", parameters, err)
		}
	}
}

func TestSavedOperationAllowsSecretReferencesButNotDuplicateReferences(t *testing.T) {
	spec := NewOperationSpecV2("backup.create", map[string]any{"source": "/shared/Documents"})
	spec.CredentialRefs = []SecretReference{{ID: "drive-main", Purpose: "cloud auth"}}
	if err := ValidateOperationSpecV2(spec); err != nil {
		t.Fatal(err)
	}
	spec.CredentialRefs = append(spec.CredentialRefs, SecretReference{ID: "drive-main"})
	if err := ValidateOperationSpecV2(spec); err == nil {
		t.Fatal("duplicate credential reference unexpectedly accepted")
	}
}

func TestSavedJobRejectsUnstableIdentityAndTimestampOrdering(t *testing.T) {
	job := validSavedJob()
	job.JobID = "Phone Backup"
	if err := ValidateSavedJob(job); err == nil {
		t.Fatal("unstable job id unexpectedly accepted")
	}
	job = validSavedJob()
	job.UpdatedAt = "2026-09-09T10:00:00Z"
	if err := ValidateSavedJob(job); err == nil {
		t.Fatal("updated_at before created_at unexpectedly accepted")
	}
}
