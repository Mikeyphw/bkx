package services

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
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

func bindPlanEvidence(t *testing.T, payload map[string]any, result Result, label, requestField string) {
	t.Helper()
	for _, item := range result.Artifacts {
		if item.Label != label {
			continue
		}
		if item.SHA256 == nil || strings.TrimSpace(*item.SHA256) == "" {
			t.Fatalf("%s artifact missing plan token: %+v", label, item)
		}
		const prefix = "embedded://plan/"
		if !strings.HasPrefix(item.Path, prefix) {
			t.Fatalf("%s artifact has unexpected path: %s", label, item.Path)
		}
		payload[requestField] = strings.TrimPrefix(item.Path, prefix)
		payload["plan_token"] = *item.SHA256
		return
	}
	t.Fatalf("%s artifact missing from result: %+v", label, result)
}

func restoredArtifactPath(t *testing.T, result Result, base string) string {
	t.Helper()
	for _, item := range result.Artifacts {
		if item.Label == "restored_path" && filepath.Base(item.Path) == base {
			return item.Path
		}
	}
	t.Fatalf("restored path %q missing from result: %+v", base, result)
	return ""
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
	// The frozen v1 bridge is a compatibility floor. Additive V2 operations
	// are permitted as long as every originally advertised operation remains.
	owned := map[string]bool{}
	for _, operation := range got {
		owned[operation] = true
	}
	for _, operation := range doc.Advertised {
		if !owned[operation] {
			t.Fatalf("frozen bridge operation lost: %s", operation)
		}
	}
	for _, required := range []string{"backup.snapshot", "repository.init"} {
		if !owned[required] {
			t.Fatalf("BR05/06 service operation missing: %s", required)
		}
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
	if plan.Metrics == nil || plan.Metrics.FilesTotal == nil || *plan.Metrics.FilesTotal != 1 || plan.Metrics.BytesTotal == nil || *plan.Metrics.BytesTotal != 5 {
		t.Fatalf("backup plan metrics=%+v", plan.Metrics)
	}
	bindPlanEvidence(t, payload, plan, "backup_plan", "plan_request_id")
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
	var runArtifacts, pageArtifacts int
	for _, artifact := range runs.Artifacts {
		switch artifact.Label {
		case "run":
			runArtifacts++
			if artifact.Path != "embedded://runs/backup-run" || artifact.Metadata["run_id"] != "backup-run" {
				t.Fatalf("unexpected run artifact=%+v", artifact)
			}
		case "run_history_page":
			pageArtifacts++
			if artifact.Path != "embedded://runs/page" || artifact.Metadata["total"] != "1" {
				t.Fatalf("unexpected run history page artifact=%+v", artifact)
			}
		default:
			t.Fatalf("unexpected runs.list artifact=%+v result=%+v", artifact, runs)
		}
	}
	if runArtifacts != 1 || pageArtifacts != 1 {
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
	bindPlanEvidence(t, restorePayload, preview, "restore_plan", "preview_request_id")
	restored, err := d.Dispatch(OperationContext{RequestID: "restore-run"}, "restore.extract", raw(restorePayload))
	if err != nil {
		t.Fatal(err)
	}
	if restored.Status != "success" {
		t.Fatalf("restored=%+v", restored)
	}
	restoredFile := restoredArtifactPath(t, restored, "a.txt")
	if b, err := os.ReadFile(restoredFile); err != nil || string(b) != "hello" {
		t.Fatalf("restored content %q err=%v path=%s", b, err, restoredFile)
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
	if plan.Status != "success" || len(plan.Artifacts) < 1 {
		t.Fatalf("plan=%+v", plan)
	}
	bindPlanEvidence(t, payload, plan, "transfer_plan", "plan_request_id")
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
	cancelPayload := map[string]any{"sources": []any{map[string]any{"location": src, "remote": false}}, "destination": map[string]any{"location": filepath.Join(root, "never"), "remote": false}, "mode": "push", "verify": false, "overwrite": false}
	cancelPlan, err := d.Dispatch(OperationContext{RequestID: "cancel-plan"}, "transfer.plan", raw(cancelPayload))
	if err != nil {
		t.Fatal(err)
	}
	bindPlanEvidence(t, cancelPayload, cancelPlan, "transfer_plan", "plan_request_id")
	_, err = d.Dispatch(OperationContext{RequestID: "cancelled", Cancelled: func() bool { return true }}, "transfer.run", raw(cancelPayload))
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

func TestTransferDispatcherAcceptsRcloneEndpointsAndRejectsSpoofedRemoteFlags(t *testing.T) {
	root := t.TempDir()
	rclone := filepath.Join(root, "rclone")
	script := `#!/bin/sh
set -eu
case "$1" in
  lsjson) printf '{"Path":"source","Name":"source","Size":5,"ModTime":"2026-09-07T12:00:00Z","IsDir":false}\n' ;;
  size) printf '{"count":1,"bytes":5}\n' ;;
  *) exit 0 ;;
esac
`
	if err := os.WriteFile(rclone, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	config := filepath.Join(root, "rclone.conf")
	if err := os.WriteFile(config, []byte("[drive]\ntype = drive\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("BKX_RCLONE_PATH", rclone)
	t.Setenv("BKX_RCLONE_CONFIG", config)

	src := filepath.Join(root, "source")
	if err := os.WriteFile(src, []byte("hello"), 0o600); err != nil {
		t.Fatal(err)
	}
	d := New(nil)
	payload := map[string]any{
		"sources":     []any{map[string]any{"location": src, "remote": false}},
		"destination": map[string]any{"location": "rclone://drive/BKX", "remote": true},
		"mode":        "push", "verify": true, "overwrite": false,
	}
	result, err := d.Dispatch(OperationContext{RequestID: "remote-plan"}, "transfer.plan", raw(payload))
	if err != nil || result.Status != "success" {
		t.Fatalf("result=%+v err=%v", result, err)
	}

	payload["destination"] = map[string]any{"location": "rclone://drive/BKX", "remote": false}
	if _, err := d.Dispatch(OperationContext{RequestID: "spoofed"}, "transfer.plan", raw(payload)); err == nil {
		t.Fatal("remote flag spoofing accepted")
	}

	syncPayload := map[string]any{
		"sources":     []any{map[string]any{"location": src, "remote": false}},
		"destination": map[string]any{"location": "rclone://drive/BKX", "remote": true},
		"mode":        "sync", "verify": true, "overwrite": true, "delete_extraneous_on_sync": true,
	}
	if _, err := d.Dispatch(OperationContext{RequestID: "sync-plan"}, "transfer.plan", raw(syncPayload)); err != nil {
		t.Fatalf("approved sync rejected: %v", err)
	}
}

func TestFilesystemBrowserDispatchesConfiguredRcloneUrisWithoutProtocolExpansion(t *testing.T) {
	root := t.TempDir()
	rclone := filepath.Join(root, "rclone")
	script := `#!/bin/sh
set -eu
case "$1" in
  lsjson)
    case "$*" in
      *--stat*) printf '{"Path":"folder/file.txt","Name":"file.txt","Size":5,"ModTime":"2026-09-07T12:00:00Z","IsDir":false}\n' ;;
      *) printf '[{"Path":"folder","Name":"folder","Size":0,"ModTime":"2026-09-07T12:00:00Z","IsDir":true},{"Path":".hidden","Name":".hidden","Size":1,"ModTime":"2026-09-07T12:00:00Z","IsDir":false},{"Path":"file.txt","Name":"file.txt","Size":5,"ModTime":"2026-09-07T12:00:00Z","IsDir":false}]\n' ;;
    esac ;;
  *) echo "unexpected command $1" >&2; exit 3 ;;
esac
`
	if err := os.WriteFile(rclone, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	config := filepath.Join(root, "rclone.conf")
	if err := os.WriteFile(config, []byte("[drive]\ntype = drive\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("BKX_RCLONE_PATH", rclone)
	t.Setenv("BKX_RCLONE_CONFIG", config)

	d := New(nil)
	listed, err := d.Dispatch(OperationContext{RequestID: "remote-list"}, "filesystem.list", raw(map[string]any{
		"path": "rclone://drive/BKX", "show_hidden": false, "limit": 100, "offset": 0,
	}))
	if err != nil {
		t.Fatal(err)
	}
	if listed.Status != "success" || len(listed.Artifacts) != 2 {
		t.Fatalf("listed=%+v", listed)
	}
	if listed.Artifacts[0].Label != "directory" || listed.Artifacts[0].Path != "rclone://drive/BKX/folder" {
		t.Fatalf("directory artifact=%+v", listed.Artifacts[0])
	}
	if listed.Artifacts[1].Label != "file" || listed.Artifacts[1].Path != "rclone://drive/BKX/file.txt" {
		t.Fatalf("file artifact=%+v", listed.Artifacts[1])
	}

	stat, err := d.Dispatch(OperationContext{RequestID: "remote-stat"}, "filesystem.stat", raw(map[string]any{"path": "rclone://drive/BKX/file.txt"}))
	if err != nil {
		t.Fatal(err)
	}
	if stat.Status != "success" || len(stat.Artifacts) != 1 || stat.Artifacts[0].Path != "rclone://drive/BKX/file.txt" || stat.Artifacts[0].Label != "file" {
		t.Fatalf("stat=%+v", stat)
	}
}

func TestRemoteArchiveProductOperationsListInspectVerifyAndRestore(t *testing.T) {
	root := t.TempDir()
	remoteRoot := filepath.Join(root, "remote")
	remoteBkx := filepath.Join(remoteRoot, "BKX")
	if err := os.MkdirAll(remoteBkx, 0o755); err != nil {
		t.Fatal(err)
	}
	source := filepath.Join(root, "source")
	if err := os.MkdirAll(source, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(source, "cloud.txt"), []byte("cloud-payload"), 0o600); err != nil {
		t.Fatal(err)
	}
	localArchive := filepath.Join(root, "cloud.bakery")
	_, err := archiveio.Create(models.ArchiveCreateSpec{
		Sources: []string{source}, OutputPath: localArchive,
		Compression: models.CompressionProfile{Algorithm: "none", Level: 0}, Verify: true, SkipSymlinks: true,
	}, false, nil)
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(localArchive)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(remoteBkx, "cloud.bakery"), data, 0o600); err != nil {
		t.Fatal(err)
	}

	rclone := filepath.Join(root, "rclone")
	script := `#!/bin/sh
set -eu
remote_root="$RCLONE_TEST_REMOTE_ROOT"
map_path() { case "$1" in drive:*) printf '%s/%s' "$remote_root" "${1#drive:}" ;; *) printf '%s' "$1" ;; esac; }
size_of() { wc -c < "$1" | tr -d ' '; }
cmd="$1"; shift
case "$cmd" in
  lsjson)
    loc="$1"; shift; p="$(map_path "$loc")"
    case " $* " in
      *" --stat "*)
        [ -e "$p" ] || { echo 'object not found' >&2; exit 1; }
        b="$(basename "$p")"
        if [ -d "$p" ]; then printf '{"Path":"%s","Name":"%s","Size":0,"ModTime":"2026-01-01T00:00:00Z","IsDir":true}\n' "$b" "$b";
        else n="$(size_of "$p")"; printf '{"Path":"%s","Name":"%s","Size":%s,"ModTime":"2026-01-01T00:00:00Z","IsDir":false}\n' "$b" "$b" "$n"; fi
        ;;
      *)
        [ -d "$p" ] || { echo 'directory not found' >&2; exit 1; }
        first=1; printf '['
        for f in "$p"/*; do
          [ -e "$f" ] || continue; b="$(basename "$f")"; [ $first -eq 1 ] || printf ','; first=0
          if [ -d "$f" ]; then printf '{"Path":"%s","Name":"%s","Size":0,"ModTime":"2026-01-01T00:00:00Z","IsDir":true}' "$b" "$b";
          else n="$(size_of "$f")"; printf '{"Path":"%s","Name":"%s","Size":%s,"ModTime":"2026-01-01T00:00:00Z","IsDir":false}' "$b" "$b" "$n"; fi
        done
        printf ']\n'
        ;;
    esac
    ;;
  copyto)
    src="$1"; dst="$2"; srcp="$(map_path "$src")"; dstp="$(map_path "$dst")"; mkdir -p "$(dirname "$dstp")"; cp "$srcp" "$dstp"; n="$(size_of "$dstp")"; printf '{"stats":{"bytes":%s,"transfers":1,"totalBytes":%s,"totalTransfers":1}}\n' "$n" "$n" >&2
    ;;
  check) ;;
  size) p="$(map_path "$1")"; n="$(size_of "$p")"; printf '{"count":1,"bytes":%s}\n' "$n" ;;
  *) echo "unexpected command: $cmd" >&2; exit 3 ;;
esac
`
	if err := os.WriteFile(rclone, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	config := filepath.Join(root, "rclone.conf")
	if err := os.WriteFile(config, []byte("[drive]\ntype = drive\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("BKX_RCLONE_PATH", rclone)
	t.Setenv("BKX_RCLONE_CONFIG", config)
	t.Setenv("RCLONE_TEST_REMOTE_ROOT", remoteRoot)

	d := New(nil)
	remoteArchive := "rclone://drive/BKX/cloud.bakery"
	indexed, err := d.Dispatch(OperationContext{}, "archive.list", raw(map[string]any{"roots": []string{"rclone://drive/BKX"}, "limit": 20, "max_depth": 2}))
	if err != nil {
		t.Fatal(err)
	}
	if len(indexed.Artifacts) != 1 || indexed.Artifacts[0].Path != remoteArchive {
		t.Fatalf("indexed=%+v", indexed)
	}
	for _, operation := range []string{"archive.info", "archive.browse", "archive.verify"} {
		payload := map[string]any{"archive": map[string]any{"path": remoteArchive}}
		if operation == "archive.browse" {
			payload["page"] = map[string]any{"page_size": 20}
		}
		result, dispatchErr := d.Dispatch(OperationContext{RequestID: operation}, operation, raw(payload))
		if dispatchErr != nil {
			t.Fatalf("%s: %v", operation, dispatchErr)
		}
		if result.Status != "success" {
			t.Fatalf("%s result=%+v", operation, result)
		}
	}
	restoreDir := filepath.Join(root, "restored")
	restorePayload := map[string]any{"archive": remoteArchive, "destination_path": restoreDir, "paths": []string{}, "overwrite": false, "verify": true, "fresh_dir": true, "confirm_overwrite": false}
	preview, err := d.Dispatch(OperationContext{RequestID: "remote-restore-preview"}, "restore.preview", raw(restorePayload))
	if err != nil || preview.Status != "success" {
		t.Fatalf("preview=%+v err=%v", preview, err)
	}
	bindPlanEvidence(t, restorePayload, preview, "restore_plan", "preview_request_id")
	restored, err := d.Dispatch(OperationContext{RequestID: "remote-restore"}, "restore.extract", raw(restorePayload))
	if err != nil || restored.Status != "success" {
		t.Fatalf("restored=%+v err=%v", restored, err)
	}
	restoredFile := restoredArtifactPath(t, restored, "cloud.txt")
	got, err := os.ReadFile(restoredFile)
	if err != nil || string(got) != "cloud-payload" {
		t.Fatalf("restored content=%q err=%v path=%s", got, err, restoredFile)
	}
}

func TestArchiveListTreatsMissingFreshRemoteRootAsEmpty(t *testing.T) {
	root := t.TempDir()
	rclone := filepath.Join(root, "rclone")
	if err := os.WriteFile(rclone, []byte(`#!/bin/sh
set -eu
case "$1" in
  lsjson) echo 'directory not found' >&2; exit 1 ;;
  *) exit 0 ;;
esac
`), 0o755); err != nil {
		t.Fatal(err)
	}
	config := filepath.Join(root, "rclone.conf")
	if err := os.WriteFile(config, []byte("[drive]\ntype = drive\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("BKX_RCLONE_PATH", rclone)
	t.Setenv("BKX_RCLONE_CONFIG", config)
	result, err := New(nil).Dispatch(OperationContext{}, "archive.list", raw(map[string]any{"roots": []string{"rclone://drive/BKX"}, "limit": 20, "max_depth": 2}))
	if err != nil {
		t.Fatal(err)
	}
	if result.Status != "success" || len(result.Artifacts) != 0 {
		t.Fatalf("result=%+v", result)
	}
}

func TestBR23FilesystemRejectsContentUriAndProtectedPseudoPath(t *testing.T) {
	d := New(nil)
	if _, err := d.Dispatch(OperationContext{}, "filesystem.stat", raw(map[string]any{"path": "content://provider/document/1"})); err == nil {
		t.Fatal("filesystem.stat accepted a content URI as a POSIX path")
	}
	if _, err := d.Dispatch(OperationContext{}, "filesystem.list", raw(map[string]any{"path": "/proc/self", "show_hidden": true, "limit": 10, "offset": 0})); err == nil {
		t.Fatal("filesystem.list accepted a pseudo-filesystem path")
	}
}

func TestBR23StorageLocationsExposeUsabilityMetadata(t *testing.T) {
	root := t.TempDir()
	d := New(nil)
	d.StorageLocations = []StorageLocation{{Label: "fixture", Path: root}}
	result, err := d.Dispatch(OperationContext{}, "storage.locations", raw(map[string]any{}))
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Artifacts) != 1 {
		t.Fatalf("storage=%+v", result)
	}
	metadata := result.Artifacts[0].Metadata
	if metadata["usable_for_direct_file_access"] != "true" || metadata["canonical_path"] == "" {
		t.Fatalf("metadata=%v", metadata)
	}
}
