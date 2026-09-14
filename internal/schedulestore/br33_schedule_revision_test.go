package schedulestore

import (
	"os"
	"path/filepath"
	"testing"
)

func TestBR33ScheduleReadDoesNotRewriteAndRevisionCAS(t *testing.T) {
	path := filepath.Join(t.TempDir(), "schedules.json")
	store := &Store{Path: path}
	created, err := store.Put(Schedule{ScheduleID: "nightly", JobID: "job1", Enabled: true, IntervalMinutes: 60, Network: "none"})
	if err != nil {
		t.Fatal(err)
	}
	if created.Revision != 1 {
		t.Fatalf("revision=%d", created.Revision)
	}
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Get("nightly"); err != nil {
		t.Fatal(err)
	}
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(before) != string(after) {
		t.Fatal("schedule read path rewrote the persistent document")
	}
	wrong := int64(99)
	if _, err := store.PutExpected(Schedule{ScheduleID: "nightly", JobID: "job1", Enabled: true, IntervalMinutes: 120, Network: "none"}, &wrong); err == nil {
		t.Fatal("expected stale schedule revision to fail")
	}
	updated, err := store.PutExpected(Schedule{ScheduleID: "nightly", JobID: "job1", Enabled: false, IntervalMinutes: 120, Network: "none"}, &created.Revision)
	if err != nil {
		t.Fatal(err)
	}
	if updated.Revision != 2 || updated.Enabled {
		t.Fatalf("updated=%+v", updated)
	}
}
