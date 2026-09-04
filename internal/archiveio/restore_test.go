package archiveio

import (
	"archive/tar"
	"archive/zip"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"bkx/internal/models"
)

func restoreSpec(archive, destination string) models.RestoreSpec {
	return models.RestoreSpec{
		Archive: archive, Destination: &destination, Paths: []string{},
		Overwrite: false, Verify: false, DryRun: false, FreshDir: false, ConfirmOverwrite: false,
	}
}

func restoreZip(t *testing.T, path string, entries map[string]string) {
	t.Helper()
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	zw := zip.NewWriter(f)
	for name, payload := range entries {
		w, err := zw.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := w.Write([]byte(payload)); err != nil {
			t.Fatal(err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestParseRestoreSpecJSONRequiresFrozenShape(t *testing.T) {
	full := `{"archive":"/tmp/a.zip","destination":null,"paths":[],"overwrite":false,"verify":true,"dry_run":false,"fresh_dir":true,"confirm_overwrite":true}`
	if _, err := ParseRestoreSpecJSON(full); err != nil {
		t.Fatalf("full frozen shape rejected: %v", err)
	}
	if _, err := ParseRestoreSpecJSON(`{"archive":"/tmp/a.zip"}`); err == nil {
		t.Fatal("partial RestoreSpec JSON unexpectedly accepted")
	}
	if _, err := ParseRestoreSpecJSON(strings.TrimSuffix(full, "}") + `,"extra":1}`); err == nil {
		t.Fatal("unknown RestoreSpec JSON field unexpectedly accepted")
	}
}

func TestRestorePreviewAndExtractZip(t *testing.T) {
	root := t.TempDir()
	archive := filepath.Join(root, "safe.zip")
	restoreZip(t, archive, map[string]string{"dir/a.txt": "alpha", "dir/b.txt": "bravo"})
	dest := filepath.Join(root, "out")
	spec := restoreSpec(archive, dest)

	preview, err := PreviewRestore(spec)
	if err != nil {
		t.Fatal(err)
	}
	if !preview.Success || preview.TotalFiles != 2 || preview.TotalSize != 10 || preview.ExistingFiles != 0 {
		t.Fatalf("preview=%+v", preview)
	}
	result, err := ExtractRestore(spec, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !result.Success || result.FilesRestored != 2 || result.BytesRestored != 10 || len(result.RestoredPaths) != 2 {
		t.Fatalf("result=%+v", result)
	}
	data, _ := os.ReadFile(filepath.Join(dest, "dir", "a.txt"))
	if string(data) != "alpha" {
		t.Fatalf("a=%q", data)
	}
}

func TestRestorePartialSelectorAndMissingSelector(t *testing.T) {
	root := t.TempDir()
	archive := filepath.Join(root, "safe.zip")
	restoreZip(t, archive, map[string]string{"dir/a.txt": "a", "other/b.txt": "b"})
	dest := filepath.Join(root, "out")
	spec := restoreSpec(archive, dest)
	spec.Paths = []string{"dir"}
	preview, err := PreviewRestore(spec)
	if err != nil {
		t.Fatal(err)
	}
	if preview.TotalFiles != 1 || preview.Files[0].ArchivePath != "dir/a.txt" || !preview.Files[0].IsPartial {
		t.Fatalf("preview=%+v", preview)
	}
	missing := restoreSpec(archive, filepath.Join(root, "missing"))
	missing.Paths = []string{"does-not-exist"}
	if _, err := PreviewRestore(missing); err == nil {
		t.Fatal("missing selector unexpectedly accepted")
	}
}

func TestRestoreDryRunAndConfirmation(t *testing.T) {
	root := t.TempDir()
	archive := filepath.Join(root, "safe.zip")
	restoreZip(t, archive, map[string]string{"a.txt": "a"})
	dest := filepath.Join(root, "out")
	if err := os.Mkdir(dest, 0755); err != nil {
		t.Fatal(err)
	}

	confirm := restoreSpec(archive, dest)
	confirm.ConfirmOverwrite = true
	preview, err := PreviewRestore(confirm)
	if err != nil {
		t.Fatal(err)
	}
	if len(preview.Warnings) != 1 {
		t.Fatalf("warnings=%v", preview.Warnings)
	}
	if _, err := ExtractRestore(confirm, nil); err == nil {
		t.Fatal("confirmation gate did not fail")
	}

	dryDest := filepath.Join(root, "dry")
	dry := restoreSpec(archive, dryDest)
	dry.DryRun = true
	result, err := ExtractRestore(dry, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !result.DryRun || result.FilesRestored != 1 || len(result.Warnings) == 0 {
		t.Fatalf("result=%+v", result)
	}
	if pathExists(dryDest) {
		t.Fatalf("dry-run destination exists: %s", dryDest)
	}
}

func TestRestoreRejectsUnsafeArchiveMembers(t *testing.T) {
	root := t.TempDir()
	evil := filepath.Join(root, "evil.tar")
	f, _ := os.Create(evil)
	tw := tar.NewWriter(f)
	payload := []byte("x")
	_ = tw.WriteHeader(&tar.Header{Name: "../escape", Typeflag: tar.TypeReg, Size: 1})
	_, _ = tw.Write(payload)
	_ = tw.Close()
	_ = f.Close()
	if _, err := PreviewRestore(restoreSpec(evil, filepath.Join(root, "out"))); err == nil {
		t.Fatal("traversal accepted")
	}
	if pathExists(filepath.Join(root, "escape")) {
		t.Fatal("escape created")
	}

	link := filepath.Join(root, "link.tar")
	f, _ = os.Create(link)
	tw = tar.NewWriter(f)
	_ = tw.WriteHeader(&tar.Header{Name: "link", Typeflag: tar.TypeSymlink, Linkname: "/tmp/outside"})
	_ = tw.Close()
	_ = f.Close()
	preview, err := PreviewRestore(restoreSpec(link, filepath.Join(root, "out2")))
	if err != nil || preview.TotalFiles != 1 {
		t.Fatalf("link preview parity failed: preview=%+v err=%v", preview, err)
	}
	if _, err := ExtractRestore(restoreSpec(link, filepath.Join(root, "out2")), nil); err == nil {
		t.Fatal("symlink member extraction accepted")
	}
}

func TestRestoreRejectsDestinationSymlink(t *testing.T) {
	root := t.TempDir()
	archive := filepath.Join(root, "safe.zip")
	restoreZip(t, archive, map[string]string{"dir/a.txt": "a"})
	outside := filepath.Join(root, "outside")
	if err := os.Mkdir(outside, 0755); err != nil {
		t.Fatal(err)
	}
	parentLink := filepath.Join(root, "parent-link")
	if err := os.Symlink(outside, parentLink); err != nil {
		t.Fatal(err)
	}
	if _, err := ExtractRestore(restoreSpec(archive, filepath.Join(parentLink, "dest")), nil); err == nil {
		t.Fatal("destination parent symlink accepted")
	}
	if pathExists(filepath.Join(outside, "dest", "dir", "a.txt")) {
		t.Fatal("wrote through symlink")
	}
}

func TestRestoreCollisionPreflightLeavesDestinationUnchanged(t *testing.T) {
	root := t.TempDir()
	archive := filepath.Join(root, "safe.zip")
	restoreZip(t, archive, map[string]string{"a.txt": "new", "nested/b.txt": "b"})
	dest := filepath.Join(root, "out")
	if err := os.Mkdir(dest, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dest, "a.txt"), []byte("old"), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := ExtractRestore(restoreSpec(archive, dest), nil); err == nil {
		t.Fatal("collision accepted")
	}
	old, _ := os.ReadFile(filepath.Join(dest, "a.txt"))
	if string(old) != "old" {
		t.Fatalf("old changed: %q", old)
	}
	if pathExists(filepath.Join(dest, "nested")) {
		t.Fatal("partial directory published")
	}
	matches, _ := filepath.Glob(filepath.Join(root, ".out.bkx-restore.*"))
	if len(matches) != 0 {
		t.Fatalf("staging leaked: %v", matches)
	}
}

func TestRestoreOverwriteMergeRollbackOnPublishFailure(t *testing.T) {
	root := t.TempDir()
	archive := filepath.Join(root, "safe.zip")
	restoreZip(t, archive, map[string]string{"a.txt": "new-a", "b.txt": "new-b"})
	dest := filepath.Join(root, "out")
	if err := os.Mkdir(dest, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dest, "a.txt"), []byte("old-a"), 0644); err != nil {
		t.Fatal(err)
	}
	spec := restoreSpec(archive, dest)
	spec.Overwrite = true

	original := restoreRename
	calls := 0
	restoreRename = func(oldpath, newpath string) error {
		calls++
		// a.txt backup, a.txt publish, then fail b.txt publish after it has no prior target.
		if calls == 3 && strings.HasSuffix(newpath, "b.txt") {
			return os.ErrPermission
		}
		return os.Rename(oldpath, newpath)
	}
	defer func() { restoreRename = original }()
	if _, err := ExtractRestore(spec, nil); err == nil {
		t.Fatal("injected publish failure unexpectedly succeeded")
	}
	a, _ := os.ReadFile(filepath.Join(dest, "a.txt"))
	if string(a) != "old-a" {
		t.Fatalf("a not rolled back: %q", a)
	}
	if pathExists(filepath.Join(dest, "b.txt")) {
		t.Fatal("new b.txt survived rollback")
	}
}

func TestRestoreOverwriteSuccess(t *testing.T) {
	root := t.TempDir()
	archive := filepath.Join(root, "safe.zip")
	restoreZip(t, archive, map[string]string{"a.txt": "new"})
	dest := filepath.Join(root, "out")
	_ = os.Mkdir(dest, 0755)
	_ = os.WriteFile(filepath.Join(dest, "a.txt"), []byte("old"), 0644)
	spec := restoreSpec(archive, dest)
	spec.Overwrite = true
	result, err := ExtractRestore(spec, nil)
	if err != nil {
		t.Fatal(err)
	}
	if result.FilesRestored != 1 {
		t.Fatalf("result=%+v", result)
	}
	data, _ := os.ReadFile(filepath.Join(dest, "a.txt"))
	if string(data) != "new" {
		t.Fatalf("data=%q", data)
	}
}
