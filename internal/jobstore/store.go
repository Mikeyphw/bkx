package jobstore

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
	"time"

	"bkx/internal/berror"
	"bkx/internal/models"
	"bkx/internal/statepaths"
)

const documentSchemaVersion = 2

type document struct {
	SchemaVersion int                          `json:"schema_version"`
	Jobs          []models.SavedJob            `json:"jobs"`
	Revisions     map[string][]models.SavedJob `json:"revisions"`
}

type legacyDocumentV1 struct {
	SchemaVersion int                `json:"schema_version"`
	Jobs          []legacySavedJobV1 `json:"jobs"`
}

type legacySavedJobV1 struct {
	JobID         string         `json:"job_id"`
	Name          string         `json:"name"`
	Description   string         `json:"description,omitempty"`
	Tags          []string       `json:"tags,omitempty"`
	Enabled       *bool          `json:"enabled,omitempty"`
	Operation     string         `json:"operation"`
	Parameters    map[string]any `json:"parameters,omitempty"`
	RepositoryRef *string        `json:"repository_ref,omitempty"`
	CreatedAt     string         `json:"created_at,omitempty"`
	UpdatedAt     string         `json:"updated_at,omitempty"`
}

type Store struct {
	Path       string
	LegacyPath string
	Now        func() time.Time
}

func New(path string) *Store {
	return &Store{Path: path, Now: time.Now}
}

func Default(home string) *Store {
	path := statepaths.JobStorePath(home)
	return &Store{Path: path, LegacyPath: statepaths.LegacyJobStorePath(home), Now: time.Now}
}

func DefaultPath(home string) string {
	return statepaths.JobStorePath(home)
}

func (s *Store) LockPath() string { return s.Path + ".lock" }

func (s *Store) withLock(fn func() error) error {
	if s.Path == "" {
		return berror.Validation("saved-job store path must not be empty", nil)
	}
	if err := os.MkdirAll(filepath.Dir(s.Path), 0o700); err != nil {
		return berror.WrapExternal("creating saved-job directory", filepath.Dir(s.Path), "filesystem", err)
	}
	lock, err := os.OpenFile(s.LockPath(), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return berror.WrapExternal("opening saved-job lock", s.LockPath(), "filesystem", err)
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX); err != nil {
		return berror.WrapExternal("locking saved jobs", s.LockPath(), "filesystem", err)
	}
	defer syscall.Flock(int(lock.Fd()), syscall.LOCK_UN)
	if strings.TrimSpace(s.LegacyPath) != "" {
		if err := statepaths.SeedLegacyFile(s.Path, s.LegacyPath); err != nil {
			return berror.WrapExternal("seeding shared saved jobs from legacy state", s.LegacyPath, "filesystem", err)
		}
	}
	return fn()
}

func emptyDocument() document {
	return document{SchemaVersion: documentSchemaVersion, Jobs: []models.SavedJob{}, Revisions: map[string][]models.SavedJob{}}
}

func (s *Store) loadUnlocked() (document, error) {
	data, err := os.ReadFile(s.Path)
	if err != nil {
		if os.IsNotExist(err) {
			return emptyDocument(), nil
		}
		return document{}, berror.WrapExternal("reading saved jobs", s.Path, "filesystem", err)
	}
	var header struct {
		SchemaVersion int `json:"schema_version"`
	}
	if err := json.Unmarshal(data, &header); err != nil {
		return document{}, berror.Validation("saved-job store is not valid JSON", map[string]any{"reason": err.Error()})
	}
	switch header.SchemaVersion {
	case documentSchemaVersion:
		var doc document
		if err := json.Unmarshal(data, &doc); err != nil {
			return document{}, berror.Validation("saved-job store does not match schema v2", map[string]any{"reason": err.Error()})
		}
		if doc.Revisions == nil {
			doc.Revisions = map[string][]models.SavedJob{}
		}
		if doc.Jobs == nil {
			doc.Jobs = []models.SavedJob{}
		}
		if err := validateDocument(doc); err != nil {
			return document{}, err
		}
		return doc, nil
	case 1:
		var legacy legacyDocumentV1
		if err := json.Unmarshal(data, &legacy); err != nil {
			return document{}, berror.Validation("saved-job store does not match legacy schema v1", map[string]any{"reason": err.Error()})
		}
		migrated, err := s.migrateV1(legacy)
		if err != nil {
			return document{}, err
		}
		if err := s.writeUnlocked(migrated); err != nil {
			return document{}, err
		}
		return migrated, nil
	default:
		return document{}, berror.Validation("unsupported saved-job store schema_version", map[string]any{"schema_version": header.SchemaVersion})
	}
}

