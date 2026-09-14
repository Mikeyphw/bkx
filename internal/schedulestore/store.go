package schedulestore

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
	"time"

	"bkx/internal/berror"
	"bkx/internal/statepaths"
)

const (
	SchemaVersion      = 1
	MaxIntervalMinutes = 525600
)

type Schedule struct {
	SchemaVersion   int    `json:"schema_version"`
	ScheduleID      string `json:"schedule_id"`
	JobID           string `json:"job_id"`
	Enabled         bool   `json:"enabled"`
	IntervalMinutes int64  `json:"interval_minutes"`
	RequireCharging bool   `json:"require_charging"`
	Network         string `json:"network"`
	CreatedAt       string `json:"created_at"`
	UpdatedAt       string `json:"updated_at"`
	Revision        int64  `json:"revision"`
}

type document struct {
	SchemaVersion int        `json:"schema_version"`
	Schedules     []Schedule `json:"schedules"`
}

type Store struct {
	Path       string
	LegacyPath string
}

func Default(home string) *Store {
	return &Store{Path: statepaths.ScheduleStorePath(home), LegacyPath: statepaths.LegacyScheduleStorePath(home)}
}
func (s *Store) LockPath() string { return s.Path + ".lock" }

func Validate(v Schedule) error {
	if v.SchemaVersion != SchemaVersion {
		return fmt.Errorf("schedule schema_version must be %d", SchemaVersion)
	}
	if !stableID(v.ScheduleID) {
		return fmt.Errorf("invalid schedule_id")
	}
	if !stableID(v.JobID) {
		return fmt.Errorf("invalid job_id")
	}
	if v.IntervalMinutes < 15 {
		return fmt.Errorf("interval_minutes must be at least 15")
	}
	if v.IntervalMinutes > MaxIntervalMinutes {
		return fmt.Errorf("interval_minutes must be at most %d", MaxIntervalMinutes)
	}
	if v.Network != "none" && v.Network != "any" && v.Network != "unmetered" {
		return fmt.Errorf("network must be none, any, or unmetered")
	}
	if _, err := time.Parse(time.RFC3339Nano, v.CreatedAt); err != nil {
		return fmt.Errorf("created_at must be RFC3339")
	}
	if v.Revision < 1 {
		return fmt.Errorf("revision must be positive")
	}
	if _, err := time.Parse(time.RFC3339Nano, v.UpdatedAt); err != nil {
		return fmt.Errorf("updated_at must be RFC3339")
	}
	return nil
}

func (s *Store) List() ([]Schedule, error) {
	var out []Schedule
	err := s.readDocument(func(doc *document) error { out = append([]Schedule(nil), doc.Schedules...); return nil })
	sort.Slice(out, func(i, j int) bool { return out[i].ScheduleID < out[j].ScheduleID })
	return out, err
}
func (s *Store) Get(id string) (*Schedule, error) {
	var out *Schedule
	err := s.readDocument(func(doc *document) error {
		for i := range doc.Schedules {
			if doc.Schedules[i].ScheduleID == id {
				v := doc.Schedules[i]
				out = &v
				break
			}
		}
		return nil
	})
	return out, err
}
func (s *Store) Put(v Schedule) (Schedule, error) {
	return s.PutExpected(v, nil)
}

func (s *Store) PutExpected(v Schedule, expectedRevision *int64) (Schedule, error) {
	now := time.Now().UTC().Format(time.RFC3339Nano)
	v.SchemaVersion = SchemaVersion
	v.ScheduleID = strings.TrimSpace(v.ScheduleID)
	v.JobID = strings.TrimSpace(v.JobID)
	v.Network = strings.ToLower(strings.TrimSpace(v.Network))
	if v.Network == "" {
		v.Network = "none"
	}
	err := s.withDocument(func(doc *document) error {
		found := -1
		for i := range doc.Schedules {
			if doc.Schedules[i].ScheduleID == v.ScheduleID {
				found = i
				break
			}
		}
		if found >= 0 {
			if expectedRevision != nil && doc.Schedules[found].Revision != *expectedRevision {
				return berror.Validation("schedule revision mismatch", map[string]any{"schedule_id": v.ScheduleID, "expected_revision": *expectedRevision, "actual_revision": doc.Schedules[found].Revision})
			}
			v.CreatedAt = doc.Schedules[found].CreatedAt
			v.UpdatedAt = now
			v.Revision = doc.Schedules[found].Revision + 1
		} else {
			if expectedRevision != nil {
				return berror.NotFound("Schedule not found: "+v.ScheduleID, map[string]any{"schedule_id": v.ScheduleID})
			}
			v.CreatedAt = now
			v.UpdatedAt = now
			v.Revision = 1
		}
		if err := Validate(v); err != nil {
			return berror.Validation("invalid saved-job schedule", map[string]any{"reason": err.Error()})
		}
		if found >= 0 {
			doc.Schedules[found] = v
		} else {
			doc.Schedules = append(doc.Schedules, v)
		}
		return nil
	})
	return v, err
}
func (s *Store) SetEnabled(id string, enabled bool) (*Schedule, error) {
	return s.SetEnabledExpected(id, enabled, nil)
}

