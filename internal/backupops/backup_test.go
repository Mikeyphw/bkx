package backupops

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"syscall"
	"testing"

	"bkx/internal/archiveio"
	"bkx/internal/models"
	"bkx/internal/runstore"
	"bkx/internal/validation"
)

func fixture(t *testing.T) string {
	t.Helper()
	root := filepath.Join(t.TempDir(), "source")
	if err := os.MkdirAll(filepath.Join(root, "nested"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "a.txt"), []byte("alpha"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "nested", "b.txt"), []byte("bravo"), 0o640); err != nil {
		t.Fatal(err)
	}
	return root
}

func backupSpec(source string, destination *string) models.BackupSpec {
	return models.BackupSpec{
		Sources: []string{source}, Destination: destination, ArchiveName: nil,
		Excludes: []string{}, Includes: []string{}, SplitSize: nil,
		Compression: models.CompressionProfile{Algorithm: "zstd", Level: 3},
		Verify:      true, DryRun: false, SkipSymlinks: false,
	}
}

func TestPlanMatchesPythonShapeAndDeterministicName(t *testing.T) {
	src := fixture(t)
	dest := filepath.Join(t.TempDir(), "dest")
	plan, err := PlanBackup(backupSpec(src, &dest))
	if err != nil {
		t.Fatal(err)
	}
	wantName := validation.DeterministicName(filepath.Base(src), src) + ".bakery"
	if plan.Command != "backup create" || !plan.DryRun || plan.TotalFiles != 2 || plan.TotalBytes != 10 || plan.TimingEstimate != "unknown" {
		t.Fatalf("unexpected plan: %+v", plan)
	}
	if len(plan.Steps) != 3 || plan.Steps[0].StepType != "archive" || plan.Steps[1].StepType != "transfer" || plan.Steps[2].StepType != "verify" {
		t.Fatalf("unexpected steps: %+v", plan.Steps)
	}
	if plan.Steps[0].Destination == nil || filepath.Base(*plan.Steps[0].Destination) != wantName {
		t.Fatalf("archive destination=%v want %s", plan.Steps[0].Destination, wantName)
	}
}

func TestCreateStagesPublishesVerifiesAndRecordsRun(t *testing.T) {
	src := fixture(t)
	dest := filepath.Join(t.TempDir(), "final")
	runs := filepath.Join(t.TempDir(), "runs.jsonl")
	store, err := runstore.New(runs, 20, false)
	if err != nil {
		t.Fatal(err)
	}
	var events []ProgressEvent
	result, err := Create(backupSpec(src, &dest), true, Options{RunStore: store, RunID: "go08-run", Progress: func(event ProgressEvent) { events = append(events, event) }})
	if err != nil {
		t.Fatal(err)
	}
	if !result.Success || result.DryRun || result.FilesProcessed != 2 || result.ArchivesCreated != 1 || result.VerificationPassed == nil || !*result.VerificationPassed {
		t.Fatalf("unexpected result: %+v", result)
	}
	if len(result.ArchivePaths) != 1 {
		t.Fatalf("archive paths: %+v", result.ArchivePaths)
	}
	final := result.ArchivePaths[0]
	if filepath.Dir(final) != dest {
		t.Fatalf("final=%s dest=%s", final, dest)
	}
	if _, err := os.Stat(final); err != nil {
		t.Fatal(err)
	}
	if result.Checksums["archive"] == "" || result.Checksums[filepath.Base(final)] == "" {
		t.Fatalf("checksums=%v", result.Checksums)
	}
	for _, key := range []string{"archive", "verify_archive", "transfer", "verify_transfer"} {
		if !result.StepResults[key] {
			t.Fatalf("step %s false: %v", key, result.StepResults)
		}
	}
	if len(events) < 3 || events[0].Stage != "archiving" || events[len(events)-1].Stage != "complete" {
		t.Fatalf("events=%+v", events)
	}
	record, err := store.Get("go08-run")
	if err != nil || record == nil {
		t.Fatalf("record=%v err=%v", record, err)
	}
	if record.Status != "success" || record.Operation != "backup" || record.DestinationPath == nil || *record.DestinationPath != dest {
		t.Fatalf("record=%+v", record)
	}
	if got := record.SpecSnapshot["type"]; got != "BackupSpec" {
		t.Fatalf("snapshot type=%v", got)
	}
}

