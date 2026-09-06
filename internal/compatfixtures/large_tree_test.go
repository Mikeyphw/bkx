package compatfixtures

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"bkx/internal/archiveio"
	"bkx/internal/models"
)

func TestGoLargeTreeReleaseSeal(t *testing.T) {
	if testing.Short() {
		t.Skip("large-tree release seal")
	}
	root := t.TempDir()
	source := filepath.Join(root, "source")
	if err := os.MkdirAll(source, 0o755); err != nil {
		t.Fatal(err)
	}
	chunk := make([]byte, 2048)
	for i := 0; i < 768; i++ {
		dir := filepath.Join(source, "d"+two(i%32), "n"+two((i/32)%8))
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, four(i)+".bin"), chunk, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	out := filepath.Join(root, "large-tree.zip")
	started := time.Now()
	result, err := archiveio.Create(models.ArchiveCreateSpec{
		Compression: models.CompressionProfile{Algorithm: "gzip", Level: 6},
		OutputPath:  out, Sources: []string{source}, SkipSymlinks: true, Verify: true,
	}, false, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !result.Success || result.FilesArchived != 768 || result.BytesWritten <= 0 {
		t.Fatalf("unexpected create result: %#v", result)
	}
	verified, err := archiveio.Verify(out, []string{"sha256"})
	if err != nil {
		t.Fatal(err)
	}
	if !verified.Success || verified.EntriesChecked < 768 {
		t.Fatalf("unexpected verify result: %#v", verified)
	}
	if elapsed := time.Since(started); elapsed > 90*time.Second {
		t.Fatalf("large-tree seal took %s", elapsed)
	}
}

func two(v int) string { return string([]byte{'0' + byte(v/10), '0' + byte(v%10)}) }
func four(v int) string {
	return string([]byte{'0' + byte((v/1000)%10), '0' + byte((v/100)%10), '0' + byte((v/10)%10), '0' + byte(v%10)})
}
