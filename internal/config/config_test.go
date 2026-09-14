package config

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"bkx/internal/berror"
)

func TestDefaultsMatchFrozenContract(t *testing.T) {
	c := Defaults()
	if !c.VerifyAfterOperation || c.CompressionAlgorithm != "zstd" || c.CompressionLevel != 3 || !c.FreshRestoreDir || !c.RunHistoryEnabled || c.RunHistoryLimit != 50 || c.Trace {
		t.Fatalf("unexpected defaults: %#v", c)
	}
}

func TestEnvironmentParsingAndProvenance(t *testing.T) {
	cwd := t.TempDir()
	c, err := FromEnv(map[string]string{"BKX_VERIFY": "off", "BKX_COMPRESSION_LEVEL": "7", "BKX_DESTINATION": "out", "BKX_HISTORY_LIMIT": "12"}, cwd)
	if err != nil {
		t.Fatal(err)
	}
	if c.VerifyAfterOperation || c.CompressionLevel != 7 || c.RunHistoryLimit != 12 {
		t.Fatalf("bad env config: %#v", c)
	}
	if c.DefaultDestination == nil || *c.DefaultDestination != filepath.Join(cwd, "out") {
		t.Fatalf("bad destination: %#v", c.DefaultDestination)
	}
	if got, _ := c.SourceOf("compression_level"); got != SourceEnv {
		t.Fatalf("source=%s", got)
	}
}

func TestInvalidEnvironmentValuesFailTyped(t *testing.T) {
	for _, env := range []map[string]string{{"BKX_VERIFY": "definitely"}, {"BKX_COMPRESSION_LEVEL": "fast"}, {"BKX_HISTORY_LIMIT": "0"}} {
		if _, err := FromEnv(env, t.TempDir()); err == nil {
			t.Fatalf("expected failure for %#v", env)
		}
	}
}

func TestFileThenEnvironmentPrecedence(t *testing.T) {
	cwd := t.TempDir()
	home := t.TempDir()
	if err := os.WriteFile(filepath.Join(cwd, ".bkx.yaml"), []byte("compression_level: 5\nverify_after_operation: false\ndefault_destination: rel\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	c, err := Load(cwd, home, map[string]string{"BKX_COMPRESSION_LEVEL": "9"})
	if err != nil {
		t.Fatal(err)
	}
	if c.CompressionLevel != 9 || c.VerifyAfterOperation {
		t.Fatalf("precedence failed: %#v", c)
	}
	if src, _ := c.SourceOf("compression_level"); src != SourceEnv {
		t.Fatalf("compression source=%s", src)
	}
	if src, _ := c.SourceOf("verify_after_operation"); src != SourceFile {
		t.Fatalf("verify source=%s", src)
	}
	if c.DefaultDestination == nil || *c.DefaultDestination != filepath.Join(cwd, "rel") {
		t.Fatalf("file path=%v", c.DefaultDestination)
	}
}

func TestResolveCLIWins(t *testing.T) {
	c := Defaults()
	got, err := c.Resolve("compression_level", 11, true)
	if err != nil {
		t.Fatal(err)
	}
	if got.Source != SourceCLI || got.Value != 11 {
		t.Fatalf("resolved=%#v", got)
	}
}

func TestBR34ConfigErrorsAreDeterministicAndDirectoriesAreErrors(t *testing.T) {
	_, err := FromMap(map[string]any{"zzz": true, "aaa": false}, t.TempDir())
	if err == nil || !strings.Contains(err.Error(), "Unknown BKX configuration") {
		t.Fatalf("expected unknown key error, got %v", err)
	}
	var coded *berror.Error
	if !errors.As(err, &coded) {
		t.Fatalf("expected typed error, got %T", err)
	}
	keys, ok := coded.Details["keys"].([]string)
	if !ok || len(keys) != 2 || keys[0] != "aaa" || keys[1] != "zzz" {
		t.Fatalf("unknown keys not deterministic: %#v", coded.Details["keys"])
	}

	cwd := t.TempDir()
	if err := os.Mkdir(filepath.Join(cwd, ".bkx.yaml"), 0o700); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(cwd, t.TempDir(), map[string]string{}); err == nil {
		t.Fatal("expected config-directory error")
	}
}

func TestBR34ConfigTildeExpansionUsesSuppliedHome(t *testing.T) {
	home := filepath.Join(t.TempDir(), "home")
	cfg, err := fromMapWithHome(map[string]any{"default_destination": "~/bkx"}, t.TempDir(), home)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.DefaultDestination == nil || *cfg.DefaultDestination != filepath.Join(home, "bkx") {
		t.Fatalf("destination=%v want %s", cfg.DefaultDestination, filepath.Join(home, "bkx"))
	}
}
