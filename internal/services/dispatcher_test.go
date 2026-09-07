package services

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"testing"

	"bkx/internal/archiveio"
	"bkx/internal/models"
	"bkx/internal/runstore"
)

func raw(v any) json.RawMessage { b, _ := json.Marshal(v); return b }
func mustStore(t *testing.T, path string) *runstore.Store {
	t.Helper()
	s, err := runstore.New(path, 0, false)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestSupportedOperationsMatchFrozenBridge(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("..", "..", "migration", "contracts", "v1", "bridge.json"))
	if err != nil {
		t.Fatal(err)
	}
	var doc struct {
		Advertised []string `json:"advertisedOperations"`
	}
	if err := json.Unmarshal(data, &doc); err != nil {
		t.Fatal(err)
	}
	got := SupportedOperations()
	sort.Strings(got)
	sort.Strings(doc.Advertised)
	if !reflect.DeepEqual(got, doc.Advertised) {
		t.Fatalf("supported=%v frozen=%v", got, doc.Advertised)
	}
}

func TestFilesystemAndStorageDispatch(t *testing.T) {
	root := t.TempDir()
	file := filepath.Join(root, "A.txt")
	if err := os.WriteFile(file, []byte("alpha"), 0o600); err != nil {
		t.Fatal(err)
	}
	d := New(nil)
	d.StorageLocations = []StorageLocation{{Label: "fixture", Path: root}, {Label: "missing", Path: filepath.Join(root, "nope")}}
	stat, err := d.Dispatch(OperationContext{RequestID: "stat"}, "filesystem.stat", raw(map[string]any{"path": file}))
	if err != nil {
		t.Fatal(err)
	}
	if stat.Status != "success" || len(stat.Artifacts) != 1 || stat.Artifacts[0].Label != "file" {
		t.Fatalf("stat=%+v", stat)
	}
	list, err := d.Dispatch(OperationContext{RequestID: "list"}, "filesystem.list", raw(map[string]any{"path": root, "show_hidden": true, "limit": 100, "offset": 0}))
	if err != nil {
		t.Fatal(err)
	}
	if len(list.Artifacts) != 1 || list.Artifacts[0].Path != file {
		t.Fatalf("list=%+v", list)
	}
	storage, err := d.Dispatch(OperationContext{}, "storage.locations", raw(map[string]any{}))
	if err != nil {
		t.Fatal(err)
	}
	if len(storage.Artifacts) != 1 || storage.Artifacts[0].Label != "fixture" {
		t.Fatalf("storage=%+v", storage)
	}
}

