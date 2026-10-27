package rcloneops

import (
	"os"
	"path/filepath"
	"testing"
)

func TestAPI03SkybindProviderIsRootOnlySourceBoundAndOptIn(t *testing.T) {
	root := t.TempDir()
	if err := os.Chmod(root, 0700); err != nil {
		t.Fatal(err)
	}
	module := filepath.Join(root, "module")
	config := filepath.Join(root, "config")
	for _, p := range []string{module, filepath.Join(module, "system"), filepath.Join(module, "system", "bin"), config} {
		if err := os.MkdirAll(p, 0700); err != nil {
			t.Fatal(err)
		}
	}
	for _, name := range []string{"rclone", "skybind-rclone", "skybind-rclone-engine"} {
		if err := os.WriteFile(filepath.Join(module, "system", "bin", name), []byte("fixture"), 0700); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(module, "module.prop"), []byte("id=skybind\nname=Skybind\n"), 0644); err != nil {
		t.Fatal(err)
	}
	cfg := filepath.Join(config, "rclone.conf")
	if err := os.WriteFile(cfg, []byte("[drive]\ntype = drive\n"), 0600); err != nil {
		t.Fatal(err)
	}
	// Synthetic fixtures belong to the unprivileged test UID. They may not
	// exercise the production root-only ownership policy as fake UID 0.
	owner := uint32(os.Getuid())
	resolveFixture := func(uid int) (Runtime, error) {
		return resolveSkybindProviderWithOwner(uid, module, config, owner)
	}
	if os.Getuid() != 0 {
		if _, err := resolveSkybindProvider(0, module, config); err == nil {
			t.Fatal("production resolver trusted non-root-owned synthetic paths")
		}
	}
	if _, err := resolveFixture(10056); err == nil {
		t.Fatal("nonroot accepted")
	}
	got, err := resolveFixture(0)
	if err != nil || got.Config != cfg || got.Executable != filepath.Join(module, "system", "bin", "rclone") {
		t.Fatalf("%+v %v", got, err)
	}
	for _, marker := range []string{"disable", "remove"} {
		markerPath := filepath.Join(module, marker)
		if err := os.WriteFile(markerPath, []byte{}, 0600); err != nil {
			t.Fatal(err)
		}
		if _, err := resolveFixture(0); err == nil {
			t.Fatalf("disabled/pending-remove Skybind module accepted: %s", marker)
		}
		if err := os.Remove(markerPath); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.Remove(filepath.Join(module, "module.prop")); err != nil {
		t.Fatal(err)
	}
	if _, err := resolveFixture(0); err == nil {
		t.Fatal("missing module identity accepted")
	}
	if err := os.WriteFile(filepath.Join(module, "module.prop"), []byte("id=skybind\nname=Skybind\n"), 0644); err != nil {
		t.Fatal(err)
	}
	for _, wrongIdentity := range []string{"id=other\n", "name=Skybind\n", "id=skybind\nid=skybind\n"} {
		if err := os.WriteFile(filepath.Join(module, "module.prop"), []byte(wrongIdentity), 0644); err != nil {
			t.Fatal(err)
		}
		if _, err := resolveFixture(0); err == nil {
			t.Fatalf("invalid module identity accepted: %q", wrongIdentity)
		}
	}
	if err := os.WriteFile(filepath.Join(module, "module.prop"), []byte("id=skybind\nname=Skybind\n"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(module, 0777); err != nil {
		t.Fatal(err)
	}
	if _, err := resolveFixture(0); err == nil {
		t.Fatal("world-writable module accepted")
	}
	if err := os.Chmod(module, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(cfg, 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := resolveFixture(0); err == nil {
		t.Fatal("public config accepted")
	}
	if err := os.Chmod(cfg, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(module, "system", "bin", "rclone")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(module, "system", "bin", "skybind-rclone-engine"), filepath.Join(module, "system", "bin", "rclone")); err != nil {
		t.Fatal(err)
	}
	if _, err := resolveFixture(0); err == nil {
		t.Fatal("symlink launcher accepted")
	}
}
func TestAPI03ProviderDoesNotSilentlyChangeBundledRclone(t *testing.T) {
	for _, environ := range [][]string{nil, {"BKX_RCLONE_PROVIDER=bundled"}} {
		if _, selected, err := skybindRuntimeFromEnviron(environ); selected || err != nil {
			t.Fatalf("unexpected selection %v %v", selected, err)
		}
	}
	for _, environ := range [][]string{{"BKX_RCLONE_PROVIDER=skybind", "BKX_RCLONE_PATH=/tmp/exe"}, {"BKX_RCLONE_PROVIDER=skybind", "BKX_RCLONE_CONFIG=/tmp/conf"}, {"BKX_RCLONE_PROVIDER=unknown"}} {
		if _, selected, err := skybindRuntimeFromEnviron(environ); !selected || err == nil {
			t.Fatalf("expected fail closed: %v %v", selected, err)
		}
	}
}
