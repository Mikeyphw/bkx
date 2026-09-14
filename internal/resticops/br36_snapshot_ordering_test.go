package resticops

import (
	"strings"
	"testing"
)

func TestBR36LatestSelectorsUseAbsoluteTimeAcrossRFC3339Offsets(t *testing.T) {
	access, env := localAccess(t)
	catalog := []byte(`[
        {"id":"local-date-later","time":"2026-01-02T00:30:00+02:00","paths":["/older"]},
        {"id":"utc-actually-newer","time":"2026-01-01T23:00:00Z","paths":["/newer"]}
    ]`)

	latest, err := ResolveSnapshot(access, "latest", Options{Runner: &fakeRunner{stdout: catalog}, Environ: env})
	if err != nil {
		t.Fatal(err)
	}
	if latest.ID != "utc-actually-newer" {
		t.Fatalf("latest=%+v", latest)
	}

	previous, err := ResolveSnapshot(access, "latest~1", Options{Runner: &fakeRunner{stdout: catalog}, Environ: env})
	if err != nil {
		t.Fatal(err)
	}
	if previous.ID != "local-date-later" {
		t.Fatalf("latest~1=%+v", previous)
	}
}

func TestBR36SnapshotOrderingUsesIDAsDeterministicEqualInstantTieBreak(t *testing.T) {
	access, env := localAccess(t)
	catalog := []byte(`[
        {"id":"aaaa-equal","time":"2026-01-01T23:00:00Z","paths":["/a"]},
        {"id":"zzzz-equal","time":"2026-01-02T00:00:00+01:00","paths":["/z"]}
    ]`)
	got, err := ListSnapshots(access, SnapshotListOptions{}, Options{Runner: &fakeRunner{stdout: catalog}, Environ: env})
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Snapshots) != 2 || got.Snapshots[0].ID != "zzzz-equal" || got.Snapshots[1].ID != "aaaa-equal" {
		t.Fatalf("snapshots=%+v", got.Snapshots)
	}
}

func TestBR36SnapshotCatalogRejectsMalformedTimestampInsteadOfLexicalFallback(t *testing.T) {
	access, env := localAccess(t)
	catalog := []byte(`[
        {"id":"valid","time":"2026-01-01T23:00:00Z","paths":["/valid"]},
        {"id":"invalid","time":"2026-99-99 nope","paths":["/invalid"]}
    ]`)
	_, err := ListSnapshots(access, SnapshotListOptions{}, Options{Runner: &fakeRunner{stdout: catalog}, Environ: env})
	if err == nil {
		t.Fatal("expected malformed timestamp rejection")
	}
	if !strings.Contains(strings.ToLower(err.Error()), "invalid timestamp") {
		t.Fatalf("err=%v", err)
	}
}
