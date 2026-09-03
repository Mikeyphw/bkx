package fsutil

import (
	"os"
	"path/filepath"
	"testing"
)

func TestStatAndListParityShape(t *testing.T) {
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "Dir"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "b.txt"), []byte("bb"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "A.txt"), []byte("a"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, ".hidden"), []byte("h"), 0o644); err != nil {
		t.Fatal(err)
	}

	got, err := List(root, false, 250, 0)
	if err != nil {
		t.Fatal(err)
	}
	if got.TotalCount != 3 || len(got.Entries) != 3 || got.More {
		t.Fatalf("unexpected list result: %#v", got)
	}
	if got.Entries[0].Name != "Dir" || got.Entries[0].Kind != "directory" {
		t.Fatalf("directory-first sort mismatch: %#v", got.Entries)
	}
	if got.Entries[1].Name != "A.txt" || got.Entries[2].Name != "b.txt" {
		t.Fatalf("casefold/name sort mismatch: %#v", got.Entries)
	}
	stat, err := Stat(filepath.Join(root, "b.txt"))
	if err != nil {
		t.Fatal(err)
	}
	if stat.Kind != "file" || stat.SizeBytes != 2 {
		t.Fatalf("unexpected stat: %#v", stat)
	}
}

func TestListPaginationAndSymlinkLstat(t *testing.T) {
	root := t.TempDir()
	target := filepath.Join(root, "target")
	if err := os.Mkdir(target, 0o755); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(root, "link")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	got, err := List(root, true, 1, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Entries) != 1 || !got.More {
		t.Fatalf("pagination mismatch: %#v", got)
	}
	all, err := List(root, true, 10, 0)
	if err != nil {
		t.Fatal(err)
	}
	kinds := map[string]string{}
	for _, e := range all.Entries {
		kinds[e.Name] = e.Kind
	}
	if kinds["link"] != "symlink" {
		t.Fatalf("expected lstat symlink kind: %#v", kinds)
	}
}

func TestStatMissing(t *testing.T) {
	if _, err := Stat(filepath.Join(t.TempDir(), "missing")); err == nil {
		t.Fatal("expected missing-path error")
	}
}
