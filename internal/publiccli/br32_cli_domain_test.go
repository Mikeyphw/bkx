package publiccli

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"bkx/internal/archiveio"
	"bkx/internal/berror"
	"bkx/internal/models"
)

func TestBR32BackupPlanCreateOptionParity(t *testing.T) {
	src := t.TempDir()
	if err := os.WriteFile(filepath.Join(src, "a.txt"), []byte("alpha"), 0o600); err != nil {
		t.Fatal(err)
	}
	var stdout, stderr bytes.Buffer
	code := Run([]string{"--json", "backup", "plan", src}, &stdout, &stderr)
	if code != berror.ExitUsage || !strings.Contains(stdout.String(), "backup destination is required") {
		t.Fatalf("missing destination must fail like execution: code=%d stdout=%q stderr=%q", code, stdout.String(), stderr.String())
	}
	stdout.Reset()
	stderr.Reset()
	code = Run([]string{"--json", "backup", "plan", "--engine=restic", "--repository", "repo:/x", "--compression=gzip", src}, &stdout, &stderr)
	if code != berror.ExitUsage || !strings.Contains(stdout.String(), "not supported by the restic engine") {
		t.Fatalf("restic-specific option rejection missing: code=%d stdout=%q stderr=%q", code, stdout.String(), stderr.String())
	}
	stdout.Reset()
	stderr.Reset()
	dst := t.TempDir()
	code = Run([]string{"--json", "backup", "plan", "--destination", dst, "--compression", "gzip", "--include", "*.txt", src}, &stdout, &stderr)
	if code != berror.ExitOK || !strings.Contains(stdout.String(), "\"spec_type\": \"backup\"") {
		t.Fatalf("valid Bakery backup plan failed: code=%d stdout=%q stderr=%q", code, stdout.String(), stderr.String())
	}
}

func TestBR32RestoreDryRunUsesArchivePreviewCounts(t *testing.T) {
	src := t.TempDir()
	if err := os.WriteFile(filepath.Join(src, "one.txt"), []byte("one"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(src, "two.txt"), []byte("two-two"), 0o600); err != nil {
		t.Fatal(err)
	}
	archive := filepath.Join(t.TempDir(), "sample.tar")
	_, err := archiveio.Create(models.ArchiveCreateSpec{
		Sources: []string{src}, OutputPath: archive,
		Compression: models.CompressionProfile{Algorithm: "none", Level: 0}, Verify: false,
	}, true, nil)
	if err != nil {
		t.Fatal(err)
	}
	var stdout, stderr bytes.Buffer
	code := Run([]string{"--json", "--dry-run", "restore", "pick", archive, filepath.Base(src) + "/one.txt"}, &stdout, &stderr)
	if code != berror.ExitOK || !strings.Contains(stdout.String(), "\"spec_type\": \"restore\"") || !strings.Contains(stdout.String(), "total_files") {
		t.Fatalf("restore preview-backed dry run failed: code=%d stdout=%q stderr=%q", code, stdout.String(), stderr.String())
	}
}
