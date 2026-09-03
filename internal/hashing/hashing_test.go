package hashing

import (
	"crypto/md5"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"testing"
)

func TestFileAlgorithmsAndVerify(t *testing.T) {
	path := filepath.Join(t.TempDir(), "hello.txt")
	data := []byte("hello world")
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
	got, err := File(path, "sha256")
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(data)
	if got.Checksum != hex.EncodeToString(sum[:]) || got.BytesHashed != int64(len(data)) {
		t.Fatalf("sha mismatch %#v", got)
	}
	md, err := File(path, "md5")
	if err != nil {
		t.Fatal(err)
	}
	mdSum := md5.Sum(data)
	if md.Checksum != hex.EncodeToString(mdSum[:]) {
		t.Fatalf("md5 mismatch %#v", md)
	}
	ok, err := Verify(path, got.Checksum)
	if err != nil || !ok {
		t.Fatalf("verify failed ok=%v err=%v", ok, err)
	}
}

func TestDirectoryHashDeterministic(t *testing.T) {
	parent := t.TempDir()
	root := filepath.Join(parent, "root")
	if err := os.Mkdir(root, 0o755); err != nil {
		t.Fatal(err)
	}
	_ = os.WriteFile(filepath.Join(root, "b.txt"), []byte("b"), 0o644)
	_ = os.WriteFile(filepath.Join(root, "a.txt"), []byte("a"), 0o644)
	first, err := Directory(root, "sha256", true)
	if err != nil {
		t.Fatal(err)
	}
	second, err := Directory(root, "sha256", true)
	if err != nil {
		t.Fatal(err)
	}
	if first.DirectoryChecksum != second.DirectoryChecksum || first.FileCount != 2 || first.TotalBytes != 2 {
		t.Fatalf("directory hash mismatch %#v %#v", first, second)
	}
}

func TestHashRejectsSymlink(t *testing.T) {
	root := t.TempDir()
	target := filepath.Join(root, "target")
	_ = os.WriteFile(target, []byte("x"), 0o644)
	link := filepath.Join(root, "link")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	if _, err := File(link, "sha256"); err == nil {
		t.Fatal("expected symlink rejection")
	}
}
