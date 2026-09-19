package repositorystore

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"bkx/internal/berror"
	"bkx/internal/filelock"
	"bkx/internal/models"
	"bkx/internal/rcloneops"
	"bkx/internal/statepaths"
)

const documentSchemaVersion = 1

type document struct {
	SchemaVersion int                           `json:"schema_version"`
	Repositories  []models.RepositoryDefinition `json:"repositories"`
}

type Store struct {
	Path       string
	LegacyPath string
	Now        func() time.Time
}

func New(path string) *Store { return &Store{Path: path, Now: time.Now} }

func Default(home string) *Store {
	path := statepaths.RepositoryStorePath(home)
	return &Store{Path: path, LegacyPath: statepaths.LegacyRepositoryStorePath(home), Now: time.Now}
}

func DefaultPath(home string) string {
	return statepaths.RepositoryStorePath(home)
}

func (s *Store) LockPath() string { return s.Path + ".lock" }

func (s *Store) nowISO() string {
	now := time.Now
	if s.Now != nil {
		now = s.Now
	}
	return now().UTC().Format(time.RFC3339Nano)
}

func (s *Store) withLock(fn func() error) error {
	if strings.TrimSpace(s.Path) == "" {
		return berror.Validation("repository store path must not be empty", nil)
	}
	dir := filepath.Dir(s.Path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return berror.WrapExternal("creating repository store directory", dir, "filesystem", err)
	}
	lock, err := os.OpenFile(s.LockPath(), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return berror.WrapExternal("opening repository store lock", s.LockPath(), "filesystem", err)
	}
	defer lock.Close()
	unlock, err := filelock.Acquire(lock, filelock.Exclusive)
	if err != nil {
		return berror.WrapExternal("locking repositories", s.LockPath(), "filesystem", err)
	}
	defer unlock()
	if strings.TrimSpace(s.LegacyPath) != "" {
		if err := statepaths.SeedLegacyFile(s.Path, s.LegacyPath); err != nil {
			return berror.WrapExternal("seeding shared repositories from legacy state", s.LegacyPath, "filesystem", err)
		}
	}
	return fn()
}

func emptyDocument() document {
	return document{SchemaVersion: documentSchemaVersion, Repositories: []models.RepositoryDefinition{}}
}

func (s *Store) loadUnlocked() (document, error) {
	data, err := os.ReadFile(s.Path)
	if err != nil {
		if os.IsNotExist(err) {
			return emptyDocument(), nil
		}
		return document{}, berror.WrapExternal("reading repositories", s.Path, "filesystem", err)
	}
	var doc document
	if err := json.Unmarshal(data, &doc); err != nil {
		return document{}, berror.Validation("repository store is not valid JSON", map[string]any{"reason": err.Error()})
	}
	if doc.SchemaVersion != documentSchemaVersion {
		return document{}, berror.Validation("unsupported repository store schema_version", map[string]any{"schema_version": doc.SchemaVersion})
	}
	if doc.Repositories == nil {
		doc.Repositories = []models.RepositoryDefinition{}
	}
	if err := validateDocument(doc); err != nil {
		return document{}, err
	}
	return doc, nil
}

func validateDocument(doc document) error {
	if doc.SchemaVersion != documentSchemaVersion {
		return berror.Validation("repository document schema_version mismatch", map[string]any{"schema_version": doc.SchemaVersion})
	}
	seen := map[string]bool{}
	physical := map[string]string{}
	for _, repository := range doc.Repositories {
		if err := models.ValidateRepositoryDefinition(repository); err != nil {
			return berror.Validation("invalid repository definition", map[string]any{"repository_id": repository.RepositoryID, "reason": err.Error()})
		}
		if seen[repository.RepositoryID] {
			return berror.Validation("duplicate repository id", map[string]any{"repository_id": repository.RepositoryID})
		}
		seen[repository.RepositoryID] = true
		key, err := physicalRepositoryKey(repository)
		if err != nil {
			return berror.Validation("invalid repository physical location", map[string]any{"repository_id": repository.RepositoryID, "reason": err.Error()})
		}
		if existing, ok := physical[key]; ok {
			return berror.Validation("duplicate repository physical location", map[string]any{"repository_id": repository.RepositoryID, "conflicts_with": existing})
		}
		physical[key] = repository.RepositoryID
	}
	return nil
}

func physicalRepositoryKey(def models.RepositoryDefinition) (string, error) {
	normalized, err := NormalizeDefinition(def)
	if err != nil {
		return "", err
	}
	return normalized.Backend + "|" + normalized.Location, nil
}

