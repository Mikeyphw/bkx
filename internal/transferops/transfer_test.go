package transferops

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"syscall"
	"testing"
	"time"

	"bkx/internal/models"
	"bkx/internal/runstore"
)

func writeFile(t *testing.T, path, data string) string {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(data), 0o640); err != nil {
		t.Fatal(err)
	}
	return path
}

func spec(sources []string, destination string) models.TransferSpec {
	return models.TransferSpec{Sources: sources, Destination: destination, Direction: "push", Verify: true, Checksum: nil, DryRun: false, Overwrite: false}
}

func TestPlanSingleFileMatchesPythonShape(t *testing.T) {
	root := t.TempDir()
	source := writeFile(t, filepath.Join(root, "source.txt"), "content")
	destination := filepath.Join(root, "dest.txt")
	plan, err := PlanTransfer(spec([]string{source}, destination))
	if err != nil {
		t.Fatal(err)
	}
	if plan.Command != "transfer push" || !plan.DryRun || plan.TotalFiles != 1 || plan.TotalBytes != 7 || plan.TimingEstimate != "unknown" {
		t.Fatalf("plan=%+v", plan)
	}
	if len(plan.Steps) != 2 || plan.Steps[0].StepType != "transfer" || plan.Steps[1].StepType != "verify" {
		t.Fatalf("steps=%+v", plan.Steps)
	}
	if plan.Steps[0].Source == nil || *plan.Steps[0].Source != source || plan.Steps[0].Destination == nil || *plan.Steps[0].Destination != destination {
		t.Fatalf("step=%+v", plan.Steps[0])
	}
}

func TestMultiSourceMappingsAndCollisionPreflight(t *testing.T) {
	root := t.TempDir()
	aDir := filepath.Join(root, "a")
	bDir := filepath.Join(root, "b")
	a := writeFile(t, filepath.Join(aDir, "same.txt"), "one")
	b := writeFile(t, filepath.Join(bDir, "same.txt"), "two")
	dest := filepath.Join(root, "dest")
	if err := os.MkdirAll(dest, 0o755); err != nil {
		t.Fatal(err)
	}
	_, err := PlanMappings(spec([]string{a, b}, dest))
	if err == nil {
		t.Fatal("collision unexpectedly accepted")
	}
	if _, statErr := os.Stat(filepath.Join(dest, "same.txt")); !os.IsNotExist(statErr) {
		t.Fatalf("preflight mutated destination: %v", statErr)
	}
}

func TestDirectoryTransferPreservesContentModeAndMtime(t *testing.T) {
	root := t.TempDir()
	source := filepath.Join(root, "src")
	nested := writeFile(t, filepath.Join(source, "nested", "b.txt"), "bravo")
	writeFile(t, filepath.Join(source, "a.txt"), "alpha")
	epoch := int64(1704164646)
	if err := os.Chtimes(nested, unixTime(epoch), unixTime(epoch)); err != nil {
		t.Fatal(err)
	}
	dest := filepath.Join(root, "dest")
	result, err := Run(spec([]string{source}, dest), Options{})
	if err != nil {
		t.Fatal(err)
	}
	if !result.Success || result.FilesTransferred != 2 || result.BytesTransferred != 10 || result.VerificationPassed == nil || !*result.VerificationPassed {
		t.Fatalf("result=%+v", result)
	}
	got := filepath.Join(dest, "nested", "b.txt")
	data, err := os.ReadFile(got)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "bravo" {
		t.Fatalf("data=%q", data)
	}
	srcInfo, _ := os.Stat(nested)
	dstInfo, _ := os.Stat(got)
	if srcInfo.Mode().Perm() != dstInfo.Mode().Perm() {
		t.Fatalf("mode src=%o dst=%o", srcInfo.Mode().Perm(), dstInfo.Mode().Perm())
	}
	if srcInfo.ModTime().Unix() != dstInfo.ModTime().Unix() {
		t.Fatalf("mtime src=%v dst=%v", srcInfo.ModTime(), dstInfo.ModTime())
	}
}

func TestPullDirectionUsesSameLocalMapping(t *testing.T) {
	root := t.TempDir()
	source := writeFile(t, filepath.Join(root, "remote-ish.txt"), "pull")
	destination := filepath.Join(root, "local.txt")
	s := spec([]string{source}, destination)
	s.Direction = "pull"
	result, err := Run(s, Options{})
	if err != nil {
		t.Fatal(err)
	}
	if result.Direction != "pull" || result.FilesTransferred != 1 {
		t.Fatalf("result=%+v", result)
	}
	data, _ := os.ReadFile(destination)
	if string(data) != "pull" {
		t.Fatalf("dest=%q", data)
	}
}

