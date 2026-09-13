package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestBR31YAMLScalarCompatibility(t *testing.T) {
	cwd := t.TempDir()
	home := t.TempDir()
	configPath := filepath.Join(cwd, ".bkx.yaml")
	if err := os.WriteFile(configPath, []byte("---\ncompression_level: 6 # inline comment\nverify_after_operation: off\ndefault_destination: \"relative:with:colon\"\npreferred_transfer: 'rclone remote'\ntrace: yes\n...\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	c, err := Load(cwd, home, map[string]string{})
	if err != nil {
		t.Fatal(err)
	}
	if c.CompressionLevel != 6 || c.VerifyAfterOperation || !c.Trace || c.PreferredTransfer == nil || *c.PreferredTransfer != "rclone remote" {
		t.Fatalf("bad config: %#v", c)
	}
	if c.DefaultDestination == nil || *c.DefaultDestination != filepath.Join(cwd, "relative:with:colon") {
		t.Fatalf("bad path: %#v", c.DefaultDestination)
	}
}

func TestBR31RejectsNestedYAMLInsteadOfSilentlyFlattening(t *testing.T) {
	path := filepath.Join(t.TempDir(), ".bkx.yaml")
	if err := os.WriteFile(path, []byte("tools:\n  transfer: rclone\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := parseFlatYAML(path); err == nil {
		t.Fatal("expected nested yaml rejection")
	}
}