func rejectPhysicalCollision(items []models.RepositoryDefinition, candidate models.RepositoryDefinition, exceptRepositoryID string) error {
	key, err := physicalRepositoryKey(candidate)
	if err != nil {
		return err
	}
	for _, item := range items {
		if item.RepositoryID == exceptRepositoryID {
			continue
		}
		other, err := physicalRepositoryKey(item)
		if err != nil {
			return err
		}
		if other == key {
			return berror.Validation("repository location is already registered under another id", map[string]any{"repository_id": candidate.RepositoryID, "conflicts_with": item.RepositoryID})
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
		return berror.Validation("repositories must be JSON serializable", map[string]any{"reason": err.Error()})
	}
	data = append(data, '\n')
	if err := statepaths.AtomicWriteFile(s.Path, data, 0o600); err != nil {
		return berror.WrapExternal("publishing repository store", s.Path, "filesystem", err)
	}
	return nil
}

func cloneRepository(in models.RepositoryDefinition) models.RepositoryDefinition {
	out := in
	if in.CredentialRef != nil {
		value := *in.CredentialRef
		out.CredentialRef = &value
	}
	out.Retention = models.CloneRetentionPolicy(in.Retention)
	return out
}

// NormalizeDefinition validates and canonicalizes a repository descriptor without
// resolving cloud credentials. Remote locations use BKX's rclone:// URI form;
// restic's rclone: form is created only at process execution time.
func NormalizeDefinition(def models.RepositoryDefinition) (models.RepositoryDefinition, error) {
	def.RepositoryID = strings.TrimSpace(def.RepositoryID)
	def.Name = strings.TrimSpace(def.Name)
	def.Engine = strings.ToLower(strings.TrimSpace(def.Engine))
	def.Backend = strings.ToLower(strings.TrimSpace(def.Backend))
	def.Location = strings.TrimSpace(def.Location)
	if def.Engine == "" {
		def.Engine = models.BackupEngineRestic
	}
	if def.Backend == "" {
		if rcloneops.IsLocation(def.Location) {
			def.Backend = models.RepositoryBackendRclone
		} else {
			def.Backend = models.RepositoryBackendLocal
		}
	}
	if def.CredentialRef != nil {
		value := strings.TrimSpace(*def.CredentialRef)
		def.CredentialRef = &value
	}
	if def.Retention != nil {
		policy := models.CloneRetentionPolicy(def.Retention)
		policy.SchemaVersion = models.RetentionPolicySchemaVersion
		policy.GroupBy = strings.TrimSpace(policy.GroupBy)
		if policy.KeepWithin != nil {
			value := strings.TrimSpace(*policy.KeepWithin)
			policy.KeepWithin = &value
		}
		for i := range policy.KeepTags {
			policy.KeepTags[i] = strings.TrimSpace(policy.KeepTags[i])
		}
		def.Retention = policy
	}
	switch def.Backend {
	case models.RepositoryBackendLocal:
		if rcloneops.IsLocation(def.Location) || strings.Contains(def.Location, "://") || strings.HasPrefix(strings.ToLower(def.Location), "rclone:") {
			return def, berror.Validation("local repository backend requires a filesystem path", map[string]any{"location": def.Location})
		}
		absolute, err := filepath.Abs(def.Location)
		if err != nil {
			return def, berror.Validation("invalid local repository path", map[string]any{"location": def.Location, "reason": err.Error()})
		}
		def.Location = filepath.Clean(absolute)
		def.ManagedByBKX = false
	case models.RepositoryBackendRclone:
		canonical, err := rcloneops.CanonicalURI(def.Location)
		if err != nil {
			return def, berror.Validation("rclone repository requires rclone://remote/path", map[string]any{"location": def.Location, "reason": err.Error()})
		}
		def.Location = canonical
		def.ManagedByBKX = false
	case models.RepositoryBackendManagedGoogleDrive:
		canonical, err := rcloneops.CanonicalURI(def.Location)
		if err != nil {
			return def, berror.Validation("managed Google Drive repository requires a BKX rclone URI", map[string]any{"location": def.Location, "reason": err.Error()})
		}
		native, _ := rcloneops.NormalizeLocation(canonical)
		remote, path, _ := strings.Cut(native, ":")
		path = strings.TrimPrefix(path, "/")
		if remote != models.BKXGoogleDriveRemoteName || (path != "BKX" && !strings.HasPrefix(path, "BKX/")) {
			return def, berror.Validation("managed Google Drive repositories must stay under rclone://bkx-google-drive/BKX", map[string]any{"location": canonical})
		}
		def.Location = canonical
		def.ManagedByBKX = true
	default:
		return def, berror.Validation("unsupported repository backend", map[string]any{"backend": def.Backend})
	}
	return def, nil
}

func RcloneURIFromRemote(raw string) (string, error) {
	value := strings.TrimSpace(raw)
	remote, child, ok := strings.Cut(value, ":")
	if !ok || strings.TrimSpace(remote) == "" {
		return "", berror.Validation("--remote must use REMOTE:PATH syntax", map[string]any{"remote": raw})
	}
	uri := "rclone://" + strings.TrimSpace(remote) + "/" + strings.TrimPrefix(strings.TrimSpace(child), "/")
	canonical, err := rcloneops.CanonicalURI(uri)
	if err != nil {
		return "", berror.Validation("invalid rclone remote", map[string]any{"remote": raw, "reason": err.Error()})
	}
	return canonical, nil
}

func (s *Store) List() ([]models.RepositoryDefinition, error) {
	var out []models.RepositoryDefinition
	err := s.withLock(func() error {
		doc, err := s.loadUnlocked()
		if err != nil {
			return err
		}
		out = make([]models.RepositoryDefinition, len(doc.Repositories))
		for i, item := range doc.Repositories {
			out[i] = cloneRepository(item)
		}
		sort.Slice(out, func(i, j int) bool {
			if out[i].Name == out[j].Name {
				return out[i].RepositoryID < out[j].RepositoryID
			}
			return out[i].Name < out[j].Name
		})
		return nil
	})
	return out, err
}

func (s *Store) Get(repositoryID string) (*models.RepositoryDefinition, error) {
	id := strings.TrimSpace(repositoryID)
	var out *models.RepositoryDefinition
	err := s.withLock(func() error {
		doc, err := s.loadUnlocked()
		if err != nil {
			return err
		}
		for _, item := range doc.Repositories {
			if item.RepositoryID == id {
				copy := cloneRepository(item)
				out = &copy
				break
			}
		}
		return nil
	})
	return out, err
}

func (s *Store) Create(def models.RepositoryDefinition) (models.RepositoryDefinition, error) {
	var created models.RepositoryDefinition
	err := s.withLock(func() error {
		doc, err := s.loadUnlocked()
		if err != nil {
			return err
		}
		for _, item := range doc.Repositories {
			if item.RepositoryID == strings.TrimSpace(def.RepositoryID) {
				return berror.Validation("repository already exists", map[string]any{"repository_id": def.RepositoryID})
			}
		}
		normalized, err := NormalizeDefinition(def)
		if err != nil {
			return err
		}
		if err := rejectPhysicalCollision(doc.Repositories, normalized, normalized.RepositoryID); err != nil {
			return err
		}
		now := s.nowISO()
		normalized.SchemaVersion = models.RepositoryDefinitionSchemaVersion
		normalized.CreatedAt = now
		normalized.UpdatedAt = now
		if normalized.Name == "" {
			normalized.Name = normalized.RepositoryID
		}
		if err := models.ValidateRepositoryDefinition(normalized); err != nil {
			return berror.Validation("invalid repository definition", map[string]any{"repository_id": normalized.RepositoryID, "reason": err.Error()})
		}
		doc.Repositories = append(doc.Repositories, normalized)
		if err := s.writeUnlocked(doc); err != nil {
			return err
		}
		created = cloneRepository(normalized)
		return nil
	})
	return created, err
}

func (s *Store) Update(repositoryID string, replacement models.RepositoryDefinition) (models.RepositoryDefinition, error) {
	var updated models.RepositoryDefinition
	err := s.withLock(func() error {
		doc, err := s.loadUnlocked()
		if err != nil {
			return err
		}
		index := -1
		for i := range doc.Repositories {
			if doc.Repositories[i].RepositoryID == repositoryID {
				index = i
				break
			}
		}
		if index < 0 {
			return berror.NotFound("Repository not found: "+repositoryID, map[string]any{"repository_id": repositoryID})
		}
		current := doc.Repositories[index]
		replacement.RepositoryID = current.RepositoryID
		replacement.CreatedAt = current.CreatedAt
		normalized, err := NormalizeDefinition(replacement)
		if err != nil {
			return err
		}
		if err := rejectPhysicalCollision(doc.Repositories, normalized, current.RepositoryID); err != nil {
			return err
		}
		normalized.SchemaVersion = models.RepositoryDefinitionSchemaVersion
		normalized.UpdatedAt = s.nowISO()
		if normalized.Name == "" {
			normalized.Name = current.Name
		}
		if err := models.ValidateRepositoryDefinition(normalized); err != nil {
			return berror.Validation("invalid repository definition", map[string]any{"repository_id": repositoryID, "reason": err.Error()})
		}
		doc.Repositories[index] = normalized
		if err := s.writeUnlocked(doc); err != nil {
			return err
		}
		updated = cloneRepository(normalized)
		return nil
	})
	return updated, err
}

func (s *Store) Delete(repositoryID string) (bool, error) {
	deleted := false
	err := s.withLock(func() error {
		doc, err := s.loadUnlocked()
		if err != nil {
			return err
		}
		out := doc.Repositories[:0]
		for _, item := range doc.Repositories {
			if item.RepositoryID == repositoryID {
				deleted = true
				continue
			}
			out = append(out, item)
		}
		if !deleted {
			return nil
		}
		doc.Repositories = append([]models.RepositoryDefinition(nil), out...)
		return s.writeUnlocked(doc)
	})
	return deleted, err
}

func (s *Store) Resolve(repositoryID string) (models.RepositoryDefinition, error) {
	def, err := s.Get(repositoryID)
	if err != nil {
		return models.RepositoryDefinition{}, err
	}
	if def == nil {
		return models.RepositoryDefinition{}, berror.NotFound("Repository not found: "+repositoryID, map[string]any{"repository_id": repositoryID})
	}
	return *def, nil
}

func Summary(def models.RepositoryDefinition) string {
	return fmt.Sprintf("%s (%s, %s)", def.RepositoryID, def.Backend, def.Location)
}
