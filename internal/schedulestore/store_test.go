package schedulestore

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestScheduleLifecycle(t *testing.T) {
	s := &Store{Path: filepath.Join(t.TempDir(), "schedules.json")}
	v, err := s.Put(Schedule{ScheduleID: "nightly", JobID: "phone", Enabled: true, IntervalMinutes: 60, Network: "unmetered", RequireCharging: true})
	if err != nil {
		t.Fatal(err)
	}
	if v.CreatedAt == "" {
		t.Fatal("created_at missing")
	}
	list, err := s.List()
	if err != nil || len(list) != 1 {
		t.Fatalf("list=%v err=%v", list, err)
	}
	off, err := s.SetEnabled("nightly", false)
	if err != nil || off.Enabled {
		t.Fatalf("disable=%v err=%v", off, err)
	}
	deleted, err := s.Delete("nightly")
	if err != nil || !deleted {
		t.Fatalf("delete=%v err=%v", deleted, err)
	}
}
func TestScheduleRejectsTooFrequent(t *testing.T) {
	s := &Store{Path: filepath.Join(t.TempDir(), "schedules.json")}
	if _, err := s.Put(Schedule{ScheduleID: "fast", JobID: "phone", IntervalMinutes: 5, Network: "any"}); err == nil {
		t.Fatal("expected minimum interval rejection")
	}
}

func TestScheduleDefaultsToNoNetworkConstraint(t *testing.T) {
	s := &Store{Path: filepath.Join(t.TempDir(), "schedules.json")}
	v, err := s.Put(Schedule{ScheduleID: "local", JobID: "phone", Enabled: true, IntervalMinutes: 30})
	if err != nil {
		t.Fatal(err)
	}
	if v.Network != "none" {
		t.Fatalf("network=%q want none", v.Network)
	}
}

func TestScheduleAcceptsAnyNetworkAndRejectsUnknownNetwork(t *testing.T) {
	s := &Store{Path: filepath.Join(t.TempDir(), "schedules.json")}
	if _, err := s.Put(Schedule{ScheduleID: "online", JobID: "phone", Enabled: true, IntervalMinutes: 30, Network: "any"}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Put(Schedule{ScheduleID: "bad", JobID: "phone", Enabled: true, IntervalMinutes: 30, Network: "wifi"}); err == nil {
		t.Fatal("expected invalid network policy rejection")
	}
}

func TestBR27ScheduleStoreRejectsOverflowAndKeepsEmptyArray(t *testing.T) {
	s := &Store{Path: filepath.Join(t.TempDir(), "schedules.json")}
	if _, err := s.Put(Schedule{ScheduleID: "yearly", JobID: "phone", Enabled: true, IntervalMinutes: MaxIntervalMinutes + 1, Network: "none"}); err == nil {
		t.Fatal("expected maximum interval rejection")
	}
	if _, err := s.Put(Schedule{ScheduleID: "keep", JobID: "phone", Enabled: true, IntervalMinutes: 60, Network: "none"}); err != nil {
		t.Fatal(err)
	}
	deleted, err := s.Delete("keep")
	if err != nil || !deleted {
		t.Fatalf("delete=%v err=%v", deleted, err)
	}
	data, err := os.ReadFile(s.Path)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(data), `"schedules": []`) {
		t.Fatalf("expected empty array, got %s", data)
	}
}

func TestBR27ScheduleStoreBulkDisableAndDeleteForJob(t *testing.T) {
	s := &Store{Path: filepath.Join(t.TempDir(), "schedules.json")}
	for _, id := range []string{"a", "b"} {
		if _, err := s.Put(Schedule{ScheduleID: id, JobID: "job", Enabled: true, IntervalMinutes: 60, Network: "none"}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := s.Put(Schedule{ScheduleID: "other", JobID: "other", Enabled: true, IntervalMinutes: 60, Network: "none"}); err != nil {
		t.Fatal(err)
	}
	changed, err := s.SetEnabledForJob("job", false)
	if err != nil || changed != 2 {
		t.Fatalf("changed=%d err=%v", changed, err)
	}
	deleted, err := s.DeleteForJob("job")
	if err != nil || deleted != 2 {
		t.Fatalf("deleted=%d err=%v", deleted, err)
	}
	left, err := s.List()
	if err != nil || len(left) != 1 || left[0].ScheduleID != "other" {
		t.Fatalf("left=%+v err=%v", left, err)
	}
}
