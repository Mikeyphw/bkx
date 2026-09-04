package validation

import (
	"path/filepath"
	"testing"

	"bkx/internal/models"
)

func TestCompressionProfileParity(t *testing.T) {
	valid := []struct {
		a string
		l int
	}{{"zstd", 1}, {"zstd", 22}, {"gzip", 1}, {"gzip", 9}, {"lz4", 0}, {"lz4", 16}, {"xz", 0}, {"xz", 9}, {"bzip2", 1}, {"bzip2", 9}, {"none", 0}, {"none", 22}}
	for _, tc := range valid {
		if _, err := CompressionProfile(tc.a, tc.l); err != nil {
			t.Fatalf("valid %#v: %v", tc, err)
		}
	}
	invalid := []struct {
		a string
		l int
	}{{"wat", 3}, {"gzip", 0}, {"gzip", 10}, {"zstd", 0}, {"zstd", 23}, {"bzip2", 0}}
	for _, tc := range invalid {
		if _, err := CompressionProfile(tc.a, tc.l); err == nil {
			t.Fatalf("invalid accepted %#v", tc)
		}
	}
}

func TestArchiveNameAndMemberParity(t *testing.T) {
	if got, err := NormalizeArchiveMember("dir/"); err != nil || got != "dir/" {
		t.Fatalf("directory member = %q, %v; want dir/", got, err)
	}
	for _, name := range []string{"../escape", "a/b", `a\\b`, "/absolute", "C:drive", ".", "..", " "} {
		if _, err := NormalizeArchiveName(name); err == nil {
			t.Fatalf("archive name accepted %q", name)
		}
	}
	for _, member := range []string{"", "../x", "a/../../x", "/abs", `C:\\abs`, ".", "..", " "} {
		if _, err := NormalizeArchiveMember(member); err == nil {
			t.Fatalf("member accepted %q", member)
		}
	}
	if got, err := NormalizeArchiveMember("./docs/readme.txt"); err != nil || got != "docs/readme.txt" {
		t.Fatalf("got=%q err=%v", got, err)
	}
}

func TestContainedPathAndOverlap(t *testing.T) {
	root := t.TempDir()
	got, err := ContainedPath(root, "docs/readme.txt")
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(root, "docs", "readme.txt")
	if got != want {
		t.Fatalf("got=%q want=%q", got, want)
	}
	if _, err := ContainedPath(root, "../escape"); err == nil {
		t.Fatal("escape accepted")
	}
	child := filepath.Join(root, "child")
	overlap, err := PathsOverlap(root, child)
	if err != nil || !overlap {
		t.Fatalf("overlap=%v err=%v", overlap, err)
	}
}

func TestPatternAndSplitParity(t *testing.T) {
	inc, exc, err := ValidatePatternSets([]string{"*.txt", "*.txt"}, []string{"*.tmp"})
	if err != nil || len(inc) != 1 || len(exc) != 1 {
		t.Fatalf("inc=%v exc=%v err=%v", inc, exc, err)
	}
	if _, _, err := ValidatePatternSets([]string{"*.txt"}, []string{"*.txt"}); err == nil {
		t.Fatal("conflict accepted")
	}
	zero := int64(0)
	if _, err := ValidateSplitSize(&zero); err == nil {
		t.Fatal("zero split accepted")
	}
}

func TestTransferLocationPreservesURI(t *testing.T) {
	got, err := NormalizeTransferLocation("s3://bucket/key", "destination")
	if err != nil || got != "s3://bucket/key" {
		t.Fatalf("got=%q err=%v", got, err)
	}
}

func TestDeterministicNameStable(t *testing.T) {
	a := DeterministicName("a b", "/one", "/two")
	b := DeterministicName("a b", "/one", "/two")
	if a != b || len(a) < 13 {
		t.Fatalf("a=%q b=%q", a, b)
	}
}

func TestFrozenSpecShapeValidation(t *testing.T) {
	profile := models.CompressionProfile{Algorithm: "zstd", Level: 3}
	name := "stable"
	backup, err := ValidateBackupSpec(models.BackupSpec{Sources: []string{"/src"}, ArchiveName: &name, Compression: profile, Verify: true})
	if err != nil || backup.ArchiveName == nil || *backup.ArchiveName != "stable" {
		t.Fatalf("backup=%#v err=%v", backup, err)
	}
	if _, err := ValidateBackupSpec(models.BackupSpec{Sources: []string{"/src"}, Compression: models.CompressionProfile{Algorithm: "wat", Level: 3}}); err == nil {
		t.Fatal("invalid compression accepted")
	}
	if _, err := ValidateRestoreSpec(models.RestoreSpec{Archive: "/a", Paths: []string{"docs/a", "./docs/a"}}); err == nil {
		t.Fatal("duplicate normalized restore selectors accepted")
	}
	if _, err := ValidateRestoreSpec(models.RestoreSpec{Archive: "/a", Overwrite: true, ConfirmOverwrite: true}); err == nil {
		t.Fatal("impossible overwrite policy accepted")
	}
	if _, err := ValidateTransferSpec(models.TransferSpec{Sources: []string{"s3://bucket/key"}, Destination: "/tmp", Direction: "push"}); err == nil {
		t.Fatal("remote push source accepted")
	}
}