func (s *Store) SetEnabledExpected(id string, enabled bool, expectedRevision *int64) (*Schedule, error) {
	current, err := s.Get(id)
	if err != nil {
		return nil, err
	}
	if current == nil {
		return nil, berror.NotFound("Schedule not found: "+id, map[string]any{"schedule_id": id})
	}
	current.Enabled = enabled
	v, err := s.PutExpected(*current, expectedRevision)
	return &v, err
}
func (s *Store) Delete(id string) (bool, error) {
	deleted := false
	err := s.withDocument(func(doc *document) error {
		out := doc.Schedules[:0]
		for _, v := range doc.Schedules {
			if v.ScheduleID == id {
				deleted = true
				continue
			}
			out = append(out, v)
		}
		doc.Schedules = out
		if doc.Schedules == nil {
			doc.Schedules = []Schedule{}
		}
		return nil
	})
	return deleted, err
}

func (s *Store) DeleteForJob(jobID string) (int, error) {
	deleted := 0
	err := s.withDocument(func(doc *document) error {
		out := doc.Schedules[:0]
		for _, v := range doc.Schedules {
			if v.JobID == jobID {
				deleted++
				continue
			}
			out = append(out, v)
		}
		doc.Schedules = out
		if doc.Schedules == nil {
			doc.Schedules = []Schedule{}
		}
		return nil
	})
	return deleted, err
}

func (s *Store) SetEnabledForJob(jobID string, enabled bool) (int, error) {
	changed := 0
	now := time.Now().UTC().Format(time.RFC3339Nano)
	err := s.withDocument(func(doc *document) error {
		for i := range doc.Schedules {
			if doc.Schedules[i].JobID == jobID && doc.Schedules[i].Enabled != enabled {
				doc.Schedules[i].Enabled = enabled
				doc.Schedules[i].UpdatedAt = now
				changed++
			}
		}
		return nil
	})
	return changed, err
}

func (s *Store) readDocument(fn func(*document) error) error {
	if err := os.MkdirAll(filepath.Dir(s.Path), 0o700); err != nil {
		return err
	}
	lock, err := os.OpenFile(s.LockPath(), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return err
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_SH); err != nil {
		return err
	}
	defer syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
	if strings.TrimSpace(s.LegacyPath) != "" {
		if err := statepaths.SeedLegacyFile(s.Path, s.LegacyPath); err != nil {
			return berror.WrapExternal("seeding shared schedules from legacy state", s.LegacyPath, "filesystem", err)
		}
	}
	doc := document{SchemaVersion: SchemaVersion, Schedules: []Schedule{}}
	if data, err := os.ReadFile(s.Path); err == nil {
		if err := json.Unmarshal(data, &doc); err != nil {
			return fmt.Errorf("invalid schedule store: %w", err)
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	normalizeLegacyDocument(&doc)
	if err := validateDocument(doc); err != nil {
		return err
	}
	return fn(&doc)
}

func normalizeLegacyDocument(doc *document) {
	if doc.SchemaVersion == 0 {
		doc.SchemaVersion = SchemaVersion
	}
	if doc.Schedules == nil {
		doc.Schedules = []Schedule{}
	}
	for i := range doc.Schedules {
		if doc.Schedules[i].SchemaVersion == 0 {
			doc.Schedules[i].SchemaVersion = SchemaVersion
		}
	}
	stampLegacyScheduleRevisions(doc)
}

func stampLegacyScheduleRevisions(doc *document) {
	for i := range doc.Schedules {
		if doc.Schedules[i].Revision < 1 {
			doc.Schedules[i].Revision = 1
		}
	}
}

func (s *Store) withDocument(fn func(*document) error) error {
	if err := os.MkdirAll(filepath.Dir(s.Path), 0o700); err != nil {
		return err
	}
	lock, err := os.OpenFile(s.LockPath(), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return err
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX); err != nil {
		return err
	}
	defer syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
	if strings.TrimSpace(s.LegacyPath) != "" {
		if err := statepaths.SeedLegacyFile(s.Path, s.LegacyPath); err != nil {
			return berror.WrapExternal("seeding shared schedules from legacy state", s.LegacyPath, "filesystem", err)
		}
	}
	doc := document{SchemaVersion: SchemaVersion, Schedules: []Schedule{}}
	if data, err := os.ReadFile(s.Path); err == nil {
		if err := json.Unmarshal(data, &doc); err != nil {
			return fmt.Errorf("invalid schedule store: %w", err)
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	normalizeLegacyDocument(&doc)
	if err := fn(&doc); err != nil {
		return err
	}
	if doc.Schedules == nil {
		doc.Schedules = []Schedule{}
	}
	if err := validateDocument(doc); err != nil {
		return err
	}
	data, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	return statepaths.AtomicWriteFile(s.Path, data, 0o600)
}

func validateDocument(doc document) error {
	if doc.SchemaVersion != SchemaVersion {
		return fmt.Errorf("unsupported schedule schema %d", doc.SchemaVersion)
	}
	seen := map[string]bool{}
	if doc.Schedules == nil {
		doc.Schedules = []Schedule{}
	}
	for _, schedule := range doc.Schedules {
		if seen[schedule.ScheduleID] {
			return fmt.Errorf("duplicate schedule_id: %s", schedule.ScheduleID)
		}
		seen[schedule.ScheduleID] = true
		if err := Validate(schedule); err != nil {
			return fmt.Errorf("invalid schedule %s: %w", schedule.ScheduleID, err)
		}
	}
	return nil
}

func stableID(v string) bool {
	v = strings.TrimSpace(v)
	if v == "" || len(v) > 128 {
		return false
	}
	for i, r := range v {
		if i == 0 && !((r >= 'a' && r <= 'z') || (r >= '0' && r <= '9')) {
			return false
		}
		if !((r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '.' || r == '_' || r == '-') {
			return false
		}
	}
	return true
}
