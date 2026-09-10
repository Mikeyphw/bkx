package publiccli

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"bkx/internal/runstore"
	"bkx/internal/statepaths"
)

func TestStateStatusReportsExplicitSharedControlPlane(t *testing.T) {
	home := t.TempDir()
	shared := filepath.Join(t.TempDir(), "shared")
	t.Setenv("HOME", home)
	t.Setenv(statepaths.EnvStateDir, shared)
	var stdout, stderr bytes.Buffer
	code := Run([]string{"--json", "state", "status"}, &stdout, &stderr)
	if code != 0 {
		t.Fatalf("code=%d stderr=%q", code, stderr.String())
	}
	out := stdout.String()
	for _, want := range []string{`"type": "state"`, `"source": "environment"`, `"shared": true`, shared, filepath.Join(shared, "jobs", "jobs.json"), filepath.Join(shared, "repositories", "repositories.json"), filepath.Join(shared, "runs", "runs.jsonl"), filepath.Join(home, ".local", "share", "bkx", "runs", "runs.jsonl")} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in %s", want, out)
		}
	}
}

func TestRunListSeedsLegacyHistoryIntoSharedControlPlane(t *testing.T) {
	home := t.TempDir()
	shared := filepath.Join(t.TempDir(), "shared")
	t.Setenv("HOME", home)
	t.Setenv(statepaths.EnvStateDir, "")

	legacy, err := runstore.New(runstore.LegacyDefaultPath(home), 0, false)
	if err != nil {
		t.Fatal(err)
	}
	archive := filepath.Join(home, "legacy.bakery")
	if _, err := legacy.RecordStart("legacy-shared-run", "backup", []string{filepath.Join(home, "src")}, nil, &archive, map[string]any{"verify": true}); err != nil {
		t.Fatal(err)
	}
	if _, err := legacy.RecordCompletion("legacy-shared-run", "success", nil, nil, nil); err != nil {
		t.Fatal(err)
	}

	t.Setenv(statepaths.EnvStateDir, shared)
	var stdout, stderr bytes.Buffer
	code := Run([]string{"--json", "run", "list"}, &stdout, &stderr)
	if code != 0 {
		t.Fatalf("code=%d stderr=%q", code, stderr.String())
	}
	if !strings.Contains(stdout.String(), `"run_id": "legacy-shared-run"`) {
		t.Fatalf("legacy run missing after shared-state migration: %s", stdout.String())
	}
	sharedHistory := statepaths.RunHistoryPath(home)
	data, err := os.ReadFile(sharedHistory)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(data), `"run_id":"legacy-shared-run"`) {
		t.Fatalf("shared run history was not seeded: %s", data)
	}
	legacyData, err := os.ReadFile(runstore.LegacyDefaultPath(home))
	if err != nil || !strings.Contains(string(legacyData), `"run_id":"legacy-shared-run"`) {
		t.Fatalf("legacy history must remain intact; err=%v data=%s", err, legacyData)
	}
}