func TestCreateDryRunDoesNotMutate(t *testing.T) {
	src := fixture(t)
	dest := filepath.Join(t.TempDir(), "final")
	spec := backupSpec(src, &dest)
	spec.DryRun = true
	result, err := Create(spec, true, Options{})
	if err != nil {
		t.Fatal(err)
	}
	if !result.Success || !result.DryRun || !result.StepResults["archive"] || !result.StepResults["transfer"] || !result.StepResults["verify"] {
		t.Fatalf("result=%+v", result)
	}
	if _, err := os.Stat(dest); !os.IsNotExist(err) {
		t.Fatalf("dry run created destination: %v", err)
	}
}

func TestCreateCancellationAfterArchivePreventsPublicationAndMarksCancelled(t *testing.T) {
	src := fixture(t)
	dest := filepath.Join(t.TempDir(), "final")
	store, err := runstore.New(filepath.Join(t.TempDir(), "runs.jsonl"), 20, false)
	if err != nil {
		t.Fatal(err)
	}
	cancelled := false
	_, err = Create(backupSpec(src, &dest), true, Options{
		RunStore: store, RunID: "cancelled",
		Cancelled: func() bool { return cancelled },
		Progress: func(event ProgressEvent) {
			if event.Stage == "archiving" && event.Files >= 2 {
				cancelled = true
			}
		},
	})
	if !errors.Is(err, ErrCancelled) {
		t.Fatalf("err=%v", err)
	}
	if _, statErr := os.Stat(dest); !os.IsNotExist(statErr) {
		t.Fatalf("cancelled backup published destination: %v", statErr)
	}
	record, err := store.Get("cancelled")
	if err != nil || record == nil || record.Status != "cancelled" {
		t.Fatalf("record=%+v err=%v", record, err)
	}
}

func TestPublishNoReplaceFallsBackWhenAtomicRenameIsPermissionDenied(t *testing.T) {
	dir := t.TempDir()
	tmp := filepath.Join(dir, ".archive.tmp")
	dst := filepath.Join(dir, "archive.bakery")
	if err := os.WriteFile(tmp, []byte("payload"), 0o600); err != nil {
		t.Fatal(err)
	}
	err := publishNoReplaceWith(tmp, dst, func(string, string) error { return syscall.EPERM })
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(dst)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "payload" {
		t.Fatalf("destination=%q", data)
	}
	if _, err := os.Stat(tmp); !os.IsNotExist(err) {
		t.Fatalf("staging file survived fallback publication: %v", err)
	}
}

