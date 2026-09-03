package tools

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func writeTool(t *testing.T, dir, name, version string, executable bool) string {
	t.Helper()
	p := filepath.Join(dir, name)
	mode := os.FileMode(0o644)
	if executable {
		mode = 0o755
	}
	if err := os.WriteFile(p, []byte("#!/bin/sh\necho '"+name+" (fake) "+version+"'\n"), mode); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestDetectorVersionExecutableAndPathInvalidation(t *testing.T) {
	first := t.TempDir()
	second := t.TempDir()
	writeTool(t, first, "sha256sum", "1.2.3", true)
	writeTool(t, second, "sha256sum", "9.8.7", true)
	d, err := New(nil, map[string]string{"PATH": first}, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	a, err := d.Detect("sha256sum")
	if err != nil {
		t.Fatal(err)
	}
	if a.Version == nil || *a.Version != "1.2.3" || !a.Available {
		t.Fatalf("first probe %#v", a)
	}
	d.SetEnv("PATH", second)
	b, err := d.Detect("sha256sum")
	if err != nil {
		t.Fatal(err)
	}
	if b.Version == nil || *b.Version != "9.8.7" {
		t.Fatalf("cache invalidation %#v", b)
	}
}

func TestDetectorPreferenceRequiresExecutable(t *testing.T) {
	dir := t.TempDir()
	p := writeTool(t, dir, "rsync", "1.0.0", false)
	d, err := New(map[string]string{"transfer": "rsync"}, map[string]string{"PATH": dir}, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	pref, err := d.Preferred("transfer")
	if err != nil {
		t.Fatal(err)
	}
	if pref != "" {
		t.Fatalf("nonexec preferred %q", pref)
	}
	if err := os.Chmod(p, 0o755); err != nil {
		t.Fatal(err)
	}
	d.ClearCache()
	pref, err = d.Preferred("transfer")
	if err != nil {
		t.Fatal(err)
	}
	if pref != "rsync" {
		t.Fatalf("preferred %q", pref)
	}
}
