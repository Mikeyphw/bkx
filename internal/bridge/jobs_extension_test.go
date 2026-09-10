package bridge

import (
	"path/filepath"
	"testing"

	"bkx/internal/runstore"
)

func TestAndroidReadinessAdvertisesSharedSavedJobsWithoutMutatingFrozenV1Registry(t *testing.T) {
	store, err := runstore.New(filepath.Join(t.TempDir(), "runs.jsonl"), 20, false)
	if err != nil {
		t.Fatal(err)
	}
	runtime := NewAndroid(store)
	caps := runtime.Readiness().Capabilities
	for _, want := range []string{"jobs.shared_store", "jobs.crud", "jobs.run", "jobs.plan"} {
		if !contains(caps, want) {
			t.Fatalf("missing readiness capability %q in %v", want, caps)
		}
	}
	for _, postV1 := range []string{"jobs.create", "jobs.list", "jobs.run"} {
		if contains(SupportedOperations(), postV1) {
			t.Fatalf("frozen v1 SupportedOperations unexpectedly contains %q", postV1)
		}
		if !contains(ExecutableOperations(), postV1) {
			t.Fatalf("extension operation %q is not executable", postV1)
		}
	}
}