func TestDryRunReportsCountsWithoutMutation(t *testing.T) {
	root := t.TempDir()
	source := writeFile(t, filepath.Join(root, "source.txt"), "content")
	destination := filepath.Join(root, "dest.txt")
	s := spec([]string{source}, destination)
	s.DryRun = true
	s.Verify = false
	result, err := Run(s, Options{})
	if err != nil {
		t.Fatal(err)
	}
	if !result.DryRun || result.FilesTransferred != 1 || result.BytesTransferred != 7 || len(result.Warnings) != 1 {
		t.Fatalf("result=%+v", result)
	}
	if _, err := os.Stat(destination); !os.IsNotExist(err) {
		t.Fatalf("dry-run destination exists: %v", err)
	}
}

func TestOverwritePolicyAndDestinationSymlink(t *testing.T) {
	root := t.TempDir()
	source := writeFile(t, filepath.Join(root, "source.txt"), "new")
	destination := writeFile(t, filepath.Join(root, "dest.txt"), "old")
	if _, err := Run(spec([]string{source}, destination), Options{}); err == nil {
		t.Fatal("no-overwrite unexpectedly succeeded")
	}
	data, _ := os.ReadFile(destination)
	if string(data) != "old" {
		t.Fatalf("existing destination changed: %q", data)
	}
	s := spec([]string{source}, destination)
	s.Overwrite = true
	if _, err := Run(s, Options{}); err != nil {
		t.Fatal(err)
	}
	data, _ = os.ReadFile(destination)
	if string(data) != "new" {
		t.Fatalf("overwrite result=%q", data)
	}

	outside := filepath.Join(root, "outside.txt")
	link := filepath.Join(root, "link.txt")
	if err := os.Symlink(outside, link); err != nil {
		t.Fatal(err)
	}
	s.Destination = link
	if _, err := Run(s, Options{}); err == nil {
		t.Fatal("destination symlink unexpectedly accepted")
	}
	if _, err := os.Stat(outside); !os.IsNotExist(err) {
		t.Fatalf("symlink target mutated: %v", err)
	}
}

