package runstore

import (
	"os"
	"strings"
	"testing"
)

func ptr[T any](v T) *T { return &v }

func TestPythonStyleFixtureRoundTrip(t *testing.T) {
	path := t.TempDir() + "/runs.jsonl"
	fixture := `{"archive_path":null,"bytes_processed":12,"completed_at":"2026-08-31T12:00:01","destination_path":"/backup","error_message":null,"files_count":2,"operation":"backup","run_id":"py-1","source_paths":["/src"],"spec_snapshot":{"verify":true},"started_at":"2026-08-31T12:00:00","status":"success"}` + "\n"
	if err := os.WriteFile(path, []byte(fixture), 0o600); err != nil {
		t.Fatal(err)
	}
	s, err := New(path, 0, false)
	if err != nil {
		t.Fatal(err)
	}
	records, err := s.Load()
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 1 || records[0].RunID != "py-1" {
		t.Fatalf("records=%#v", records)
	}
	_, err = s.RecordStart("go-1", "restore", []string{"/archive"}, ptr("/restore"), nil, map[string]any{"fresh_dir": false})
	if err != nil {
		t.Fatal(err)
	}
	count, err := s.Count("")
	if err != nil || count != 2 {
		t.Fatalf("count=%d err=%v", count, err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(data), `"run_id":"go-1"`) {
		t.Fatalf("missing go record: %s", data)
	}
}

func TestMalformedAndDuplicateRecordsQuarantined(t *testing.T) {
	path := t.TempDir() + "/runs.jsonl"
	line := `{"archive_path":null,"bytes_processed":null,"completed_at":null,"destination_path":null,"error_message":null,"files_count":null,"operation":"backup","run_id":"r1","source_paths":[],"spec_snapshot":null,"started_at":"2026-08-31T12:00:00","status":"started"}`
	if err := os.WriteFile(path, []byte(line+"\nnot-json\n"+line+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	s, _ := New(path, 0, false)
	records, err := s.Load()
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 1 {
		t.Fatalf("records=%d", len(records))
	}
	q, err := os.ReadFile(path + ".corrupt")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(q), "duplicate run_id") || !strings.Contains(string(q), "invalid character") {
		t.Fatalf("quarantine=%s", q)
	}
}

func TestRecoverInterruptedAndCompletion(t *testing.T) {
	path := t.TempDir() + "/runs.jsonl"
	s, _ := New(path, 0, false)
	if _, err := s.RecordStart("r1", "backup", []string{}, nil, nil, nil); err != nil {
		t.Fatal(err)
	}
	recovered, err := s.RecoverInterrupted()
	if err != nil || recovered != 1 {
		t.Fatalf("recovered=%d err=%v", recovered, err)
	}
	r, err := s.Get("r1")
	if err != nil || r == nil || r.Status != "interrupted" || r.CompletedAt == nil {
		t.Fatalf("r=%#v err=%v", r, err)
	}
}

func TestRetentionKeepsNewestInOriginalOrder(t *testing.T) {
	path := t.TempDir() + "/runs.jsonl"
	s, _ := New(path, 2, false)
	for _, id := range []string{"a", "b", "c"} {
		if _, err := s.RecordStart(id, "backup", []string{}, nil, nil, nil); err != nil {
			t.Fatal(err)
		}
	}
	records, err := s.Load()
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 2 || records[0].RunID != "b" || records[1].RunID != "c" {
		t.Fatalf("records=%#v", records)
	}
}

func TestValidationAndNotFound(t *testing.T) {
	s, _ := New(t.TempDir()+"/runs.jsonl", 0, false)
	if _, err := s.RecordStart("r", "bad", nil, nil, nil, nil); err == nil {
		t.Fatal("bad operation accepted")
	}
	if _, err := s.RecordCompletion("missing", "success", nil, nil, nil); err == nil {
		t.Fatal("missing run accepted")
	}
	negative := int64(-1)
	if _, err := s.RecordCompletion("missing", "success", &negative, nil, nil); err == nil {
		t.Fatal("negative accepted")
	}
}
