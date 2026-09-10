package backupops

import (
	"context"
	"math/rand"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"bkx/internal/rcloneops"
)

func installBackupRemoteFake(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	remoteRoot := filepath.Join(root, "remote")
	if err := os.MkdirAll(remoteRoot, 0o755); err != nil {
		t.Fatal(err)
	}
	script := filepath.Join(root, "rclone")
	body := `#!/bin/sh
set -eu
remote_root="$RCLONE_TEST_REMOTE_ROOT"
map_path() {
  case "$1" in
    drive:*) printf '%s/%s' "$remote_root" "${1#drive:}" ;;
    *) printf '%s' "$1" ;;
  esac
}
size_of() { wc -c < "$1" | tr -d ' '; }
cmd="$1"; shift
case "$cmd" in
  version)
    printf '%s\n' 'rclone vtest'
    ;;
  lsjson)
    loc="$1"; shift
    mapped="$(map_path "$loc")"
    case " $* " in
      *" --stat "*)
        if [ ! -e "$mapped" ]; then echo 'object not found' >&2; exit 1; fi
        name="$(basename "$mapped")"
        if [ -d "$mapped" ]; then
          printf '{"Path":"%s","Name":"%s","Size":0,"ModTime":"2026-01-01T00:00:00Z","IsDir":true}\n' "$name" "$name"
        else
          n="$(size_of "$mapped")"
          printf '{"Path":"%s","Name":"%s","Size":%s,"ModTime":"2026-01-01T00:00:00Z","IsDir":false}\n' "$name" "$name" "$n"
        fi
        ;;
      *)
        if [ ! -d "$mapped" ]; then echo 'directory not found' >&2; exit 1; fi
        first=1; printf '['
        for f in "$mapped"/* "$mapped"/.*; do
          [ -e "$f" ] || continue
          b="$(basename "$f")"
          [ "$b" = '.' ] && continue; [ "$b" = '..' ] && continue
          [ $first -eq 1 ] || printf ','
          first=0
          if [ -d "$f" ]; then
            printf '{"Path":"%s","Name":"%s","Size":0,"ModTime":"2026-01-01T00:00:00Z","IsDir":true}' "$b" "$b"
          else
            n="$(size_of "$f")"
            printf '{"Path":"%s","Name":"%s","Size":%s,"ModTime":"2026-01-01T00:00:00Z","IsDir":false}' "$b" "$b" "$n"
          fi
        done
        printf ']\n'
        ;;
    esac
    ;;
  copyto)
    src="$1"; dst="$2"; shift 2
    srcp="$(map_path "$src")"; dstp="$(map_path "$dst")"
    mkdir -p "$(dirname "$dstp")"
    case " $* " in *" --immutable "*) [ ! -e "$dstp" ] || { echo 'immutable destination exists' >&2; exit 1; } ;; esac
    if [ "${RCLONE_TEST_FAIL_PART003:-0}" = 1 ] && case "${dst#drive:}" in *.part003) true;; *) false;; esac; then
      echo 'injected part003 upload failure' >&2
      exit 17
    fi
    cp "$srcp" "$dstp"
    if [ "${RCLONE_TEST_CORRUPT_UPLOAD:-0}" = 1 ] && [ "${dst#drive:}" = "BKX/cloud.bakery" ]; then
      # Same-length corruption so the per-copy fake check/stat pass; BKX's final
      # archive/hash verification must still catch it and roll everything back.
      printf 'X' | dd of="$dstp" bs=1 seek=0 conv=notrunc status=none
    fi
    n="$(size_of "$dstp")"
    printf '{"stats":{"bytes":%s,"transfers":1,"totalBytes":%s,"totalTransfers":1}}\n' "$n" "$n" >&2
    ;;
  check)
    # Deliberately only acknowledges the transport command. BKX performs its own
    # final logical archive verification after remote publication.
    ;;
  deletefile)
    p="$(map_path "$1")"; rm -f "$p"
    ;;
  size)
    p="$(map_path "$1")"
    if [ -f "$p" ]; then n="$(size_of "$p")"; printf '{"count":1,"bytes":%s}\n' "$n"; else
      count=0; bytes=0
      if [ -d "$p" ]; then
        for f in "$p"/*; do [ -f "$f" ] || continue; count=$((count+1)); n="$(size_of "$f")"; bytes=$((bytes+n)); done
      fi
      printf '{"count":%s,"bytes":%s}\n' "$count" "$bytes"
    fi
    ;;
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
	t.Setenv("RCLONE_TEST_REMOTE_ROOT", remoteRoot)
	return remoteRoot
}

func TestCreateRemotePublishesVerifiesAndCanBeDownloaded(t *testing.T) {
	remoteRoot := installBackupRemoteFake(t)
	source := fixture(t)
	name := "cloud"
	destination := "rclone://drive/BKX"
	spec := backupSpec(source, &destination)
	spec.ArchiveName = &name

	result, err := Create(spec, false, Options{})
	if err != nil {
		t.Fatal(err)
	}
	if !result.Success || len(result.ArchivePaths) != 1 || result.ArchivePaths[0] != "rclone://drive/BKX/cloud.bakery" {
		t.Fatalf("result=%+v", result)
	}
	if !result.StepResults["transfer"] || !result.StepResults["verify_transfer"] {
		t.Fatalf("step results=%v", result.StepResults)
	}
	remoteFile := filepath.Join(remoteRoot, "BKX", "cloud.bakery")
	if _, err := os.Stat(remoteFile); err != nil {
		t.Fatal(err)
	}

	runtime, err := rcloneops.RuntimeFromEnvironment()
	if err != nil {
		t.Fatal(err)
	}
	local := filepath.Join(t.TempDir(), "download.bakery")
	if _, err := runtime.CopyTo(context.Background(), result.ArchivePaths[0], local, rcloneops.TransferOptions{Overwrite: true}); err != nil {
		t.Fatal(err)
	}
	if st, err := os.Stat(local); err != nil || st.Size() == 0 {
		t.Fatalf("download stat=%v err=%v", st, err)
	}
}

func TestCreateRemotePostUploadVerificationFailureRollsBackPublishedObjects(t *testing.T) {
	remoteRoot := installBackupRemoteFake(t)
	t.Setenv("RCLONE_TEST_CORRUPT_UPLOAD", "1")
	source := fixture(t)
	name := "cloud"
	destination := "rclone://drive/BKX"
	spec := backupSpec(source, &destination)
	spec.ArchiveName = &name

	_, err := Create(spec, false, Options{})
	if err == nil {
		t.Fatal("corrupted remote backup unexpectedly succeeded")
	}
	if _, statErr := os.Stat(filepath.Join(remoteRoot, "BKX", "cloud.bakery")); !os.IsNotExist(statErr) {
		t.Fatalf("failed remote publication survived rollback: %v", statErr)
	}
}

func TestPlanRemoteUsesCredentialFreeRcloneURI(t *testing.T) {
	source := fixture(t)
	destination := "rclone://drive/BKX"
	name := "cloud"
	spec := backupSpec(source, &destination)
	spec.ArchiveName = &name
	plan, err := PlanBackup(spec)
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.Steps) < 2 || plan.Steps[0].Destination == nil || *plan.Steps[0].Destination != "rclone://drive/BKX/cloud.bakery" {
		t.Fatalf("plan=%+v", plan)
	}
	for _, step := range plan.Steps {
		if strings.Contains(strings.ToLower(step.Description), "token") || strings.Contains(strings.ToLower(step.Description), "password") {
			t.Fatalf("plan leaked secret-shaped text: %+v", step)
		}
	}
}

func TestCreateRemoteRefusesExistingLogicalArchiveWithoutChangingIt(t *testing.T) {
	remoteRoot := installBackupRemoteFake(t)
	if err := os.MkdirAll(filepath.Join(remoteRoot, "BKX"), 0o755); err != nil {
		t.Fatal(err)
	}
	existing := filepath.Join(remoteRoot, "BKX", "cloud.bakery")
	if err := os.WriteFile(existing, []byte("keep-existing"), 0o600); err != nil {
		t.Fatal(err)
	}
	source := fixture(t)
	name := "cloud"
	destination := "rclone://drive/BKX"
	spec := backupSpec(source, &destination)
	spec.ArchiveName = &name

	if _, err := Create(spec, false, Options{}); err == nil {
		t.Fatal("remote collision unexpectedly succeeded")
	}
	data, err := os.ReadFile(existing)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "keep-existing" {
		t.Fatalf("existing remote archive changed: %q", data)
	}
}

func TestCreateRemoteSplitPublishesSidecarsAndFailureRollsBackEarlierParts(t *testing.T) {
	remoteRoot := installBackupRemoteFake(t)
	source := fixture(t)
	blob := make([]byte, 128*1024)
	// A seeded PRNG keeps this fixture reproducible and incompressible enough
	// to exercise multipart publication with the real zstd codec.
	_, _ = rand.New(rand.NewSource(19)).Read(blob)
	if err := os.WriteFile(filepath.Join(source, "blob.bin"), blob, 0o600); err != nil {
		t.Fatal(err)
	}
	name := "cloud"
	destination := "rclone://drive/BKX"
	split := int64(1024)
	spec := backupSpec(source, &destination)
	spec.ArchiveName = &name
	spec.SplitSize = &split
	t.Setenv("RCLONE_TEST_FAIL_PART003", "1")

	if _, err := Create(spec, false, Options{}); err == nil {
		t.Fatal("injected split upload failure unexpectedly succeeded")
	}
	matches, err := filepath.Glob(filepath.Join(remoteRoot, "BKX", "cloud.bakery*"))
	if err != nil {
		t.Fatal(err)
	}
	if len(matches) != 0 {
		t.Fatalf("split upload rollback left remote parts: %v", matches)
	}
}

func TestCreateRemoteSplitPublishesBaseLastAndVerifiesLogicalArchive(t *testing.T) {
	remoteRoot := installBackupRemoteFake(t)
	source := fixture(t)
	blob := make([]byte, 128*1024)
	// A seeded PRNG keeps this fixture reproducible and incompressible enough
	// to exercise multipart publication with the real zstd codec.
	_, _ = rand.New(rand.NewSource(19)).Read(blob)
	if err := os.WriteFile(filepath.Join(source, "blob.bin"), blob, 0o600); err != nil {
		t.Fatal(err)
	}
	name := "cloud"
	destination := "rclone://drive/BKX"
	split := int64(1024)
	spec := backupSpec(source, &destination)
	spec.ArchiveName = &name
	spec.SplitSize = &split

	result, err := Create(spec, false, Options{})
	if err != nil {
		t.Fatal(err)
	}
	if !result.Success || result.VerificationPassed == nil || !*result.VerificationPassed {
		t.Fatalf("result=%+v", result)
	}
	matches, err := filepath.Glob(filepath.Join(remoteRoot, "BKX", "cloud.bakery*"))
	if err != nil {
		t.Fatal(err)
	}
	if len(matches) < 3 {
		t.Fatalf("expected multipart remote archive, got %v", matches)
	}
	if _, err := os.Stat(filepath.Join(remoteRoot, "BKX", "cloud.bakery")); err != nil {
		t.Fatal(err)
	}
}
