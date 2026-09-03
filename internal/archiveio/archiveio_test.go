package archiveio

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func writeFixtures(t *testing.T, root string) (string, string) {
	t.Helper()
	zipPath := filepath.Join(root, "fixture.zip")
	zf, err := os.Create(zipPath)
	if err != nil {
		t.Fatal(err)
	}
	zw := zip.NewWriter(zf)
	dh := &zip.FileHeader{Name: "root/", Method: zip.Store}
	dh.SetMode(os.ModeDir | 0755)
	dh.SetModTime(time.Date(2024, 1, 2, 3, 4, 6, 0, time.Local))
	if _, err := zw.CreateHeader(dh); err != nil {
		t.Fatal(err)
	}
	fh := &zip.FileHeader{Name: "root/hello.txt", Method: zip.Store}
	fh.SetMode(0644)
	fh.SetModTime(time.Date(2024, 1, 2, 3, 4, 6, 0, time.Local))
	w, err := zw.CreateHeader(fh)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = w.Write([]byte("hello"))
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := zf.Close(); err != nil {
		t.Fatal(err)
	}

	tarPath := filepath.Join(root, "fixture.tar")
	tf, err := os.Create(tarPath)
	if err != nil {
		t.Fatal(err)
	}
	tw := tar.NewWriter(tf)
	mod := time.Unix(1704164646, 0)
	if err := tw.WriteHeader(&tar.Header{Name: "root/", Typeflag: tar.TypeDir, Mode: 0755, ModTime: mod}); err != nil {
		t.Fatal(err)
	}
	payload := []byte("hello")
	if err := tw.WriteHeader(&tar.Header{Name: "root/hello.txt", Typeflag: tar.TypeReg, Mode: 0644, Size: int64(len(payload)), ModTime: mod}); err != nil {
		t.Fatal(err)
	}
	if _, err := tw.Write(payload); err != nil {
		t.Fatal(err)
	}
	if err := tw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := tf.Close(); err != nil {
		t.Fatal(err)
	}
	return zipPath, tarPath
}

func TestListInfoBrowseZipAndTar(t *testing.T) {
	zipPath, tarPath := writeFixtures(t, t.TempDir())
	for _, path := range []string{zipPath, tarPath} {
		list, err := List(path, nil, 0, 100)
		if err != nil {
			t.Fatal(err)
		}
		if list.TotalEntries != 2 || len(list.Entries) != 2 {
			t.Fatalf("list=%+v", list)
		}
		info, err := Info(path)
		if err != nil {
			t.Fatal(err)
		}
		if info.TotalEntries != 2 || info.UncompressedSize != 5 {
			t.Fatalf("info=%+v", info)
		}
		query := "hello"
		fileType := "file"
		browse, err := Browse(path, BrowseOptions{Query: &query, FileType: &fileType, Limit: 50, SortBy: "path", SortOrder: "asc"})
		if err != nil {
			t.Fatal(err)
		}
		if browse.TotalMatches != 1 || browse.FilesCount != 1 || browse.Entries[0].Path != "root/hello.txt" {
			t.Fatalf("browse=%+v", browse)
		}
	}
}

func TestListPythonFnmatchAndPagination(t *testing.T) {
	zipPath, _ := writeFixtures(t, t.TempDir())
	pattern := "*.txt"
	list, err := List(zipPath, &pattern, 0, 1)
	if err != nil {
		t.Fatal(err)
	}
	if list.TotalEntries != 1 || len(list.Entries) != 1 || list.Entries[0].Path != "root/hello.txt" {
		t.Fatalf("list=%+v", list)
	}
}

func TestVerifySafeAndUnsafe(t *testing.T) {
	zipPath, _ := writeFixtures(t, t.TempDir())
	ok, err := Verify(zipPath, []string{"sha256", "md5", "sha256"})
	if err != nil {
		t.Fatal(err)
	}
	if !ok.Success || ok.EntriesChecked != 2 || len(ok.ArchiveChecksums) != 2 {
		t.Fatalf("verify=%+v", ok)
	}

	unsafe := filepath.Join(t.TempDir(), "unsafe.tar")
	f, _ := os.Create(unsafe)
	tw := tar.NewWriter(f)
	payload := []byte("x")
	_ = tw.WriteHeader(&tar.Header{Name: "../escape", Typeflag: tar.TypeReg, Size: 1})
	_, _ = tw.Write(payload)
	_ = tw.Close()
	_ = f.Close()
	bad, err := Verify(unsafe, nil)
	if err != nil {
		t.Fatal(err)
	}
	if bad.Success || bad.EntriesFailed != 1 {
		t.Fatalf("unsafe verify=%+v", bad)
	}
}

func TestVerifyTarRejectsLinks(t *testing.T) {
	path := filepath.Join(t.TempDir(), "link.tar")
	f, _ := os.Create(path)
	tw := tar.NewWriter(f)
	_ = tw.WriteHeader(&tar.Header{Name: "link", Typeflag: tar.TypeSymlink, Linkname: "target"})
	_ = tw.Close()
	_ = f.Close()
	result, err := Verify(path, nil)
	if err != nil {
		t.Fatal(err)
	}
	if result.Success || result.EntriesFailed != 1 {
		t.Fatalf("result=%+v", result)
	}
}

func TestCorruptZipSuffixIsStructuralFailure(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bad.zip")
	if err := os.WriteFile(path, bytes.Repeat([]byte{0xff}, 64), 0644); err != nil {
		t.Fatal(err)
	}
	result, err := Verify(path, nil)
	if err != nil {
		t.Fatal(err)
	}
	if result.Success || result.StructureValid {
		t.Fatalf("result=%+v", result)
	}
}