func (s *Store) migrateV1(legacy legacyDocumentV1) (document, error) {
	doc := emptyDocument()
	now := s.nowISO()
	for _, old := range legacy.Jobs {
		enabled := true
		if old.Enabled != nil {
			enabled = *old.Enabled
		}
		created := old.CreatedAt
		if created == "" {
			created = now
		}
		updated := old.UpdatedAt
		if updated == "" {
			updated = created
		}
		params := old.Parameters
		if params == nil {
			params = map[string]any{}
		}
		job := models.SavedJob{
			SchemaVersion: models.SavedJobSchemaVersion,
			JobID:         old.JobID, Name: old.Name, Description: old.Description, Tags: append([]string(nil), old.Tags...),
			Revision: 1, Enabled: enabled,
			Operation: models.OperationSpecV2{
				SchemaVersion: models.OperationSpecV2SchemaVersion,
				Operation:     old.Operation,
				Parameters:    params,
				RepositoryRef: old.RepositoryRef,
			},
			CreatedAt: created, UpdatedAt: updated,
		}
		if err := models.ValidateSavedJob(job); err != nil {
			return document{}, berror.Validation("legacy saved job cannot be migrated", map[string]any{"job_id": old.JobID, "reason": err.Error()})
		}
		doc.Jobs = append(doc.Jobs, job)
	}
	if err := validateDocument(doc); err != nil {
		return document{}, err
	}
	return doc, nil
}

func validateDocument(doc document) error {
	if doc.SchemaVersion != documentSchemaVersion {
		return berror.Validation("saved-job document schema_version mismatch", map[string]any{"schema_version": doc.SchemaVersion})
	}
	seen := map[string]bool{}
	for _, job := range doc.Jobs {
		if err := models.ValidateSavedJob(job); err != nil {
			return berror.Validation("invalid saved job", map[string]any{"job_id": job.JobID, "reason": err.Error()})
		}
		if seen[job.JobID] {
			return berror.Validation("duplicate saved job id", map[string]any{"job_id": job.JobID})
		}
		seen[job.JobID] = true
	}
	for jobID, revisions := range doc.Revisions {
		last := int64(0)
		for _, revision := range revisions {
			if revision.JobID != jobID {
				return berror.Validation("saved-job revision history contains the wrong job id", map[string]any{"job_id": jobID})
			}
			if err := models.ValidateSavedJob(revision); err != nil {
				return berror.Validation("invalid saved-job revision", map[string]any{"job_id": jobID, "reason": err.Error()})
			}
			if revision.Revision <= last {
				return berror.Validation("saved-job revision history must be strictly increasing", map[string]any{"job_id": jobID})
			}
			last = revision.Revision
		}
	}
	return nil
}

