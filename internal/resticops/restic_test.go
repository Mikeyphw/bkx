package resticops

import (
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"bkx/internal/models"
)

type fakeRunner struct {
	calls  [][]string
	envs   [][]string
	stdout []byte
	stderr []byte
	err    error
}

func (f *fakeRunner) Run(args []string, env []string) ([]byte, []byte, error) {
	f.calls = append(f.calls, append([]string(nil), args...))
	f.envs = append(f.envs, append([]string(nil), env...))
	return f.stdout, f.stderr, f.err
}

func TestNormalizeLocalRepositoryRejectsRemote(t *testing.T) {
	for _, raw := range []string{"rclone://drive/repo", "s3:https://example/bucket", "sftp:user@host:/repo"} {
		if _, err := NormalizeLocalRepository(raw); err == nil {
			t.Fatalf("expected local-only rejection for %q", raw)
		}
	}
}

func TestCredentialReferenceResolvesWithoutPuttingSecretInArgs(t *testing.T) {
	ref := "phone-repo"
	env, err := credentialEnvironment([]string{"PATH=/bin", "BKX_CREDENTIAL_PHONE_REPO=super-secret"}, &ref)
	if err != nil {
		t.Fatal(err)
	}
	joined := strings.Join(env, "\n")
	if !strings.Contains(joined, "RESTIC_PASSWORD=super-secret") {
		t.Fatalf("credential not resolved: %q", joined)
	}
}

func TestInitUsesRepoArgumentAndCredentialEnvironment(t *testing.T) {
	root := t.TempDir()
	repo := filepath.Join(root, "repo")
	ref := "local"
	fake := &fakeRunner{}
	got, err := Init(models.RepositoryInitSpec{Engine: models.BackupEngineRestic, Repository: repo, CredentialRef: &ref}, Options{
		Runner:  fake,
		Environ: []string{"BKX_CREDENTIAL_LOCAL=pw"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if !got.Success || got.Repository != repo {
		t.Fatalf("result=%+v", got)
	}
	if len(fake.calls) != 1 || !reflect.DeepEqual(fake.calls[0], []string{"--repo", repo, "init"}) {
		t.Fatalf("calls=%v", fake.calls)
	}
	if strings.Contains(strings.Join(fake.calls[0], " "), "pw") {
		t.Fatal("secret leaked into command args")
	}
}

func TestBackupParsesSnapshotSummary(t *testing.T) {
	source := filepath.Join(t.TempDir(), "source")
	if err := os.MkdirAll(source, 0o755); err != nil {
		t.Fatal(err)
	}
	repo := filepath.Join(t.TempDir(), "repo")
	ref := "local"
	fake := &fakeRunner{stdout: []byte("{\"message_type\":\"status\",\"files_done\":2,\"bytes_done\":100,\"percent_done\":0.5}\n{\"message_type\":\"summary\",\"snapshot_id\":\"abc123\",\"total_files_processed\":4,\"total_bytes_processed\":200,\"data_added\":50}\n")}
	got, err := Backup(models.SnapshotBackupSpec{Engine: models.BackupEngineRestic, Repository: repo, CredentialRef: &ref, Sources: []string{source}, Tags: []string{"phone"}}, Options{
		Runner:  fake,
		Environ: []string{"BKX_CREDENTIAL_LOCAL=pw"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if got.SnapshotID != "abc123" || got.FilesProcessed != 4 || got.BytesProcessed != 200 || got.DataAdded != 50 {
		t.Fatalf("result=%+v", got)
	}
	if len(fake.calls) != 1 || fake.calls[0][0] != "--json" || fake.calls[0][3] != "backup" {
		t.Fatalf("calls=%v", fake.calls)
	}
}

func TestBackupVerificationFailureFailsRun(t *testing.T) {
	source := filepath.Join(t.TempDir(), "source")
	if err := os.MkdirAll(source, 0o755); err != nil {
		t.Fatal(err)
	}
	repo := filepath.Join(t.TempDir(), "repo")
	ref := "local"
	fake := &sequenceRunner{responses: []response{
		{stdout: []byte("{\"message_type\":\"summary\",\"snapshot_id\":\"abc123\",\"total_files_processed\":1,\"total_bytes_processed\":2,\"data_added\":2}\n")},
		{stderr: []byte("repository damaged"), err: errors.New("exit 1")},
	}}
	got, err := Backup(models.SnapshotBackupSpec{Engine: models.BackupEngineRestic, Repository: repo, CredentialRef: &ref, Sources: []string{source}, Verify: true}, Options{Runner: fake, Environ: []string{"BKX_CREDENTIAL_LOCAL=pw"}})
	if err == nil || got.Success {
		t.Fatalf("got=%+v err=%v", got, err)
	}
	if got.VerificationPassed == nil || *got.VerificationPassed {
		t.Fatalf("verification=%v", got.VerificationPassed)
	}
}

type response struct {
	stdout, stderr []byte
	err            error
}
type sequenceRunner struct {
	responses []response
	calls     int
}

func (s *sequenceRunner) Run(args []string, env []string) ([]byte, []byte, error) {
	if s.calls >= len(s.responses) {
		return nil, nil, errors.New("unexpected call")
	}
	r := s.responses[s.calls]
	s.calls++
	return r.stdout, r.stderr, r.err
}
