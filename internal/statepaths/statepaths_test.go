package statepaths

import (
	"os"
	"path/filepath"
	"testing"
)

func TestResolveDefaultsToHomeAndHonorsExplicitStateDir(t *testing.T) {
	t.Setenv(EnvStateDir, "")
	home := t.TempDir()
	got := Resolve(home)
	if got.Shared || got.Source != "home" || got.Root != filepath.Join(home, ".local", "share", "bkx") {
		t.Fatalf("unexpected home resolution: %#v", got)
	}
	explicit := filepath.Join(t.TempDir(), "shared")
	t.Setenv(EnvStateDir, explicit)
	got = Resolve(home)
	if got.Root != explicit || got.Source != "environment" || !got.Shared {
		t.Fatalf("unexpected explicit resolution: %#v", got)
	}
}

func TestEnsureMarkerAndSeedLegacyFile(t *testing.T) {
	root := filepath.Join(t.TempDir(), "shared")
	if err := EnsureMarker(root, "test"); err != nil {
		t.Fatal(err)
	}
	marker, err := ReadMarker(root)
	if err != nil {
		t.Fatal(err)
	}
	if marker.Kind != "bkx-shared-control-plane" || marker.SchemaVersion != StateSchemaVersion {
		t.Fatalf("bad marker: %#v", marker)
	}

	legacy := filepath.Join(t.TempDir(), "jobs.json")
	if err := os.WriteFile(legacy, []byte("legacy\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	canonical := filepath.Join(root, "jobs", "jobs.json")
	if err := SeedLegacyFile(canonical, legacy); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(canonical)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "legacy\n" {
		t.Fatalf("unexpected seed: %q", data)
	}

	if err := os.WriteFile(canonical, []byte("canonical\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(legacy, []byte("changed\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := SeedLegacyFile(canonical, legacy); err != nil {
		t.Fatal(err)
	}
	data, _ = os.ReadFile(canonical)
	if string(data) != "canonical\n" {
		t.Fatalf("canonical file was overwritten: %q", data)
	}
}

func TestTermuxHomeUsesInitializedSharedMarker(t *testing.T) {
	t.Setenv(EnvStateDir, "")
	shared := filepath.Join(t.TempDir(), "android-shared")
	t.Setenv(EnvAndroidSharedStateDir, shared)
	if err := EnsureMarker(shared, "android"); err != nil {
		t.Fatal(err)
	}
	got := Resolve("/data/data/com.termux/files/home")
	if got.Root != shared || got.Source != "android-shared-marker" || !got.Shared {
		t.Fatalf("unexpected Termux shared resolution: %#v", got)
	}
}

func TestCanonicalTermuxHomeRecognitionIsNarrow(t *testing.T) {
	if !IsCanonicalTermuxHome("/data/data/com.termux/files/home") {
		t.Fatal("standard Termux home should match")
	}
	if !IsCanonicalTermuxHome("/data/user/0/com.termux/files/home") {
		t.Fatal("user-0 Termux home should match")
	}
	if IsCanonicalTermuxHome(t.TempDir()) {
		t.Fatal("temporary HOME must not trigger shared Android state")
	}
}

func TestRunHistoryFollowsCanonicalStateResolution(t *testing.T) {
	home := t.TempDir()
	shared := filepath.Join(t.TempDir(), "shared")
	t.Setenv(EnvStateDir, shared)
	if got, want := RunHistoryPath(home), filepath.Join(shared, "runs", "runs.jsonl"); got != want {
		t.Fatalf("run history path=%q want=%q", got, want)
	}
	if got, want := LegacyRunHistoryPath(home), filepath.Join(home, ".local", "share", "bkx", "runs", "runs.jsonl"); got != want {
		t.Fatalf("legacy run history path=%q want=%q", got, want)
	}
}
