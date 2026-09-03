package runstore

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
	"time"

	"bkx/internal/berror"
	"bkx/internal/models"
)

var validStatuses = map[string]bool{"started": true, "success": true, "failed": true, "interrupted": true}
var validOperations = map[string]bool{"backup": true, "archive": true, "transfer": true, "restore": true, "run": true, "unknown": true}

type Store struct {
	Path           string
	MaxRecords     int
	RecoverStarted bool
}

func New(path string, maxRecords int, recoverStarted bool) (*Store, error) {
	if maxRecords < 0 || maxRecords == 0 && false {
		return nil, berror.Validation("max_records must be greater than zero", map[string]any{"max_records": maxRecords})
	}
	s := &Store{Path: path, MaxRecords: maxRecords, RecoverStarted: recoverStarted}
	if recoverStarted {
		if _, err := s.RecoverInterrupted(); err != nil {
			return nil, err
		}
	}
	return s, nil
}

func DefaultPath(home string) string {
	return filepath.Join(home, ".local", "share", "bkx", "runs", "runs.jsonl")
}
func (s *Store) LockPath() string       { return s.Path + ".lock" }
func (s *Store) QuarantinePath() string { return s.Path + ".corrupt" }

func (s *Store) withLock(fn func() error) error {
	if err := os.MkdirAll(filepath.Dir(s.Path), 0o755); err != nil {
		return berror.WrapExternal("creating run-history directory", filepath.Dir(s.Path), "filesystem", err)
	}
	lock, err := os.OpenFile(s.LockPath(), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return berror.WrapExternal("opening run-history lock", s.LockPath(), "filesystem", err)
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX); err != nil {
		return berror.WrapExternal("locking run history", s.LockPath(), "filesystem", err)
	}
	defer syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
	return fn()
}

func (s *Store) Load() ([]models.RunRecord, error) {
	var out []models.RunRecord
	err := s.withLock(func() error { var err error; out, err = s.loadUnlocked(true); return err })
	return out, err
}

func (s *Store) loadUnlocked(clean bool) ([]models.RunRecord, error) {
	f, err := os.Open(s.Path)
	if err != nil {
		if os.IsNotExist(err) {
			return []models.RunRecord{}, nil
		}
		return nil, berror.WrapExternal("reading run history", s.Path, "filesystem", err)
	}
	defer f.Close()
	var records []models.RunRecord
	var malformed []quarantineRecord
	seen := map[string]bool{}
	scanner := bufio.NewScanner(f)
	buf := make([]byte, 64*1024)
	scanner.Buffer(buf, 4*1024*1024)
	lineNo := 0
	for scanner.Scan() {
		lineNo++
		raw := scanner.Text()
		line := strings.TrimSpace(raw)
		if line == "" {
			continue
		}
		record, err := decodeRecord([]byte(line))
		if err == nil {
			err = validateRecord(record)
		}
		if err == nil && seen[record.RunID] {
			err = fmt.Errorf("duplicate run_id: %s", record.RunID)
		}
		if err != nil {
			malformed = append(malformed, quarantineRecord{Error: err.Error(), Line: raw, LineNumber: lineNo})
			continue
		}
		seen[record.RunID] = true
		records = append(records, record)
	}
	if err := scanner.Err(); err != nil {
		return nil, berror.WrapExternal("reading run history", s.Path, "filesystem", err)
	}
	if clean && len(malformed) > 0 {
		if err := s.appendQuarantineUnlocked(malformed); err != nil {
			return nil, err
		}
		if err := s.writeUnlocked(records); err != nil {
			return nil, err
		}
	}
	return records, nil
}

type quarantineRecord struct {
	Error      string `json:"error"`
	Line       string `json:"line"`
	LineNumber int    `json:"line_number"`
}

func (s *Store) appendQuarantineUnlocked(items []quarantineRecord) error {
	f, err := os.OpenFile(s.QuarantinePath(), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return berror.WrapExternal("writing run-history quarantine", s.QuarantinePath(), "filesystem", err)
	}
	defer f.Close()
	for _, item := range items {
		data, _ := json.Marshal(item)
		if _, err := f.Write(append(data, '\n')); err != nil {
			return berror.WrapExternal("writing run-history quarantine", s.QuarantinePath(), "filesystem", err)
		}
	}
	if err := f.Sync(); err != nil {
		return err
	}
	return nil
}

