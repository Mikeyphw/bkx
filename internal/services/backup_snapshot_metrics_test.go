package services

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestSnapshotResultPublishesIncrementalMetricsMetadata(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("fake restic helper uses a POSIX shell")
	}
	root := t.TempDir()
	fake := filepath.Join(root, "restic")
	script := `#!/bin/sh
printf '%s\n' '{"message_type":"status","files_done":4,"bytes_done":1000,"percent_done":1}'
printf '%s\n' '{"message_type":"summary","snapshot_id":"abc123","total_files_processed":4,"total_bytes_processed":1000,"data_added":250}'
`
	if err := os.WriteFile(fake, []byte(script), 0o700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("BKX_RESTIC_PATH", fake)
	t.Setenv("RESTIC_PASSWORD", "test-only")

	source := filepath.Join(root, "source")
	if err := os.MkdirAll(source, 0o700); err != nil {
		t.Fatal(err)
	}
	payload, _ := json.Marshal(map[string]any{
		"engine":     "restic",
		"repository": filepath.Join(root, "repository"),
		"sources":    []string{source},
		"verify":     false,
		"dry_run":    false,
	})
	result, err := New(nil).Dispatch(OperationContext{RequestID: "metrics-run"}, "backup.snapshot", payload)
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Artifacts) != 1 {
		t.Fatalf("artifacts=%#v", result.Artifacts)
	}
	metadata := result.Artifacts[0].Metadata
	for key, want := range map[string]string{
		"snapshot_id":     "abc123",
		"files_processed": "4",
		"bytes_processed": "1000",
		"data_added":      "250",
		"bytes_reused":    "750",
	} {
		if metadata[key] != want {
			t.Fatalf("metadata[%s]=%q want %q; all=%#v", key, metadata[key], want, metadata)
		}
	}
}
