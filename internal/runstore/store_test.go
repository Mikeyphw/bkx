package runstore

import (
	"bkx/internal/executioncontrol"
	"bkx/internal/models"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
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

func TestMergeLegacyHistoryUnionsUniqueRunsWithoutDeletingLegacy(t *testing.T) {
	root := t.TempDir()
	canonicalPath := root + "/shared/runs.jsonl"
	legacyPath := root + "/legacy/runs.jsonl"
	canonical, err := New(canonicalPath, 0, false)
	if err != nil {
		t.Fatal(err)
	}
	legacy, err := New(legacyPath, 0, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := canonical.RecordStart("shared-only", "backup", []string{"/shared"}, nil, nil, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := legacy.RecordStart("legacy-only", "backup", []string{"/legacy"}, nil, nil, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := legacy.RecordStart("shared-only", "backup", []string{"/legacy-conflict"}, nil, nil, nil); err != nil {
		t.Fatal(err)
	}

	result, err := MergeLegacyHistory(canonicalPath, legacyPath)
	if err != nil {
		t.Fatal(err)
	}
	if result.Added != 1 || result.Duplicates != 1 {
		t.Fatalf("merge result=%#v", result)
	}
	records, err := canonical.Load()
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 2 {
		t.Fatalf("canonical records=%#v", records)
	}
	var shared *models.RunRecord
	for i := range records {
		if records[i].RunID == "shared-only" {
			shared = &records[i]
		}
	}
	if shared == nil || len(shared.SourcePaths) != 1 || shared.SourcePaths[0] != "/shared" {
		t.Fatalf("canonical duplicate must win: %#v", shared)
	}
	legacyRecords, err := legacy.Load()
	if err != nil {
		t.Fatal(err)
	}
	if len(legacyRecords) != 2 {
		t.Fatalf("legacy history changed: %#v", legacyRecords)
	}
}

func TestMergeLegacyHistoryNoopsForSameOrMissingPath(t *testing.T) {
	path := t.TempDir() + "/runs.jsonl"
	if result, err := MergeLegacyHistory(path, path); err != nil || result.Added != 0 || result.Duplicates != 0 {
		t.Fatalf("same-path merge result=%#v err=%v", result, err)
	}
	if result, err := MergeLegacyHistory(path, t.TempDir()+"/missing.jsonl"); err != nil || result.Added != 0 || result.Duplicates != 0 {
		t.Fatalf("missing merge result=%#v err=%v", result, err)
	}
}

func TestMergeLegacyHistoryDoesNotRewriteMalformedLegacySource(t *testing.T) {
	root := t.TempDir()
	canonicalPath := root + "/shared/runs.jsonl"
	legacyPath := root + "/legacy/runs.jsonl"
	if err := os.MkdirAll(filepath.Dir(legacyPath), 0o700); err != nil {
		t.Fatal(err)
	}
	valid := `{"archive_path":null,"bytes_processed":null,"completed_at":null,"destination_path":null,"error_message":null,"files_count":null,"operation":"backup","run_id":"legacy-valid","source_paths":[],"spec_snapshot":null,"started_at":"2026-08-31T12:00:00","status":"started"}`
	original := []byte(valid + "\nnot-json\n")
	if err := os.WriteFile(legacyPath, original, 0o600); err != nil {
		t.Fatal(err)
	}
	result, err := MergeLegacyHistory(canonicalPath, legacyPath)
	if err != nil {
		t.Fatal(err)
	}
	if result.Added != 1 {
		t.Fatalf("merge result=%#v", result)
	}
	after, err := os.ReadFile(legacyPath)
	if err != nil {
		t.Fatal(err)
	}
	if string(after) != string(original) {
		t.Fatalf("legacy history was rewritten: %q", after)
	}
	if _, err := os.Stat(legacyPath + ".corrupt"); !os.IsNotExist(err) {
		t.Fatalf("migration must not quarantine/rewrite legacy source; stat err=%v", err)
	}
}

func TestSharedRecoveryPreservesLiveLeaseAndRecoversUnownedRun(t *testing.T) {
	root := t.TempDir()
	control := &executioncontrol.Store{Path: root + "/execution/control.json"}
	store, err := New(root+"/runs/runs.jsonl", 0, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := control.Acquire("active", "backup.snapshot", "job", "repo", "android"); err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{"active", "orphan"} {
		if _, err := store.RecordStart(id, "backup", nil, nil, nil, nil); err != nil {
			t.Fatal(err)
		}
	}
	count, err := store.RecoverInterruptedWithControl(control)
	if err != nil || count != 1 {
		t.Fatalf("recovered=%d err=%v", count, err)
	}
	active, err := store.Get("active")
	if err != nil || active.Status != "started" || active.CompletedAt != nil {
		t.Fatalf("active run changed: %+v err=%v", active, err)
	}
	orphan, err := store.Get("orphan")
	if err != nil || orphan.Status != "interrupted" {
		t.Fatalf("orphan=%+v err=%v", orphan, err)
	}
	if err := control.Release("active"); err != nil {
		t.Fatal(err)
	}
	count, err = store.RecoverInterruptedWithControl(control)
	if err != nil || count != 1 {
		t.Fatalf("released run recovery=%d err=%v", count, err)
	}
}

func TestDurablePhasesCancellationAndSavedJobReplaySnapshot(t *testing.T) {
	store, err := New(filepath.Join(t.TempDir(), "runs.jsonl"), 0, false)
	if err != nil {
		t.Fatal(err)
	}
	repo := "phone-drive"
	op := models.OperationSpecV2{
		SchemaVersion: models.OperationSpecV2SchemaVersion,
		Operation:     "backup.snapshot",
		Parameters: map[string]any{
			"engine":  "restic",
			"sources": []any{"/source"},
		},
		RepositoryRef:  &repo,
		CredentialRefs: []models.SecretReference{{ID: "restic-phone-drive", Purpose: "restic repository password"}},
	}
	origin, err := store.RecordStartWithOrigin(
		"job-run-1",
		"backup",
		[]string{"/source"},
		nil,
		nil,
		map[string]any{"engine": "restic"},
		&SavedJobOrigin{JobID: "phone-backup", Revision: 3, Operation: op},
	)
	if err != nil {
		t.Fatal(err)
	}
	if origin.Phase != "queued" {
		t.Fatalf("start phase=%s", origin.Phase)
	}
	if got, err := store.RecordPhase("job-run-1", "preparing"); err != nil || got.Phase != "preparing" {
		t.Fatalf("phase=%+v err=%v", got, err)
	}
	if origin.JobID != "phone-backup" || origin.JobRevision != 3 || origin.OperationSpecSnapshot == nil {
		t.Fatalf("origin=%+v", origin)
	}
	replayed, err := ReplayOperation(origin)
	if err != nil {
		t.Fatal(err)
	}
	if replayed.Operation != "backup.snapshot" || replayed.RepositoryRef == nil || *replayed.RepositoryRef != repo || len(replayed.CredentialRefs) != 1 {
		t.Fatalf("replayed=%+v", replayed)
	}
	finished, err := store.RecordCompletion("job-run-1", "cancelled", nil, nil, ptr("cancelled by caller"))
	if err != nil {
		t.Fatal(err)
	}
	if finished.Status != "cancelled" || finished.Phase != "cancelled" {
		t.Fatalf("finished=%+v", finished)
	}
}

func TestLegacyReplayStillWorksWithoutBR20OriginMetadata(t *testing.T) {
	record := models.RunRecord{
		RunID:        "legacy",
		Operation:    "transfer",
		Status:       "success",
		StartedAt:    "2026-09-01T00:00:00Z",
		SourcePaths:  []string{"/a"},
		SpecSnapshot: map[string]any{"sources": []any{"/a"}, "destination": "/b", "direction": "push", "dry_run": false, "overwrite": false, "verify": true},
	}
	op, err := ReplayOperation(record)
	if err != nil {
		t.Fatal(err)
	}
	if op.Operation != "transfer.run" {
		t.Fatalf("operation=%s", op.Operation)
	}
}

func TestBR27RunHistoryPageTokensUTCAndUnknownFields(t *testing.T) {
	path := filepath.Join(t.TempDir(), "runs.jsonl")
	fixture := `{"archive_path":null,"bytes_processed":null,"completed_at":null,"destination_path":null,"error_message":null,"files_count":null,"operation":"backup","run_id":"future","source_paths":[],"spec_snapshot":null,"started_at":"2026-09-01T00:00:00Z","status":"started","future_field":"kept_by_newer_clients"}` + "\n"
	if err := os.WriteFile(path, []byte(fixture), 0o600); err != nil {
		t.Fatal(err)
	}
	store, err := New(path, 0, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Load(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path + ".corrupt"); !os.IsNotExist(err) {
		t.Fatalf("unknown fields must not be quarantined, stat err=%v", err)
	}
	for _, id := range []string{"a", "b", "c"} {
		if _, err := store.RecordStart(id, "backup", nil, nil, nil, nil); err != nil {
			t.Fatal(err)
		}
	}
	page, err := store.ListPage(2, "", "")
	if err != nil || len(page.Records) != 2 || page.NextPageToken == "" {
		t.Fatalf("page=%+v err=%v", page, err)
	}
	second, err := store.ListPage(2, "", page.NextPageToken)
	if err != nil || len(second.Records) == 0 {
		t.Fatalf("second=%+v err=%v", second, err)
	}
	if _, err := time.Parse(time.RFC3339Nano, second.Records[0].StartedAt); err != nil {
		t.Fatalf("started_at is not RFC3339: %s", second.Records[0].StartedAt)
	}
}

func TestBR27ReplayRequiresTerminalCompletedRun(t *testing.T) {
	started := models.RunRecord{RunID: "active", Operation: "transfer", Status: "started", Phase: "running", StartedAt: "2026-09-01T00:00:00Z", SpecSnapshot: map[string]any{"sources": []any{"/a"}, "destination": "/b", "direction": "push", "dry_run": false, "overwrite": false, "verify": true}}
	if IsReplayable(started) {
		t.Fatal("active run must not be replayable")
	}
	completedAt := "2026-09-01T00:01:00Z"
	started.Status = "success"
	started.CompletedAt = &completedAt
	if !IsReplayable(started) {
		t.Fatal("terminal completed transfer should be replayable")
	}
}
