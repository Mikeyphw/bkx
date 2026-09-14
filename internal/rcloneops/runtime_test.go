package rcloneops

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func fakeRuntime(t *testing.T) (Runtime, string) {
	t.Helper()
	root := t.TempDir()
	logPath := filepath.Join(root, "args.log")
	script := filepath.Join(root, "rclone")
	body := `#!/bin/sh
set -eu
printf '%s\n' "$*" >> "$RCLONE_TEST_LOG"
case "$1" in
  version) printf '%s\n' 'rclone vtest' ;;
  size) printf '{"count":2,"bytes":12}\n' ;;
  lsjson)
    case "$*" in
      *--stat*) printf '{"Path":"source","Name":"source","Size":12,"ModTime":"2026-01-01T00:00:00Z","IsDir":true}\n' ;;
      *) printf '[{"Path":"folder","Name":"folder","Size":-1,"ModTime":"2026-01-01T00:00:00Z","IsDir":true},{"Path":"file.txt","Name":"file.txt","Size":5,"ModTime":"2026-01-01T00:00:00Z","IsDir":false}]\n' ;;
    esac ;;
  copy|copyto|sync) printf '%s\n' '{"stats":{"bytes":12,"transfers":2,"totalBytes":12,"totalTransfers":2}}' >&2 ;;
  check) ;;
  *) echo "unexpected command: $1" >&2; exit 3 ;;
esac
`
	if err := os.WriteFile(script, []byte(body), 0o755); err != nil {
		t.Fatal(err)
	}
	config := filepath.Join(root, "rclone.conf")
	if err := os.WriteFile(config, []byte("[drive]\ntype = drive\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("BKX_RCLONE_PATH", script)
	t.Setenv("BKX_RCLONE_CONFIG", config)
	t.Setenv("RCLONE_TEST_LOG", logPath)
	runtime, err := RuntimeFromEnvironment()
	if err != nil {
		t.Fatal(err)
	}
	return runtime, logPath
}

func TestRuntimeAvailabilityProbeAndConfiguredState(t *testing.T) {
	runtime, _ := fakeRuntime(t)
	if !Available() || !Configured() || runtime.Executable == "" {
		t.Fatal("expected configured fake runtime")
	}
	info, err := runtime.Probe(context.Background())
	if err != nil || info.Version != "vtest" {
		t.Fatalf("info=%+v err=%v", info, err)
	}
}

func TestListAndStatReturnStructuredEntries(t *testing.T) {
	runtime, _ := fakeRuntime(t)
	entries, err := runtime.List(context.Background(), "rclone://drive/root")
	if err != nil || len(entries) != 2 || !entries[0].IsDir || entries[1].Name != "file.txt" || entries[1].Size != 5 {
		t.Fatalf("entries=%+v err=%v", entries, err)
	}
	stat, err := runtime.Stat(context.Background(), "rclone://drive/source")
	if err != nil || !stat.IsDir || stat.Name != "source" {
		t.Fatalf("stat=%+v err=%v", stat, err)
	}
}

func TestTransferUsesPinnedConfigProgressVerificationAndDirectorySemantics(t *testing.T) {
	runtime, logPath := fakeRuntime(t)
	var progress []Progress
	result, err := runtime.Transfer(context.Background(), "push", []string{"rclone://drive/source"}, t.TempDir(), TransferOptions{
		Verify:   true,
		Progress: func(p Progress) { progress = append(progress, p) },
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.Files != 2 || result.Bytes != 12 || len(progress) < 2 {
		t.Fatalf("result=%+v progress=%+v", result, progress)
	}
	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatal(err)
	}
	log := string(data)
	for _, token := range []string{"size drive:source --json", "lsjson drive:source --stat", "copy drive:source", "--ignore-existing", "--use-json-log", "check drive:source", "--one-way", "--download", "--config"} {
		if !strings.Contains(log, token) {
			t.Fatalf("missing %q in log:\n%s", token, log)
		}
	}
}

func TestDiagnosticSanitizationRedactsCommonSecretShapesAndConfigPath(t *testing.T) {
	runtime, _ := fakeRuntime(t)
	input := `failed config=` + runtime.Config + ` token=abc password = hidden client_secret:"json-secret" refresh_token:rv123 access_key_id=id123 secret_access_key=sk123`
	got := runtime.sanitizeDiagnostic(input)
	for _, secret := range []string{runtime.Config, "abc", "hidden", "json-secret", "rv123", "id123", "sk123"} {
		if strings.Contains(got, secret) {
			t.Fatalf("diagnostic leaked %q: %s", secret, got)
		}
	}
	if !strings.Contains(got, "<rclone-config>") || strings.Count(got, "<redacted>") < 6 {
		t.Fatalf("sanitization evidence missing: %s", got)
	}
}

func TestConfiguredRemoteMustExistBeforeRemoteOperations(t *testing.T) {
	runtime, _ := fakeRuntime(t)
	missingConfig := filepath.Join(t.TempDir(), "rclone.conf")
	if err := os.WriteFile(missingConfig, []byte("[box]\ntype = box\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	runtime.Config = missingConfig

	_, err := runtime.List(context.Background(), "rclone://drive/root")
	if err == nil || !strings.Contains(err.Error(), "remote \"drive\" is not configured") {
		t.Fatalf("expected configured-remote failure, got %v", err)
	}
}

func TestConfiguredRemoteNamesIgnoreUnsafeSections(t *testing.T) {
	root := t.TempDir()
	config := filepath.Join(root, "rclone.conf")
	if err := os.WriteFile(config, []byte("[drive]\ntype = drive\n[bad/remote]\ntype = drive\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	runtime := Runtime{Executable: filepath.Join(root, "rclone"), Config: config}
	names, err := runtime.ConfiguredRemoteNames()
	if err != nil {
		t.Fatal(err)
	}
	if !names["drive"] || names["bad/remote"] {
		t.Fatalf("names=%v", names)
	}
}

func TestBR34ConfiguredRequiresAUsableRemoteSection(t *testing.T) {
	runtime, _ := fakeRuntime(t)
	empty := filepath.Join(t.TempDir(), "rclone.conf")
	if err := os.WriteFile(empty, []byte("# comments only\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	runtime.Config = empty
	if runtime.Configured() {
		t.Fatal("comments-only config must not be treated as configured")
	}
	if err := os.WriteFile(empty, []byte("[drive]\ntype = drive\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if !runtime.Configured() {
		t.Fatal("remote section should be configured")
	}
}
