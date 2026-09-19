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
	"time"

	"bkx/internal/berror"
	"bkx/internal/executioncontrol"
	"bkx/internal/filelock"
	"bkx/internal/models"
	"bkx/internal/statepaths"
)

const EnvLegacyRunHistory = "BKX_LEGACY_RUN_HISTORY"

var validStatuses = map[string]bool{"started": true, "success": true, "failed": true, "cancelled": true, "interrupted": true}
var validPhases = map[string]bool{"queued": true, "preparing": true, "running": true, "finalizing": true, "verifying": true, "succeeded": true, "failed": true, "cancelled": true, "interrupted": true}
var validOperations = map[string]bool{"backup": true, "archive": true, "transfer": true, "restore": true, "run": true, "unknown": true}

// SavedJobOrigin is immutable provenance captured atomically with RecordStart.
// It contains only the validated OperationSpecV2, whose credential fields are references.
type SavedJobOrigin struct {
	JobID     string
	Revision  int64
	Operation models.OperationSpecV2
	Override  bool
}

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
	return statepaths.RunHistoryPath(home)
}

func LegacyDefaultPath(home string) string {
	return statepaths.LegacyRunHistoryPath(home)
}

type MergeResult struct {
	Added      int
	Duplicates int
}

// MergeLegacyHistory unions unique historical runs into the canonical store.
// It never deletes or rewrites the legacy file. A duplicate run_id keeps the
// canonical record so migration is deterministic even if two clients had
// already converged independently.
func MergeLegacyHistory(canonicalPath, legacyPath string) (MergeResult, error) {
	canonicalPath = filepath.Clean(strings.TrimSpace(canonicalPath))
	legacyPath = filepath.Clean(strings.TrimSpace(legacyPath))
	if canonicalPath == legacyPath || legacyPath == "." || legacyPath == "" {
		return MergeResult{}, nil
	}
	if _, err := os.Stat(legacyPath); err != nil {
		if os.IsNotExist(err) {
			return MergeResult{}, nil
		}
		return MergeResult{}, berror.WrapExternal("reading legacy run history", legacyPath, "filesystem", err)
	}
	legacy, err := New(legacyPath, 0, false)
	if err != nil {
		return MergeResult{}, err
	}
	var incoming []models.RunRecord
	if err := legacy.withLock(func() error {
		var loadErr error
		incoming, loadErr = legacy.loadUnlocked(false)
		return loadErr
	}); err != nil {
		return MergeResult{}, err
	}
	if len(incoming) == 0 {
		return MergeResult{}, nil
	}
	canonical, err := New(canonicalPath, 0, false)
	if err != nil {
		return MergeResult{}, err
	}
	result := MergeResult{}
	err = canonical.withLock(func() error {
		records, err := canonical.loadUnlocked(true)
		if err != nil {
			return err
		}
		seen := make(map[string]struct{}, len(records))
		for _, record := range records {
			seen[record.RunID] = struct{}{}
		}
		for _, record := range incoming {
			if _, exists := seen[record.RunID]; exists {
				result.Duplicates++
				continue
			}
			if err := validateRecord(record); err != nil {
				return err
			}
			records = append(records, record)
			seen[record.RunID] = struct{}{}
			result.Added++
		}
		if result.Added == 0 {
			return nil
		}
		return canonical.writeUnlocked(records)
	})
	return result, err
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
	unlock, err := filelock.Acquire(lock, filelock.Exclusive)
	if err != nil {
		return berror.WrapExternal("locking run history", s.LockPath(), "filesystem", err)
	}
	defer unlock()
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
	return statepaths.AtomicWriteFile(s.Path, out.Bytes(), 0o600)
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
	return s.RecoverInterruptedWithControl(nil)
}

