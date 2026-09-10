package resticops

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"bkx/internal/models"
)

func localAccess(t *testing.T) (models.RepositoryAccessSpec, []string) {
	t.Helper()
	repo := filepath.Join(t.TempDir(), "repo")
	ref := "repo"
	return models.RepositoryAccessSpec{Repository: repo, CredentialRef: &ref}, []string{"BKX_CREDENTIAL_REPO=pw"}
}

func snapshotJSON() []byte {
	return []byte(`[{"time":"2026-09-10T09:00:00Z","paths":["/home/u/Documents"],"hostname":"phone","username":"u","tags":["daily"],"id":"aaaaaaaa11111111111111111111111111111111111111111111111111111111"},{"time":"2026-09-09T09:00:00Z","paths":["/home/u/Documents"],"hostname":"phone","username":"u","id":"bbbbbbbb22222222222222222222222222222222222222222222222222222222"}]`)
}

func TestListAndResolveSnapshots(t *testing.T) {
	access, env := localAccess(t)
	fake := &fakeRunner{stdout: snapshotJSON()}
	got, err := ListSnapshots(access, SnapshotListOptions{Host: "phone", Tags: []string{"daily"}}, Options{Runner: fake, Environ: env})
	if err != nil {
		t.Fatal(err)
	}
	if got.Total != 2 || got.Snapshots[0].ShortID != "aaaaaaaa" {
		t.Fatalf("got=%+v", got)
	}
	args := strings.Join(fake.calls[0], " ")
	if !strings.Contains(args, "snapshots --host phone --tag daily") {
		t.Fatalf("args=%v", fake.calls[0])
	}

	fake2 := &fakeRunner{stdout: snapshotJSON()}
	latest, err := ResolveSnapshot(access, "latest", Options{Runner: fake2, Environ: env})
	if err != nil || !strings.HasPrefix(latest.ID, "aaaaaaaa") {
		t.Fatalf("latest=%+v err=%v", latest, err)
	}
}

func TestRestoreDefaultsToNeverOverwriteAndSupportsDryRun(t *testing.T) {
	access, env := localAccess(t)
	target := filepath.Join(t.TempDir(), "restore")
	seq := &sequenceRunner{responses: []response{{stdout: snapshotJSON()}, {}}}
	got, err := RestoreSnapshot(access, "aaaaaaaa", RestoreOptions{Target: target, Includes: []string{"/Documents"}, DryRun: true}, Options{Runner: seq, Environ: env})
	if err != nil {
		t.Fatal(err)
	}
	if got.Overwrite != "never" || !got.DryRun {
		t.Fatalf("got=%+v", got)
	}
}

func TestForgetRejectsLatestAndNeverPrunes(t *testing.T) {
	access, env := localAccess(t)
	if _, err := ForgetSnapshot(access, "latest", Options{Runner: &fakeRunner{}, Environ: env}); err == nil {
		t.Fatal("expected latest rejection")
	}
	seq := &sequenceRunner{responses: []response{{stdout: snapshotJSON()}, {}}}
	got, err := ForgetSnapshot(access, "bbbbbbbb", Options{Runner: seq, Environ: env})
	if err != nil {
		t.Fatal(err)
	}
	if got.Operation != "forget" {
		t.Fatalf("got=%+v", got)
	}
}

func TestRetentionPolicyNeverAddsPrune(t *testing.T) {
	access, env := localAccess(t)
	n7, n4, n12 := 7, 4, 12
	fake := &fakeRunner{}
	got, err := ApplyRetention(access, models.RetentionPolicy{SchemaVersion: 1, KeepDaily: &n7, KeepWeekly: &n4, KeepMonthly: &n12}, true, Options{Runner: fake, Environ: env})
	if err != nil {
		t.Fatal(err)
	}
	if !got.DryRun {
		t.Fatalf("got=%+v", got)
	}
	joined := strings.Join(fake.calls[0], " ")
	for _, want := range []string{"forget", "--keep-daily 7", "--keep-weekly 4", "--keep-monthly 12", "--dry-run"} {
		if !strings.Contains(joined, want) {
			t.Fatalf("missing %q in %s", want, joined)
		}
	}
	if strings.Contains(joined, "--prune") {
		t.Fatalf("retention must not implicitly prune: %s", joined)
	}
}

func TestPruneIsExplicitAndSupportsLimits(t *testing.T) {
	access, env := localAccess(t)
	fake := &fakeRunner{}
	_, err := PruneRepository(access, PruneOptions{DryRun: true, MaxUnused: "10%", MaxRepackSize: "1G"}, Options{Runner: fake, Environ: env})
	if err != nil {
		t.Fatal(err)
	}
	joined := strings.Join(fake.calls[0], " ")
	for _, want := range []string{"prune", "--dry-run", "--max-unused 10%", "--max-repack-size 1G"} {
		if !strings.Contains(joined, want) {
			t.Fatalf("missing %q in %s", want, joined)
		}
	}
}

func TestDiffParsesJSONLines(t *testing.T) {
	access, env := localAccess(t)
	diff := []byte("{\"message_type\":\"change\",\"path\":\"/a\",\"modifier\":\"M\"}\n{\"message_type\":\"statistics\",\"source_snapshot\":\"aaaaaaaa\",\"target_snapshot\":\"bbbbbbbb\",\"changed_files\":1,\"added\":{\"files\":0,\"dirs\":0,\"others\":0,\"data_blobs\":1,\"tree_blobs\":0,\"bytes\":10},\"removed\":{\"files\":0,\"dirs\":0,\"others\":0,\"data_blobs\":1,\"tree_blobs\":0,\"bytes\":9}}\n")
	seq := &sequenceRunner{responses: []response{{stdout: snapshotJSON()}, {stdout: snapshotJSON()}, {stdout: diff}}}
	got, err := DiffSnapshots(access, "aaaaaaaa", "bbbbbbbb", true, Options{Runner: seq, Environ: env})
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Changes) != 1 || got.Changes[0].Modifier != "M" || got.Statistics.ChangedFiles != 1 {
		t.Fatalf("got=%+v", got)
	}
}

func TestRestoreTargetIsCanonical(t *testing.T) {
	access, env := localAccess(t)
	dir := t.TempDir()
	old, _ := os.Getwd()
	defer os.Chdir(old)
	_ = os.Chdir(dir)
	seq := &sequenceRunner{responses: []response{{stdout: snapshotJSON()}, {}}}
	got, err := RestoreSnapshot(access, "aaaaaaaa", RestoreOptions{Target: "out"}, Options{Runner: seq, Environ: env})
	if err != nil {
		t.Fatal(err)
	}
	if !filepath.IsAbs(got.Target) {
		t.Fatalf("target=%q", got.Target)
	}
}
