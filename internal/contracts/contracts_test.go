package contracts

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func contractDir(t *testing.T) string {
	t.Helper()
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	return filepath.Clean(filepath.Join(filepath.Dir(file), "..", "..", "migration", "contracts", "v1"))
}

func TestFrozenContractBundleValidates(t *testing.T) {
	bundle, err := LoadDir(contractDir(t))
	if err != nil {
		t.Fatal(err)
	}
	if err := bundle.Validate(); err != nil {
		t.Fatal(err)
	}
}

func TestManifestDetectsFixtureDrift(t *testing.T) {
	source := contractDir(t)
	dest := t.TempDir()
	if err := filepath.Walk(source, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(source, path)
		if err != nil {
			return err
		}
		target := filepath.Join(dest, rel)
		if info.IsDir() {
			return os.MkdirAll(target, 0o755)
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		return os.WriteFile(target, data, info.Mode().Perm())
	}); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dest, "cli.json")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, append(data, '\n'), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadDir(dest); err == nil {
		t.Fatal("expected manifest drift to be rejected")
	}
}