// RecoverInterruptedWithControl preserves operations owned by a live shared
// execution lease when another CLI or Android client opens the same history.
func (s *Store) RecoverInterruptedWithControl(control *executioncontrol.Store) (int, error) {
	changed := 0
	err := s.withLock(func() error {
		records, err := s.loadUnlocked(true)
		if err != nil {
			return err
		}
		active := map[string]bool{}
		if control != nil {
			// Read leases while holding the history lock. Acquiring a lease
			// precedes RecordStart, so a new run cannot slip past this check.
			leases, err := control.Active()
			if err != nil {
				return err
			}
			for _, lease := range leases {
				active[lease.RequestID] = true
			}
		}
		now := nowISO()
		for i := range records {
			if records[i].Status == "started" && !active[records[i].RunID] {
				records[i].Status = "interrupted"
				records[i].Phase = "interrupted"
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
	return s.RecordStartWithOrigin(runID, operation, sources, destination, archive, snapshot, nil)
}

// RecordStartWithOrigin writes run lifecycle state and saved-job provenance in
// one atomic history update. An interrupted run therefore still knows the exact
// job revision/effective spec that started it.
func (s *Store) RecordStartWithOrigin(runID, operation string, sources []string, destination, archive *string, snapshot map[string]any, origin *SavedJobOrigin) (models.RunRecord, error) {
	if !validOperations[operation] {
		return models.RunRecord{}, berror.Validation("Invalid run operation", map[string]any{"operation": operation})
	}
	record := models.RunRecord{ArchivePath: archive, CompletedAt: nil, DestinationPath: destination, Operation: operation, RunID: runID, SourcePaths: append([]string(nil), sources...), SpecSnapshot: snapshot, StartedAt: nowISO(), Status: "started", Phase: "queued"}
	if origin != nil {
		jobID := strings.TrimSpace(origin.JobID)
		if jobID == "" || origin.Revision < 1 {
			return models.RunRecord{}, berror.Validation("Saved-job run origin requires job_id and positive revision", map[string]any{"job_id": jobID, "revision": origin.Revision})
		}
		if err := models.ValidateOperationSpecV2(origin.Operation); err != nil {
			return models.RunRecord{}, berror.Validation("Invalid saved-job operation snapshot", map[string]any{"reason": err.Error()})
		}
		encoded, err := json.Marshal(origin.Operation)
		if err != nil {
			return models.RunRecord{}, err
		}
		var cloned models.OperationSpecV2
		if err := json.Unmarshal(encoded, &cloned); err != nil {
			return models.RunRecord{}, err
		}
		record.JobID = jobID
		record.JobRevision = origin.Revision
		record.OperationSpecSnapshot = &cloned
		if origin.Override {
			if record.SpecSnapshot == nil {
				record.SpecSnapshot = map[string]any{}
			}
			record.SpecSnapshot["_saved_job_execution_override"] = true
		}
	}
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
	if status != "success" && status != "failed" && status != "cancelled" && status != "interrupted" {
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
				records[i].Phase = terminalPhase(status)
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

func terminalPhase(status string) string {
	switch status {
	case "success":
		return "succeeded"
	case "failed":
		return "failed"
	case "cancelled":
		return "cancelled"
	case "interrupted":
		return "interrupted"
	default:
		return ""
	}
}

// RecordPhase durably records the canonical lifecycle phase for an active run.
// Legacy status remains stable for compatibility while phase provides the
// richer BR18 queued/preparing/running/finalizing/verifying lifecycle.
func (s *Store) RecordPhase(runID, phase string) (models.RunRecord, error) {
	phase = strings.TrimSpace(strings.ToLower(phase))
	if !validPhases[phase] {
		return models.RunRecord{}, berror.Validation("Invalid run phase", map[string]any{"phase": phase})
	}
	var updated models.RunRecord
	found := false
	err := s.withLock(func() error {
		records, err := s.loadUnlocked(true)
		if err != nil {
			return err
		}
		for i := range records {
			if records[i].RunID != runID {
				continue
			}
			if records[i].CompletedAt != nil {
				return berror.Validation("Completed run phase cannot be changed", map[string]any{"run_id": runID, "phase": records[i].Phase})
			}
			records[i].Phase = phase
			updated = records[i]
			found = true
			break
		}
		if !found {
			return berror.NotFound("Run not found: "+runID, map[string]any{"run_id": runID})
		}
		return s.writeUnlocked(records)
	})
	return updated, err
}

// RecordSavedJobOrigin binds a run to the exact revision and effective
// OperationSpecV2 used to execute it. Credentials remain references only.
func (s *Store) RecordSavedJobOrigin(runID, jobID string, revision int64, spec models.OperationSpecV2) (models.RunRecord, error) {
	jobID = strings.TrimSpace(jobID)
	if jobID == "" || revision < 1 {
		return models.RunRecord{}, berror.Validation("Saved-job run origin requires job_id and positive revision", map[string]any{"job_id": jobID, "revision": revision})
	}
	if err := models.ValidateOperationSpecV2(spec); err != nil {
		return models.RunRecord{}, berror.Validation("Invalid saved-job operation snapshot", map[string]any{"reason": err.Error()})
	}
	encoded, err := json.Marshal(spec)
	if err != nil {
		return models.RunRecord{}, err
	}
	var cloned models.OperationSpecV2
	if err := json.Unmarshal(encoded, &cloned); err != nil {
		return models.RunRecord{}, err
	}
	var updated models.RunRecord
	found := false
	err = s.withLock(func() error {
		records, err := s.loadUnlocked(true)
		if err != nil {
			return err
		}
		for i := range records {
			if records[i].RunID != runID {
				continue
			}
			records[i].JobID = jobID
			records[i].JobRevision = revision
			records[i].OperationSpecSnapshot = &cloned
			updated = records[i]
			found = true
			break
		}
		if !found {
			return berror.NotFound("Run not found: "+runID, map[string]any{"run_id": runID})
		}
		return s.writeUnlocked(records)
	})
	return updated, err
}

// ReplayOperation reconstructs the exact effective OperationSpecV2 when a
// BR20 run-origin snapshot exists, falling back to the historical v1 run
// snapshot format for migrated runs.
func ReplayOperation(record models.RunRecord) (models.OperationSpecV2, error) {
	if record.OperationSpecSnapshot != nil {
		encoded, err := json.Marshal(record.OperationSpecSnapshot)
		if err != nil {
			return models.OperationSpecV2{}, err
		}
		var cloned models.OperationSpecV2
		if err := json.Unmarshal(encoded, &cloned); err != nil {
			return models.OperationSpecV2{}, err
		}
		if err := models.ValidateOperationSpecV2(cloned); err != nil {
			return cloned, berror.Validation("historical operation spec snapshot cannot be replayed", map[string]any{"reason": err.Error()})
		}
		return cloned, nil
	}
	values := map[string]any{}
	if record.SpecSnapshot != nil {
		encoded, err := json.Marshal(record.SpecSnapshot)
		if err != nil {
			return models.OperationSpecV2{}, err
		}
		if err := json.Unmarshal(encoded, &values); err != nil {
			return models.OperationSpecV2{}, err
		}
	}
	if typed, ok := values["values"].(map[string]any); ok {
		values = typed
	}
	delete(values, "_resolved_destination")
	delete(values, "_archive_path")
	op := ""
	switch record.Operation {
	case "backup":
		if engine, _ := values["engine"].(string); strings.EqualFold(engine, models.BackupEngineRestic) {
			op = "backup.snapshot"
		} else {
			op = "backup.create"
		}
	case "transfer":
		op = "transfer.run"
	default:
		return models.OperationSpecV2{}, berror.Validation("historical operation is not replayable yet", map[string]any{"operation": record.Operation})
	}
	spec := models.NewOperationSpecV2(op, values)
	if op == "backup.snapshot" {
		if rawRef, ok := values["credential_ref"].(string); ok && strings.TrimSpace(rawRef) != "" {
			delete(spec.Parameters, "credential_ref")
			spec.CredentialRefs = []models.SecretReference{{ID: strings.TrimSpace(rawRef), Purpose: "restic repository password"}}
		}
	}
	if err := models.ValidateOperationSpecV2(spec); err != nil {
		return spec, berror.Validation("historical spec snapshot cannot be replayed", map[string]any{"reason": err.Error()})
	}
	return spec, nil
}

type ListPageResult struct {
	Records       []models.RunRecord
	NextPageToken string
	Total         int
}

func (s *Store) List(limit int, operation string) ([]models.RunRecord, error) {
	page, err := s.ListPage(limit, operation, "")
	return page.Records, err
}

func (s *Store) ListPage(limit int, operation, pageToken string) (ListPageResult, error) {
	if limit <= 0 {
		return ListPageResult{}, berror.Validation("Run list limit must be greater than zero", map[string]any{"limit": limit})
	}
	if limit > 500 {
		return ListPageResult{}, berror.Validation("Run list limit must be at most 500 per page", map[string]any{"limit": limit})
	}
	offset, err := parsePageOffset(pageToken)
	if err != nil {
		return ListPageResult{}, err
	}
	records, err := s.Load()
	if err != nil {
		return ListPageResult{}, err
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
		if ti.Equal(tj) {
			return out[i].RunID < out[j].RunID
		}
		return ti.After(tj)
	})
	if offset > len(out) {
		offset = len(out)
	}
	end := offset + limit
	if end > len(out) {
		end = len(out)
	}
	result := ListPageResult{Records: append([]models.RunRecord(nil), out[offset:end]...), Total: len(out)}
	if end < len(out) {
		result.NextPageToken = fmt.Sprintf("offset:%d", end)
	}
	return result, nil
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
	if r.Phase != "" && !validPhases[r.Phase] {
		return fmt.Errorf("invalid phase: %s", r.Phase)
	}
	if r.JobRevision < 0 {
		return fmt.Errorf("job_revision must not be negative")
	}
	if r.JobID == "" && r.JobRevision != 0 {
		return fmt.Errorf("job_revision requires job_id")
	}
	if r.OperationSpecSnapshot != nil {
		if r.JobID == "" || r.JobRevision < 1 {
			return fmt.Errorf("operation_spec_snapshot requires saved-job origin metadata")
		}
		if err := models.ValidateOperationSpecV2(*r.OperationSpecSnapshot); err != nil {
			return fmt.Errorf("invalid operation_spec_snapshot: %w", err)
		}
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
func parsePageOffset(token string) (int, error) {
	token = strings.TrimSpace(token)
	if token == "" {
		return 0, nil
	}
	if !strings.HasPrefix(token, "offset:") {
		return 0, berror.Validation("invalid run list page_token", map[string]any{"page_token": token})
	}
	var offset int
	if _, err := fmt.Sscanf(token, "offset:%d", &offset); err != nil || offset < 0 {
		return 0, berror.Validation("invalid run list page_token", map[string]any{"page_token": token})
	}
	return offset, nil
}

func IsReplayable(record models.RunRecord) bool {
	if record.CompletedAt == nil {
		return false
	}
	if record.Status != "success" && record.Status != "failed" && record.Status != "cancelled" && record.Status != "interrupted" {
		return false
	}
	_, err := ReplayOperation(record)
	return err == nil
}

func parseISO(value string) (time.Time, error) {
	for _, layout := range []string{time.RFC3339Nano, "2006-01-02T15:04:05"} {
		if t, err := time.Parse(layout, value); err == nil {
			return t, nil
		}
	}
	return time.Time{}, fmt.Errorf("invalid ISO datetime: %s", value)
}
func nowISO() string { return time.Now().UTC().Format(time.RFC3339Nano) }
