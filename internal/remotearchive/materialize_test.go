package remotearchive

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func installRemoteArchiveFake(t *testing.T, files map[string][]byte) string {
	t.Helper()
	root := t.TempDir()
	remoteRoot := filepath.Join(root, "remote", "BKX")
	if err := os.MkdirAll(remoteRoot, 0o755); err != nil {
		t.Fatal(err)
	}
	for name, data := range files {
		if err := os.WriteFile(filepath.Join(remoteRoot, name), data, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	script := filepath.Join(root, "rclone")
	body := `#!/bin/sh
set -eu
remote_root="$RCLONE_TEST_REMOTE_ROOT"
map_path() { case "$1" in drive:*) printf '%s/%s' "$remote_root" "${1#drive:}" ;; *) printf '%s' "$1" ;; esac; }
size_of() { wc -c < "$1" | tr -d ' '; }
cmd="$1"; shift
case "$cmd" in
  lsjson)
    loc="$1"; shift; p="$(map_path "$loc")"
    case " $* " in
      *" --stat "*)
        [ -e "$p" ] || { echo 'object not found' >&2; exit 1; }
        b="$(basename "$p")"; n="$(size_of "$p")"
        printf '{"Path":"%s","Name":"%s","Size":%s,"ModTime":"2026-01-01T00:00:00Z","IsDir":false}\n' "$b" "$b" "$n"
        ;;
      *)
        [ -d "$p" ] || { echo 'directory not found' >&2; exit 1; }
        first=1; printf '['
        for f in "$p"/*; do
          [ -f "$f" ] || continue; b="$(basename "$f")"; n="$(size_of "$f")"
          [ $first -eq 1 ] || printf ','; first=0
          printf '{"Path":"%s","Name":"%s","Size":%s,"ModTime":"2026-01-01T00:00:00Z","IsDir":false}' "$b" "$b" "$n"
        done
        printf ']\n'
        ;;
    esac
    ;;
  copyto)
    src="$1"; dst="$2"; srcp="$(map_path "$src")"; dstp="$(map_path "$dst")"
    mkdir -p "$(dirname "$dstp")"; cp "$srcp" "$dstp"; n="$(size_of "$dstp")"
    printf '{"stats":{"bytes":%s,"transfers":1,"totalBytes":%s,"totalTransfers":1}}\n' "$n" "$n" >&2
    ;;
  check) ;;
  *) echo "unexpected command: $cmd" >&2; exit 3 ;;
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
	t.Setenv("RCLONE_TEST_REMOTE_ROOT", filepath.Join(root, "remote"))
	return remoteRoot
}

func TestAcquireDownloadsContiguousSplitArchiveAndCleansUp(t *testing.T) {
	installRemoteArchiveFake(t, map[string][]byte{
		"sample.bakery":         []byte("part-one"),
		"sample.bakery.part002": []byte("part-two"),
		"sample.bakery.part003": []byte("part-three"),
	})
	var progress []Progress
	got, err := Acquire(context.Background(), "rclone://drive/BKX/sample.bakery", Options{Progress: func(p Progress) { progress = append(progress, p) }})
	if err != nil {
		t.Fatal(err)
	}
	if !got.Remote || got.PartCount != 3 || got.Path == "" {
		t.Fatalf("materialized=%+v", got)
	}
	for _, suffix := range []string{"", ".part002", ".part003"} {
		if _, err := os.Stat(got.Path + suffix); err != nil {
			t.Fatalf("missing materialized part %s: %v", suffix, err)
		}
	}
	if len(progress) < 4 || progress[len(progress)-1].FilesDone != 3 {
		t.Fatalf("progress=%+v", progress)
	}
	dir := filepath.Dir(got.Path)
	if err := got.Cleanup(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(dir); !os.IsNotExist(err) {
		t.Fatalf("cleanup left staging dir: %v", err)
	}
}

func TestAcquireRejectsMissingMiddleSplitPart(t *testing.T) {
	installRemoteArchiveFake(t, map[string][]byte{
		"sample.bakery":         []byte("part-one"),
		"sample.bakery.part003": []byte("part-three"),
	})
	_, err := Acquire(context.Background(), "rclone://drive/BKX/sample.bakery", Options{})
	if err == nil || !strings.Contains(err.Error(), "missing part 002") {
		t.Fatalf("err=%v", err)
	}
}

func TestAcquireCancellationRemovesStaging(t *testing.T) {
	installRemoteArchiveFake(t, map[string][]byte{"sample.bakery": []byte("payload")})
	_, err := Acquire(context.Background(), "rclone://drive/BKX/sample.bakery", Options{Cancelled: func() bool { return true }})
	if err == nil {
		t.Fatal("cancelled acquire unexpectedly succeeded")
	}
}
