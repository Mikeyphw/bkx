package bridge

import "testing"

func TestSnapshotMaintenanceStaysAdditiveToFrozenV1Bridge(t *testing.T) {
	frozen := map[string]bool{}
	for _, op := range SupportedOperations() {
		frozen[op] = true
	}
	extensions := map[string]bool{}
	for _, op := range ExtensionOperations() {
		extensions[op] = true
	}
	for _, op := range []string{"snapshots.list", "snapshots.show", "snapshots.diff", "snapshots.restore", "snapshots.forget", "repository.check", "repository.prune", "retention.set", "retention.show", "retention.clear", "retention.apply"} {
		if frozen[op] {
			t.Fatalf("post-v1 operation leaked into frozen registry: %s", op)
		}
		if !extensions[op] {
			t.Fatalf("extension operation missing: %s", op)
		}
	}
}