func (s *Store) writeUnlocked(doc document) error {
	if err := validateDocument(doc); err != nil {
		return err
	}
	data, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return berror.Validation("saved jobs must be JSON serializable", map[string]any{"reason": err.Error()})
	}
	data = append(data, '\n')
	dir := filepath.Dir(s.Path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
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
	if _, err := f.Write(data); err != nil {
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
	if d, err := os.Open(dir); err == nil {
		_ = d.Sync()
		_ = d.Close()
	}
	return nil
}

func cloneJob(job models.SavedJob) models.SavedJob {
	data, _ := json.Marshal(job)
	var out models.SavedJob
	_ = json.Unmarshal(data, &out)
	return out
}

func (s *Store) nowISO() string {
	now := time.Now
	if s.Now != nil {
		now = s.Now
	}
	return now().UTC().Format(time.RFC3339Nano)
}

func (s *Store) List() ([]models.SavedJob, error) {
	var out []models.SavedJob
	err := s.withLock(func() error {
		doc, err := s.loadUnlocked()
		if err != nil {
			return err
		}
		out = make([]models.SavedJob, len(doc.Jobs))
		for i, job := range doc.Jobs {
			out[i] = cloneJob(job)
		}
		sort.Slice(out, func(i, j int) bool {
			if out[i].Name == out[j].Name {
				return out[i].JobID < out[j].JobID
			}
			return out[i].Name < out[j].Name
		})
		return nil
	})
	return out, err
}

func (s *Store) Get(jobID string) (*models.SavedJob, error) {
	var out *models.SavedJob
	err := s.withLock(func() error {
		doc, err := s.loadUnlocked()
		if err != nil {
			return err
		}
		for _, job := range doc.Jobs {
			if job.JobID == jobID {
				copy := cloneJob(job)
				out = &copy
				return nil
			}
		}
		return nil
	})
	return out, err
}

func (s *Store) Create(job models.SavedJob) (models.SavedJob, error) {
	var created models.SavedJob
	err := s.withLock(func() error {
		doc, err := s.loadUnlocked()
		if err != nil {
			return err
		}
		for _, existing := range doc.Jobs {
			if existing.JobID == job.JobID {
				return berror.Validation("saved job already exists", map[string]any{"job_id": job.JobID})
			}
		}
		now := s.nowISO()
		job.SchemaVersion = models.SavedJobSchemaVersion
		job.Revision = 1
		if job.CreatedAt == "" {
			job.CreatedAt = now
		}
		if job.UpdatedAt == "" {
			job.UpdatedAt = job.CreatedAt
		}
		if job.Operation.SchemaVersion == 0 {
			job.Operation.SchemaVersion = models.OperationSpecV2SchemaVersion
		}
		if job.Operation.Parameters == nil {
			job.Operation.Parameters = map[string]any{}
		}
		if err := models.ValidateSavedJob(job); err != nil {
			return berror.Validation("invalid saved job", map[string]any{"job_id": job.JobID, "reason": err.Error()})
		}
		doc.Jobs = append(doc.Jobs, cloneJob(job))
		if err := s.writeUnlocked(doc); err != nil {
			return err
		}
		created = cloneJob(job)
		return nil
	})
	return created, err
}

func (s *Store) Update(jobID string, expectedRevision int64, replacement models.SavedJob) (models.SavedJob, error) {
	var updated models.SavedJob
	err := s.withLock(func() error {
		doc, err := s.loadUnlocked()
		if err != nil {
			return err
		}
		index := -1
		for i := range doc.Jobs {
			if doc.Jobs[i].JobID == jobID {
				index = i
				break
			}
		}
		if index < 0 {
			return berror.Validation("saved job not found", map[string]any{"job_id": jobID})
		}
		current := doc.Jobs[index]
		if current.Revision != expectedRevision {
			return berror.Validation("saved job revision conflict", map[string]any{"job_id": jobID, "expected_revision": expectedRevision, "current_revision": current.Revision})
		}
		if replacement.JobID != "" && replacement.JobID != jobID {
			return berror.Validation("saved job update cannot change job_id", map[string]any{"job_id": jobID})
		}
		replacement.JobID = jobID
		replacement.SchemaVersion = models.SavedJobSchemaVersion
		replacement.Revision = current.Revision + 1
		replacement.CreatedAt = current.CreatedAt
		replacement.UpdatedAt = s.nowISO()
		if replacement.Operation.SchemaVersion == 0 {
			replacement.Operation.SchemaVersion = models.OperationSpecV2SchemaVersion
		}
		if replacement.Operation.Parameters == nil {
			replacement.Operation.Parameters = map[string]any{}
		}
		if err := models.ValidateSavedJob(replacement); err != nil {
			return berror.Validation("invalid saved job update", map[string]any{"job_id": jobID, "reason": err.Error()})
		}
		doc.Revisions[jobID] = append(doc.Revisions[jobID], cloneJob(current))
		doc.Jobs[index] = cloneJob(replacement)
		if err := s.writeUnlocked(doc); err != nil {
			return err
		}
		updated = cloneJob(replacement)
		return nil
	})
	return updated, err
}

func (s *Store) Delete(jobID string) (bool, error) {
	deleted := false
	err := s.withLock(func() error {
		doc, err := s.loadUnlocked()
		if err != nil {
			return err
		}
		next := make([]models.SavedJob, 0, len(doc.Jobs))
		for _, job := range doc.Jobs {
			if job.JobID == jobID {
				deleted = true
				continue
			}
			next = append(next, job)
		}
		if !deleted {
			return nil
		}
		doc.Jobs = next
		delete(doc.Revisions, jobID)
		return s.writeUnlocked(doc)
	})
	return deleted, err
}

func (s *Store) Revisions(jobID string) ([]models.SavedJob, error) {
	var out []models.SavedJob
	err := s.withLock(func() error {
		doc, err := s.loadUnlocked()
		if err != nil {
			return err
		}
		for _, revision := range doc.Revisions[jobID] {
			out = append(out, cloneJob(revision))
		}
		for _, current := range doc.Jobs {
			if current.JobID == jobID {
				out = append(out, cloneJob(current))
				break
			}
		}
		sort.Slice(out, func(i, j int) bool { return out[i].Revision < out[j].Revision })
		return nil
	})
	return out, err
}

func (s *Store) GetRevision(jobID string, revision int64) (*models.SavedJob, error) {
	revisions, err := s.Revisions(jobID)
	if err != nil {
		return nil, err
	}
	for _, item := range revisions {
		if item.Revision == revision {
			copy := cloneJob(item)
			return &copy, nil
		}
	}
	return nil, nil
}

func (s *Store) String() string { return fmt.Sprintf("jobstore(%s)", s.Path) }