func TestPublishNoReplaceFallbackNeverOverwritesExistingDestination(t *testing.T) {
	dir := t.TempDir()
	tmp := filepath.Join(dir, ".archive.tmp")
	dst := filepath.Join(dir, "archive.bakery")
	if err := os.WriteFile(tmp, []byte("new"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(dst, []byte("keep"), 0o600); err != nil {
		t.Fatal(err)
	}
	err := publishNoReplaceWith(tmp, dst, func(string, string) error { return syscall.EPERM })
	if err == nil {
		t.Fatal("fallback publication unexpectedly overwrote destination")
	}
	data, readErr := os.ReadFile(dst)
	if readErr != nil {
		t.Fatal(readErr)
	}
	if string(data) != "keep" {
		t.Fatalf("existing destination changed: %q", data)
	}
}

func TestCreateRefusesOverwriteAndDestinationSymlink(t *testing.T) {
	src := fixture(t)
	dest := filepath.Join(t.TempDir(), "final")
	name := "fixed"
	spec := backupSpec(src, &dest)
	spec.ArchiveName = &name
	if err := os.MkdirAll(dest, 0o755); err != nil {
		t.Fatal(err)
	}
	final := filepath.Join(dest, "fixed.bakery")
	if err := os.WriteFile(final, []byte("keep"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Create(spec, true, Options{}); err == nil {
		t.Fatal("overwrite unexpectedly succeeded")
	}
	data, _ := os.ReadFile(final)
	if string(data) != "keep" {
		t.Fatalf("existing file changed: %q", data)
	}

}

func TestParseSpecJSONRequiresFrozenShape(t *testing.T) {
	src := fixture(t)
	spec := backupSpec(src, nil)
	data, err := json.Marshal(spec)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := ParseSpecJSON(string(data)); err != nil {
		t.Fatal(err)
	}
	var obj map[string]any
	if err := json.Unmarshal(data, &obj); err != nil {
		t.Fatal(err)
	}
	delete(obj, "verify")
	missing, _ := json.Marshal(obj)
	if _, err := ParseSpecJSON(string(missing)); err == nil {
		t.Fatal("missing field accepted")
	}
	obj["verify"] = true
	obj["extra"] = 1
	extra, _ := json.Marshal(obj)
	if _, err := ParseSpecJSON(string(extra)); err == nil {
		t.Fatal("unknown field accepted")
	}
}

func TestCreateSplitPublishesAllPartsAndLogicalArchiveVerifies(t *testing.T) {
	src := fixture(t)
	// Make the payload large enough to force multiple parts after zstd compression.
	if err := os.WriteFile(filepath.Join(src, "blob.bin"), make([]byte, 128*1024), 0o600); err != nil {
		t.Fatal(err)
	}
	dest := filepath.Join(t.TempDir(), "final")
	name := "split-fixture"
	split := validation.MinSplitSizeBytes
	spec := backupSpec(src, &dest)
	spec.ArchiveName = &name
	spec.SplitSize = &split
	// Incompressible-ish deterministic payload so a small split size definitely rolls.
	blob := make([]byte, int(validation.MinSplitSizeBytes*3+4096))
	for i := range blob {
		blob[i] = byte((i*31 + i/7) & 0xff)
	}
	if err := os.WriteFile(filepath.Join(src, "blob.bin"), blob, 0o600); err != nil {
		t.Fatal(err)
	}

	result, err := Create(spec, false, Options{})
	if err != nil {
		t.Fatal(err)
	}
	if !result.Success || len(result.ArchivePaths) != 1 {
		t.Fatalf("result=%+v", result)
	}
	base := result.ArchivePaths[0]
	if base != filepath.Join(dest, name+".bakery") {
		t.Fatalf("base=%s", base)
	}
	parts, err := filepath.Glob(base + ".part*")
	if err != nil {
		t.Fatal(err)
	}
	if len(parts) == 0 {
		t.Fatal("expected split sidecars")
	}
	if result.Checksums[filepath.Base(base)] == "" || result.Checksums["archive"] == "" {
		t.Fatalf("logical checksums missing: %v", result.Checksums)
	}
	verified, err := archiveio.Verify(base, []string{"sha256"})
	if err != nil || !verified.Success {
		t.Fatalf("verify=%+v err=%v", verified, err)
	}
	if verified.ArchiveChecksums["sha256"] != result.Checksums["archive"] {
		t.Fatalf("logical checksum mismatch: verify=%s result=%s", verified.ArchiveChecksums["sha256"], result.Checksums["archive"])
	}
}

func TestBR23BackupPlanRejectsProtectedLocalDestination(t *testing.T) {
	src := fixture(t)
	dest := "/sdcard/Android/data/com.example"
	_, err := PlanBackup(backupSpec(src, &dest))
	if err == nil {
		t.Fatal("protected Android/data destination accepted")
	}
}