func TestNestedSourceSymlinkAndSpecialFileRejected(t *testing.T) {
	root := t.TempDir()
	sourceDir := filepath.Join(root, "src")
	real := writeFile(t, filepath.Join(sourceDir, "real.txt"), "x")
	link := filepath.Join(sourceDir, "link.txt")
	if err := os.Symlink(real, link); err != nil {
		t.Fatal(err)
	}
	if _, err := PlanTransfer(spec([]string{sourceDir}, filepath.Join(root, "dest"))); err == nil {
		t.Fatal("nested source symlink accepted")
	}
	fifo := filepath.Join(root, "pipe")
	if err := syscall.Mkfifo(fifo, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := PlanTransfer(spec([]string{fifo}, filepath.Join(root, "fifo-dest"))); err == nil {
		t.Fatal("fifo accepted")
	}
}

func TestChecksumAlgorithmAndExpectedDigest(t *testing.T) {
	root := t.TempDir()
	source := writeFile(t, filepath.Join(root, "source.txt"), "checksum")
	s := spec([]string{source}, filepath.Join(root, "sha1.txt"))
	alg := "sha1"
	s.Checksum = &alg
	if _, err := Run(s, Options{}); err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256([]byte("checksum"))
	expected := hex.EncodeToString(sum[:])
	s = spec([]string{source}, filepath.Join(root, "expected.txt"))
	s.Verify = false
	s.Checksum = &expected
	result, err := Run(s, Options{})
	if err != nil {
		t.Fatal(err)
	}
	if result.VerificationPassed != nil || result.ChecksumVerified == nil || !*result.ChecksumVerified {
		t.Fatalf("result=%+v", result)
	}
	bad := "deadbeef"
	s = spec([]string{source}, filepath.Join(root, "bad.txt"))
	s.Checksum = &bad
	if _, err := Run(s, Options{}); err == nil {
		t.Fatal("bad expected digest accepted")
	}
	if _, err := os.Stat(filepath.Join(root, "bad.txt")); !os.IsNotExist(err) {
		t.Fatalf("bad checksum published destination: %v", err)
	}
}

func TestCancellationBeforePublicationMarksRunInterrupted(t *testing.T) {
	root := t.TempDir()
	source := writeFile(t, filepath.Join(root, "source.txt"), string(make([]byte, 2*1024*1024)))
	destination := filepath.Join(root, "dest.txt")
	store, err := runstore.New(filepath.Join(root, "runs.jsonl"), 20, false)
	if err != nil {
		t.Fatal(err)
	}
	cancelled := false
	_, err = Run(spec([]string{source}, destination), Options{RunStore: store, RunID: "go09-cancel", Cancelled: func() bool { return cancelled }, Progress: func(event ProgressEvent) {
		if event.BytesTransferred > 0 {
			cancelled = true
		}
	}})
	if !errors.Is(err, ErrCancelled) {
		t.Fatalf("err=%v", err)
	}
	if _, err := os.Stat(destination); !os.IsNotExist(err) {
		t.Fatalf("cancelled transfer published: %v", err)
	}
	record, err := store.Get("go09-cancel")
	if err != nil || record == nil {
		t.Fatalf("record=%v err=%v", record, err)
	}
	if record.Status != "interrupted" || record.Operation != "transfer" {
		t.Fatalf("record=%+v", record)
	}
}

func TestSuccessfulRunHistoryAndProgress(t *testing.T) {
	root := t.TempDir()
	source := writeFile(t, filepath.Join(root, "source.txt"), "content")
	destination := filepath.Join(root, "dest.txt")
	store, err := runstore.New(filepath.Join(root, "runs.jsonl"), 20, false)
	if err != nil {
		t.Fatal(err)
	}
	var events []ProgressEvent
	result, err := Run(spec([]string{source}, destination), Options{RunStore: store, RunID: "go09-run", Progress: func(event ProgressEvent) { events = append(events, event) }})
	if err != nil {
		t.Fatal(err)
	}
	if len(events) == 0 || events[len(events)-1].FilesTransferred != 1 || events[len(events)-1].BytesTransferred != 7 {
		t.Fatalf("events=%+v", events)
	}
	record, err := store.Get("go09-run")
	if err != nil || record == nil {
		t.Fatalf("record=%v err=%v", record, err)
	}
	if record.Status != "success" || record.FilesCount == nil || *record.FilesCount != 1 || record.BytesProcessed == nil || *record.BytesProcessed != 7 {
		t.Fatalf("record=%+v", record)
	}
	if record.SpecSnapshot["type"] != "TransferSpec" || result.FilesTransferred != 1 {
		t.Fatalf("snapshot=%v result=%+v", record.SpecSnapshot, result)
	}
}

func TestNoReplaceFallbackHandlesEPERMAndNeverClobbers(t *testing.T) {
	root := t.TempDir()
	tmp := writeFile(t, filepath.Join(root, ".tmp"), "payload")
	dst := filepath.Join(root, "dest")
	if err := publishNoReplaceWith(tmp, dst, func(string, string) error { return syscall.EPERM }); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(dst)
	if string(data) != "payload" {
		t.Fatalf("dst=%q", data)
	}
	tmp2 := writeFile(t, filepath.Join(root, ".tmp2"), "new")
	if err := publishNoReplaceWith(tmp2, dst, func(string, string) error { return syscall.EPERM }); err == nil {
		t.Fatal("existing destination overwritten")
	}
	data, _ = os.ReadFile(dst)
	if string(data) != "payload" {
		t.Fatalf("existing destination changed=%q", data)
	}
}

func TestParseSpecJSONFrozenShapeAndRemoteFailClosed(t *testing.T) {
	root := t.TempDir()
	source := writeFile(t, filepath.Join(root, "source"), "x")
	s := spec([]string{source}, filepath.Join(root, "dest"))
	raw, _ := json.Marshal(s)
	if _, err := ParseSpecJSON(string(raw)); err != nil {
		t.Fatal(err)
	}
	var obj map[string]any
	_ = json.Unmarshal(raw, &obj)
	delete(obj, "verify")
	missing, _ := json.Marshal(obj)
	if _, err := ParseSpecJSON(string(missing)); err == nil {
		t.Fatal("missing field accepted")
	}
	_ = json.Unmarshal(raw, &obj)
	obj["extra"] = 1
	extra, _ := json.Marshal(obj)
	if _, err := ParseSpecJSON(string(extra)); err == nil {
		t.Fatal("extra field accepted")
	}
	remote := s
	remote.Direction = "pull"
	remote.Sources = []string{"s3://bucket/key"}
	if _, err := PlanTransfer(remote); err == nil {
		t.Fatal("remote GO09 transfer unexpectedly accepted")
	}
}

func unixTime(sec int64) (tme time.Time) { return time.Unix(sec, 0) }
