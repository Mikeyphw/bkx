package fsutil

import (
	"os"
	"path/filepath"
	"testing"
)

func TestTraversalDeterministicAndPatterns(t *testing.T) {
	parent := t.TempDir()
	root := filepath.Join(parent, "root")
	if err := os.MkdirAll(filepath.Join(root, "sub"), 0o755); err != nil {
		t.Fatal(err)
	}
	_ = os.WriteFile(filepath.Join(root, "b.log"), []byte("b"), 0o644)
	_ = os.WriteFile(filepath.Join(root, "a.txt"), []byte("a"), 0o644)
	_ = os.WriteFile(filepath.Join(root, "sub", "c.txt"), []byte("c"), 0o644)
	got, err := BuildSourceManifest([]string{root}, []string{"*.txt"}, nil, "", SymlinkReject)
	if err != nil {
		t.Fatal(err)
	}
	names := map[string]bool{}
	for _, e := range got.Entries {
		names[e.ArchiveName] = true
	}
	if names["root/b.log"] || !names["root/a.txt"] || !names["root/sub/c.txt"] {
		t.Fatalf("fnmatch parity mismatch: %#v", names)
	}
}

func TestTraversalRejectsAndSkipsSymlinks(t *testing.T) {
	root := t.TempDir()
	target := filepath.Join(root, "target.txt")
	_ = os.WriteFile(target, []byte("x"), 0o644)
	link := filepath.Join(root, "link.txt")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	if _, err := BuildSourceManifest([]string{root}, nil, nil, "", SymlinkReject); err == nil {
		t.Fatal("expected reject")
	}
	got, err := BuildSourceManifest([]string{root}, nil, nil, "", SymlinkSkip)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range got.Entries {
		if e.IsSymlink {
			t.Fatalf("skip emitted symlink: %#v", e)
		}
	}
}

func TestTraversalRejectsOutputOverlap(t *testing.T) {
	root := t.TempDir()
	output := filepath.Join(root, "out.tar")
	if _, err := BuildSourceManifest([]string{root}, nil, nil, output, SymlinkReject); err == nil {
		t.Fatal("expected overlap rejection")
	}
}
