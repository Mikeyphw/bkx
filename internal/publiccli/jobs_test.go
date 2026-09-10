package publiccli

import (
	"bkx/internal/models"
	"testing"
)

func TestReplayOperationUnwrapsBackupSnapshot(t *testing.T) {
	r := models.RunRecord{Operation: "backup", SpecSnapshot: map[string]any{"type": "BackupSpec", "values": map[string]any{"sources": []any{"/tmp/a"}, "destination": "/tmp/out", "_archive_path": "/tmp/out/a.bakery"}}}
	op, err := replayOperation(r)
	if err != nil {
		t.Fatal(err)
	}
	if op.Operation != "backup.create" {
		t.Fatalf("operation=%q", op.Operation)
	}
	if _, ok := op.Parameters["_archive_path"]; ok {
		t.Fatal("resolved archive path leaked into replay")
	}
}

func TestApplySetsSupportsNestedJSONValues(t *testing.T) {
	p := map[string]any{"compression": map[string]any{"level": 3}}
	if err := applySets(p, []string{"compression.level=7", "verify=false", "name=nightly"}); err != nil {
		t.Fatal(err)
	}
	if p["verify"] != false {
		t.Fatalf("verify=%v", p["verify"])
	}
	c := p["compression"].(map[string]any)
	if c["level"].(float64) != 7 {
		t.Fatalf("level=%v", c["level"])
	}
	if p["name"] != "nightly" {
		t.Fatalf("name=%v", p["name"])
	}
}
