package publiccli

import (
	"bytes"
	"path/filepath"
	"strings"
	"testing"

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
	for _, want := range []string{`"type": "state"`, `"source": "environment"`, `"shared": true`, shared, filepath.Join(shared, "jobs", "jobs.json"), filepath.Join(shared, "repositories", "repositories.json")} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in %s", want, out)
		}
	}
}