func TestBackupArchiveRestoreRunsAndArchiveIndexDispatch(t *testing.T) {
	root := t.TempDir()
	src := filepath.Join(root, "src")
	dst := filepath.Join(root, "backups")
	if err := os.MkdirAll(src, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(dst, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(src, "a.txt"), []byte("hello"), 0o600); err != nil {
		t.Fatal(err)
	}
	store := mustStore(t, filepath.Join(root, "runs.jsonl"))
	d := New(store)
	payload := map[string]any{"source": map[string]any{"path": src}, "destination": map[string]any{"path": dst}, "filters": map[string]any{"include_patterns": []string{}, "exclude_patterns": []string{}, "follow_symlinks": false}, "archive": map[string]any{"archive_name": "fixture", "compression": "gzip", "compression_level": 3, "split": map[string]any{"enabled": false}, "verification": map[string]any{"verify_after_create": true}}}
	plan, err := d.Dispatch(OperationContext{RequestID: "backup-plan"}, "backup.plan", raw(payload))
	if err != nil {
		t.Fatal(err)
	}
	if plan.Status != "success" || len(plan.Artifacts) != 1 || plan.Artifacts[0].SHA256 == nil {
		t.Fatalf("plan=%+v", plan)
	}
	var progress []Progress
	created, err := d.Dispatch(OperationContext{RequestID: "backup-run", Progress: func(p Progress) { progress = append(progress, p) }}, "backup.create", raw(payload))
	if err != nil {
		t.Fatal(err)
	}
	if created.Status != "success" || len(created.Artifacts) != 1 {
		t.Fatalf("created=%+v", created)
	}
	if len(progress) == 0 {
		t.Fatal("no progress")
	}
	archive := created.Artifacts[0].Path
	info, err := d.Dispatch(OperationContext{}, "archive.info", raw(map[string]any{"archive": map[string]any{"path": archive}}))
	if err != nil {
		t.Fatal(err)
	}
	if info.Status != "success" {
		t.Fatalf("info=%+v", info)
	}
	browse, err := d.Dispatch(OperationContext{}, "archive.browse", raw(map[string]any{"archive": map[string]any{"path": archive}, "page": map[string]any{"page_size": 10}}))
	if err != nil {
		t.Fatal(err)
	}
	if len(browse.Artifacts) == 0 {
		t.Fatal("archive browse empty")
	}
	verified, err := d.Dispatch(OperationContext{}, "archive.verify", raw(map[string]any{"archive": map[string]any{"path": archive}}))
	if err != nil {
		t.Fatal(err)
	}
	if verified.Status != "success" {
		t.Fatalf("verify=%+v", verified)
	}
	indexed, err := d.Dispatch(OperationContext{}, "archive.list", raw(map[string]any{"roots": []string{dst}, "limit": 20, "max_depth": 2}))
	if err != nil {
		t.Fatal(err)
	}
	if len(indexed.Artifacts) != 1 || indexed.Artifacts[0].Path != archive {
		t.Fatalf("indexed=%+v", indexed)
	}
	runs, err := d.Dispatch(OperationContext{}, "runs.list", raw(map[string]any{"limit": 20}))
	if err != nil {
		t.Fatal(err)
	}
	if len(runs.Artifacts) != 1 {
		t.Fatalf("runs=%+v", runs)
	}
	shown, err := d.Dispatch(OperationContext{}, "runs.show", raw(map[string]any{"run_id": "backup-run"}))
	if err != nil {
		t.Fatal(err)
	}
	if len(shown.Artifacts) != 1 {
		t.Fatalf("shown=%+v", shown)
	}
	restoreDir := filepath.Join(root, "restored")
	restorePayload := map[string]any{"archive": archive, "destination_path": restoreDir, "paths": []string{}, "overwrite": false, "verify": true, "fresh_dir": true, "confirm_overwrite": false}
	preview, err := d.Dispatch(OperationContext{RequestID: "restore-plan"}, "restore.preview", raw(restorePayload))
	if err != nil {
		t.Fatal(err)
	}
	if preview.Status != "success" || len(preview.Artifacts) < 1 {
		t.Fatalf("preview=%+v", preview)
	}
	restored, err := d.Dispatch(OperationContext{RequestID: "restore-run"}, "restore.extract", raw(restorePayload))
	if err != nil {
		t.Fatal(err)
	}
	if restored.Status != "success" {
		t.Fatalf("restored=%+v", restored)
	}
	if b, err := os.ReadFile(filepath.Join(restoreDir, filepath.Base(src), "a.txt")); err != nil || string(b) != "hello" {
		t.Fatalf("restored content %q err=%v", b, err)
	}
}

func TestTransferDispatchAndCancellation(t *testing.T) {
	root := t.TempDir()
	src := filepath.Join(root, "source.txt")
	if err := os.WriteFile(src, []byte("payload"), 0o600); err != nil {
		t.Fatal(err)
	}
	store := mustStore(t, filepath.Join(root, "runs.jsonl"))
	d := New(store)
	dest := filepath.Join(root, "dest.txt")
	payload := map[string]any{"sources": []any{map[string]any{"location": src, "remote": false}}, "destination": map[string]any{"location": dest, "remote": false}, "mode": "push", "verify": true, "checksum": nil, "overwrite": false}
	plan, err := d.Dispatch(OperationContext{RequestID: "tp"}, "transfer.plan", raw(payload))
	if err != nil {
		t.Fatal(err)
	}
	if plan.Status != "success" || len(plan.Artifacts) != 1 {
		t.Fatalf("plan=%+v", plan)
	}
	var progress []Progress
	result, err := d.Dispatch(OperationContext{RequestID: "tr", Progress: func(p Progress) { progress = append(progress, p) }}, "transfer.run", raw(payload))
	if err != nil {
		t.Fatal(err)
	}
	if result.Status != "success" {
		t.Fatalf("result=%+v", result)
	}
	if len(progress) == 0 {
		t.Fatal("no progress")
	}
	if b, _ := os.ReadFile(dest); string(b) != "payload" {
		t.Fatalf("dest=%q", b)
	}
	_, err = d.Dispatch(OperationContext{RequestID: "cancelled", Cancelled: func() bool { return true }}, "transfer.run", raw(map[string]any{"sources": []any{map[string]any{"location": src, "remote": false}}, "destination": map[string]any{"location": filepath.Join(root, "never"), "remote": false}, "mode": "push", "verify": false, "overwrite": false}))
	if !errors.Is(err, ErrCancelled) {
		t.Fatalf("cancel err=%v", err)
	}
}

func TestServiceRefusesUnknownAndSyncOperations(t *testing.T) {
	d := New(nil)
	if _, err := d.Dispatch(OperationContext{}, "shell.exec", raw(map[string]any{})); err == nil {
		t.Fatal("unknown operation accepted")
	}
	root := t.TempDir()
	src := filepath.Join(root, "a")
	_ = os.WriteFile(src, []byte("x"), 0o600)
	_, err := d.Dispatch(OperationContext{}, "transfer.plan", raw(map[string]any{"sources": []any{map[string]any{"location": src, "remote": false}}, "destination": map[string]any{"location": filepath.Join(root, "b"), "remote": false}, "mode": "sync"}))
	if err == nil {
		t.Fatal("unadvertised sync accepted")
	}
}

func TestArchiveIndexMergesRunHistoryOutsideRoots(t *testing.T) {
	root := t.TempDir()
	archive := filepath.Join(root, "outside.bakery")
	_, err := archiveio.Create(models.ArchiveCreateSpec{Sources: []string{mustFile(t, root)}, OutputPath: archive, Compression: models.CompressionProfile{Algorithm: "none", Level: 0}, Verify: true, SkipSymlinks: true}, false, nil)
	if err != nil {
		t.Fatal(err)
	}
	store := mustStore(t, filepath.Join(root, "runs.jsonl"))
	_, err = store.RecordStart("r1", "backup", []string{root}, nil, &archive, nil)
	if err != nil {
		t.Fatal(err)
	}
	_, err = store.RecordCompletion("r1", "success", nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	result, err := New(store).Dispatch(OperationContext{}, "archive.list", raw(map[string]any{"roots": []string{}, "limit": 10}))
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Artifacts) != 1 || result.Artifacts[0].Path != archive {
		t.Fatalf("result=%+v", result)
	}
}
func mustFile(t *testing.T, root string) string {
	t.Helper()
	path := filepath.Join(root, "input.txt")
	if err := os.WriteFile(path, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestDetailedProgressPreservesCountsAndBytes(t *testing.T) {
	var got Progress
	ctx := OperationContext{Progress: func(p Progress) { got = p }}
	progress := 0.5
	filesDone, filesTotal := int64(5), int64(10)
	bytesDone, bytesTotal := int64(512), int64(1024)

	emitDetailed(ctx, "copying", "Copying", &progress, &filesDone, &filesTotal, &bytesDone, &bytesTotal)

	if got.Progress == nil || *got.Progress != 0.5 || got.FilesDone == nil || *got.FilesDone != 5 || got.FilesTotal == nil || *got.FilesTotal != 10 {
		t.Fatalf("detailed progress lost file counters: %#v", got)
	}
	if got.BytesDone == nil || *got.BytesDone != 512 || got.BytesTotal == nil || *got.BytesTotal != 1024 {
		t.Fatalf("detailed progress lost byte counters: %#v", got)
	}
}
