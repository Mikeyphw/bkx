package models

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestRunRecordUsesFrozenSnakeCaseJSON(t *testing.T) {
	record := RunRecord{RunID: "r1", StartedAt: "2026-09-01T00:00:00", Operation: "backup", Status: "started", SourcePaths: []string{}}
	data, err := json.Marshal(record)
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	for _, key := range []string{"archive_path", "bytes_processed", "completed_at", "destination_path", "error_message", "files_count", "operation", "run_id", "source_paths", "spec_snapshot", "started_at", "status"} {
		if !strings.Contains(text, `"`+key+`"`) {
			t.Fatalf("missing JSON key %s in %s", key, text)
		}
	}
}