func (s *Store) writeUnlocked(records []models.RunRecord) error {
	records = s.applyRetention(records)
	var out bytes.Buffer
	for _, record := range records {
		if err := validateRecord(record); err != nil {
			return err
		}
		data, err := json.Marshal(record)
		if err != nil {
			return berror.Validation("run spec_snapshot must be JSON serializable", map[string]any{"reason": err.Error()})
		}
		out.Write(data)
		out.WriteByte('\n')
	}
	dir := filepath.Dir(s.Path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	f, err := os.CreateTemp(dir, "."+filepath.Base(s.Path)+".*.tmp")
	if err != nil {
		return err
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	if err := f.Chmod(0o600); err != nil {
		f.Close()
		return err
	}
	if _, err := f.Write(out.Bytes()); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmp, s.Path); err != nil {
		return err
	}
	d, err := os.Open(dir)
	if err == nil {
		_ = d.Sync()
		_ = d.Close()
	}
	return nil
}

func (s *Store) applyRetention(records []models.RunRecord) []models.RunRecord {
	if s.MaxRecords <= 0 || len(records) <= s.MaxRecords {
		return records
	}
	copyRecords := append([]models.RunRecord(nil), records...)
	sort.Slice(copyRecords, func(i, j int) bool {
		ti, _ := parseISO(copyRecords[i].StartedAt)
		tj, _ := parseISO(copyRecords[j].StartedAt)
		return ti.After(tj)
	})
	keep := map[string]bool{}
	for _, r := range copyRecords[:s.MaxRecords] {
		keep[r.RunID] = true
	}
	out := make([]models.RunRecord, 0, s.MaxRecords)
	for _, r := range records {
		if keep[r.RunID] {
			out = append(out, r)
		}
	}
	return out
}

func (s *Store) RecoverInterrupted() (int, error) {
	changed := 0
	err := s.withLock(func() error {
		records, err := s.loadUnlocked(true)
		if err != nil {
			return err
		}
		now := nowISO()
		for i := range records {
			if records[i].Status == "started" {
				records[i].Status = "interrupted"
				records[i].CompletedAt = &now
				if records[i].ErrorMessage == nil || *records[i].ErrorMessage == "" {
					msg := "Run interrupted before completion"
					records[i].ErrorMessage = &msg
				}
				changed++
			}
		}
		if changed > 0 {
			return s.writeUnlocked(records)
		}
		return nil
	})
	return changed, err
}

func (s *Store) RecordStart(runID, operation string, sources []string, destination, archive *string, snapshot map[string]any) (models.RunRecord, error) {
	if !validOperations[operation] {
		return models.RunRecord{}, berror.Validation("Invalid run operation", map[string]any{"operation": operation})
	}
	record := models.RunRecord{ArchivePath: archive, CompletedAt: nil, DestinationPath: destination, Operation: operation, RunID: runID, SourcePaths: append([]string(nil), sources...), SpecSnapshot: snapshot, StartedAt: nowISO(), Status: "started"}
	if err := validateRecord(record); err != nil {
		return models.RunRecord{}, err
	}
	err := s.withLock(func() error {
		records, err := s.loadUnlocked(true)
		if err != nil {
			return err
		}
		for _, existing := range records {
			if existing.RunID == runID {
				return berror.Validation("Run ID already exists", map[string]any{"run_id": runID})
			}
		}
		records = append(records, record)
		return s.writeUnlocked(records)
	})
	return record, err
}

func (s *Store) RecordCompletion(runID, status string, filesCount, bytesProcessed *int64, errorMessage *string) (models.RunRecord, error) {
	if status != "success" && status != "failed" && status != "interrupted" {
		return models.RunRecord{}, berror.Validation("Invalid run completion status", map[string]any{"status": status})
	}
	if filesCount != nil && *filesCount < 0 {
		return models.RunRecord{}, berror.Validation("files_count must be a non-negative integer or None", map[string]any{"files_count": *filesCount})
	}
	if bytesProcessed != nil && *bytesProcessed < 0 {
		return models.RunRecord{}, berror.Validation("bytes_processed must be a non-negative integer or None", map[string]any{"bytes_processed": *bytesProcessed})
	}
	var updated models.RunRecord
	found := false
	err := s.withLock(func() error {
		records, err := s.loadUnlocked(true)
		if err != nil {
			return err
		}
		for i := range records {
			if records[i].RunID == runID {
				now := nowISO()
				records[i].CompletedAt = &now
				records[i].Status = status
				records[i].FilesCount = filesCount
				records[i].BytesProcessed = bytesProcessed
				records[i].ErrorMessage = errorMessage
				updated = records[i]
				found = true
				break
			}
		}
		if !found {
			return berror.NotFound("Run not found: "+runID, map[string]any{"run_id": runID})
		}
		return s.writeUnlocked(records)
	})
	return updated, err
}

func (s *Store) List(limit int, operation string) ([]models.RunRecord, error) {
	if limit <= 0 {
		return nil, berror.Validation("Run list limit must be greater than zero", map[string]any{"limit": limit})
	}
	records, err := s.Load()
	if err != nil {
		return nil, err
	}
	out := records[:0]
	for _, r := range records {
		if operation == "" || r.Operation == operation {
			out = append(out, r)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		ti, _ := parseISO(out[i].StartedAt)
		tj, _ := parseISO(out[j].StartedAt)
		return ti.After(tj)
	})
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}
func (s *Store) Count(operation string) (int, error) {
	records, err := s.Load()
	if err != nil {
		return 0, err
	}
	n := 0
	for _, r := range records {
		if operation == "" || r.Operation == operation {
			n++
		}
	}
	return n, nil
}
func (s *Store) Get(runID string) (*models.RunRecord, error) {
	records, err := s.Load()
	if err != nil {
		return nil, err
	}
	for _, r := range records {
		if r.RunID == runID {
			x := r
			return &x, nil
		}
	}
	return nil, nil
}
func (s *Store) ClearAll() (int, error) {
	count := 0
	err := s.withLock(func() error {
		records, err := s.loadUnlocked(true)
		if err != nil {
			return err
		}
		count = len(records)
		_, statErr := os.Stat(s.Path)
		existed := statErr == nil
		if err := s.writeUnlocked([]models.RunRecord{}); err != nil {
			return err
		}
		if count == 0 && existed {
			count = 1
		}
		return nil
	})
	return count, err
}

func decodeRecord(data []byte) (models.RunRecord, error) {
	var record models.RunRecord
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&record); err != nil {
		return record, err
	}
	var extra any
	if err := dec.Decode(&extra); err != io.EOF {
		return record, fmt.Errorf("trailing JSON data")
	}
	return record, nil
}
func validateRecord(r models.RunRecord) error {
	if strings.TrimSpace(r.RunID) == "" {
		return fmt.Errorf("run_id must be non-empty")
	}
	if _, err := parseISO(r.StartedAt); err != nil {
		return err
	}
	if r.CompletedAt != nil {
		if _, err := parseISO(*r.CompletedAt); err != nil {
			return err
		}
	}
	if !validOperations[r.Operation] {
		return fmt.Errorf("invalid operation: %s", r.Operation)
	}
	if !validStatuses[r.Status] {
		return fmt.Errorf("invalid status: %s", r.Status)
	}
	for _, v := range r.SourcePaths {
		_ = v
	}
	if r.FilesCount != nil && *r.FilesCount < 0 {
		return fmt.Errorf("files_count must be a non-negative integer or null")
	}
	if r.BytesProcessed != nil && *r.BytesProcessed < 0 {
		return fmt.Errorf("bytes_processed must be a non-negative integer or null")
	}
	if r.SpecSnapshot != nil {
		if _, err := json.Marshal(r.SpecSnapshot); err != nil {
			return err
		}
	}
	return nil
}
func parseISO(value string) (time.Time, error) {
	for _, layout := range []string{time.RFC3339Nano, "2006-01-02T15:04:05"} {
		if t, err := time.Parse(layout, value); err == nil {
			return t, nil
		}
	}
	return time.Time{}, fmt.Errorf("invalid ISO datetime: %s", value)
}
func nowISO() string { return time.Now().Format("2006-01-02T15:04:05.999999999") }
