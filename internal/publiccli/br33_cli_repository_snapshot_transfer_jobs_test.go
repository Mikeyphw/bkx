package publiccli

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"bkx/internal/berror"
	"bkx/internal/models"
	"bkx/internal/runstore"
)

func withTempHome(t *testing.T) string {
	t.Helper()
	home := t.TempDir()
	t.Setenv("HOME", home)
	return home
}

func TestBR33TransferSyncAndRunListPagingArePublicContracts(t *testing.T) {
	home := withTempHome(t)
	store, err := runstore.New(runstore.DefaultPath(home), 0, false)
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{"run-a", "run-b"} {
		if _, err := store.RecordStart(id, "backup", []string{"/src"}, nil, nil, map[string]any{"id": id}); err != nil {
			t.Fatal(err)
		}
	}
	var stdout, stderr bytes.Buffer
	code := Run([]string{"--json", "run", "list", "--limit", "1"}, &stdout, &stderr)
	if code != berror.ExitOK || !strings.Contains(stdout.String(), "next_page_token") {
		t.Fatalf("first page failed: code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	if _, ok := commandHandlers["transfer sync"]; !ok {
		t.Fatal("transfer sync handler missing")
	}
	stdout.Reset()
	stderr.Reset()
	code = Run([]string{"transfer", "sync", "--help"}, &stdout, &stderr)
	if code != berror.ExitOK || !strings.Contains(stdout.String(), "transfer sync") {
		t.Fatalf("transfer sync help missing: code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
}

func TestBR33JobExportRefusesOverwriteAndSecretOverrides(t *testing.T) {
	withTempHome(t)
	var stdout, stderr bytes.Buffer
	params := `{"sources":["/tmp/a"],"destination":"/tmp/b","verify":false}`
	code := Run([]string{"--json", "job", "create", "nightly", "--operation", "backup.create", "--params-json", params}, &stdout, &stderr)
	if code != berror.ExitOK {
		t.Fatalf("create job failed: code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	out := filepath.Join(t.TempDir(), "job.json")
	if err := os.WriteFile(out, []byte("existing"), 0o600); err != nil {
		t.Fatal(err)
	}
	stdout.Reset()
	stderr.Reset()
	code = Run([]string{"--json", "job", "export", "nightly", "--output", out}, &stdout, &stderr)
	if code == berror.ExitOK || !strings.Contains(stdout.String(), "refuses to overwrite") {
		t.Fatalf("overwrite should fail: code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	stdout.Reset()
	stderr.Reset()
	code = Run([]string{"--json", "job", "plan", "nightly", "--set", "password=secret"}, &stdout, &stderr)
	if code == berror.ExitOK || !strings.Contains(stdout.String(), "credential fields") {
		t.Fatalf("secret override should fail: code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
}

func TestBR33RepositoryRemovalRefusesDependentsAndScheduleRequiresEnabledJob(t *testing.T) {
	withTempHome(t)
	var stdout, stderr bytes.Buffer
	code := Run([]string{"--json", "repository", "add", "repo1", "--location", filepath.Join(t.TempDir(), "repo")}, &stdout, &stderr)
	if code != berror.ExitOK {
		t.Fatalf("repository add failed: code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	repoRef := "repo1"
	params := `{"sources":["/tmp/a"],"repository":"/tmp/r","verify":false}`
	_ = repoRef
	stdout.Reset()
	stderr.Reset()
	code = Run([]string{"--json", "job", "create", "snap", "--operation", "backup.snapshot", "--repository-ref", "repo1", "--params-json", params, "--disabled"}, &stdout, &stderr)
	if code != berror.ExitOK {
		t.Fatalf("job create failed: code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	stdout.Reset()
	stderr.Reset()
	code = Run([]string{"--json", "schedule", "add", "sched", "--job", "snap", "--every", "60"}, &stdout, &stderr)
	if code == berror.ExitOK || !strings.Contains(stdout.String(), "disabled saved job") {
		t.Fatalf("enabled schedule for disabled job should fail: code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
	stdout.Reset()
	stderr.Reset()
	code = Run([]string{"--json", "repository", "remove", "repo1"}, &stdout, &stderr)
	if code == berror.ExitOK || !strings.Contains(stdout.String(), "still referenced") {
		t.Fatalf("repository remove should see job dependency: code=%d stdout=%s stderr=%s", code, stdout.String(), stderr.String())
	}
}

func TestBR33SavedJobRejectsNonExecutableOperation(t *testing.T) {
	a := leafArgs{Values: map[string][]string{"operation": {"repository.add"}, "params_json": {`{"repository_id":"x"}`}}}
	if _, err := makeOperation(a, nil); err == nil {
		t.Fatal("saved jobs should not store non-executable configuration mutations")
	}
	ok := models.NewOperationSpecV2("backup.create", map[string]any{"sources": []any{"/tmp/a"}, "destination": "/tmp/b"})
	if err := validateExecutableOperation(ok); err != nil {
		t.Fatalf("backup.create should be accepted: %v", err)
	}
}
