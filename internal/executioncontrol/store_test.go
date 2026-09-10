package executioncontrol

import (
	"path/filepath"
	"testing"
)

func TestSameJobAndRepositoryAreMutuallyExclusive(t *testing.T) {
	s := &Store{Path: filepath.Join(t.TempDir(), "control.json")}
	if _, err := s.Acquire("r1", "jobs.run", "phone", "repo", "cli"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Acquire("r2", "jobs.run", "phone", "other", "tasker"); err == nil {
		t.Fatal("same job should conflict")
	}
	if _, err := s.Acquire("r3", "backup.snapshot", "", "repo", "android"); err == nil {
		t.Fatal("same repository should conflict")
	}
	if err := s.Release("r1"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Acquire("r3", "backup.snapshot", "", "repo", "android"); err != nil {
		t.Fatal(err)
	}
}

func TestCancellationPersistsAcrossStoreInstances(t *testing.T) {
	path := filepath.Join(t.TempDir(), "control.json")
	a := &Store{Path: path}
	b := &Store{Path: path}
	if err := a.RequestCancel("run-1"); err != nil {
		t.Fatal(err)
	}
	if !b.CancelRequested("run-1") {
		t.Fatal("shared cancellation marker missing")
	}
	if err := b.ClearCancel("run-1"); err != nil {
		t.Fatal(err)
	}
	if a.CancelRequested("run-1") {
		t.Fatal("cancel marker should be cleared")
	}
}

func TestPreCancellationSurvivesLeaseAcquisition(t *testing.T) {
	store := &Store{Path: filepath.Join(t.TempDir(), "control.json")}
	if err := store.RequestCancel("request-pre"); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Acquire("request-pre", "jobs.run", "job-a", "repo-a", "cli"); err != nil {
		t.Fatal(err)
	}
	if !store.CancelRequested("request-pre") {
		t.Fatal("acquire erased pre-existing cancellation")
	}
}

func TestDuplicateRequestInSameProcessIsRejected(t *testing.T) {
	store := &Store{Path: filepath.Join(t.TempDir(), "control.json")}
	if _, err := store.Acquire("same", "jobs.run", "job-a", "repo-a", "android"); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Acquire("same", "jobs.run", "job-a", "repo-a", "android"); err == nil {
		t.Fatal("duplicate request should be rejected even from same process")
	}
}
