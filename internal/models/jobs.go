package models

import (
	"encoding/json"
	"fmt"
	"regexp"
	"sort"
	"strings"
	"time"
	"unicode"
)

const (
	OperationSpecV2SchemaVersion = 2
	SavedJobSchemaVersion        = 2
)

var (
	operationIDPattern = regexp.MustCompile(`^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$`)
	jobIDPattern       = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,127}$`)
	referenceIDPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$`)
)

// SecretReference points at credentials stored by a platform-specific secure store.
// Saved jobs intentionally never contain the credential value itself.
type SecretReference struct {
	ID      string `json:"id"`
	Purpose string `json:"purpose,omitempty"`
}

// OperationSpecV2 is the stable cross-client wire contract used by saved jobs.
// Parameters are deliberately extensible so CLI, Android and Tasker can share a
// single job schema while operation-specific typed models evolve independently.
type OperationSpecV2 struct {
	SchemaVersion  int               `json:"schema_version"`
	Operation      string            `json:"operation"`
	Parameters     map[string]any    `json:"parameters"`
	RepositoryRef  *string           `json:"repository_ref,omitempty"`
	CredentialRefs []SecretReference `json:"credential_refs,omitempty"`
}

// SavedJob is a revisioned reusable operation. Revision is monotonic per JobID.
type SavedJob struct {
	SchemaVersion int             `json:"schema_version"`
	JobID         string          `json:"job_id"`
	Name          string          `json:"name"`
	Description   string          `json:"description,omitempty"`
	Tags          []string        `json:"tags,omitempty"`
	Revision      int64           `json:"revision"`
	Enabled       bool            `json:"enabled"`
	Operation     OperationSpecV2 `json:"operation"`
	CreatedAt     string          `json:"created_at"`
	UpdatedAt     string          `json:"updated_at"`
}

func NewOperationSpecV2(operation string, parameters map[string]any) OperationSpecV2 {
	if parameters == nil {
		parameters = map[string]any{}
	}
	return OperationSpecV2{
		SchemaVersion: OperationSpecV2SchemaVersion,
		Operation:     operation,
		Parameters:    parameters,
	}
}

func ValidateOperationSpecV2(spec OperationSpecV2) error {
	if spec.SchemaVersion != OperationSpecV2SchemaVersion {
		return fmt.Errorf("operation spec schema_version must be %d", OperationSpecV2SchemaVersion)
	}
	if !operationIDPattern.MatchString(spec.Operation) {
		return fmt.Errorf("operation must be a stable lower-case operation id")
	}
	if spec.Parameters == nil {
		return fmt.Errorf("operation parameters must not be null")
	}
	if _, err := json.Marshal(spec.Parameters); err != nil {
		return fmt.Errorf("operation parameters must be JSON serializable: %w", err)
	}
	if err := rejectInlineSecrets(spec.Parameters, "parameters"); err != nil {
		return err
	}
	if spec.RepositoryRef != nil {
		if !referenceIDPattern.MatchString(*spec.RepositoryRef) {
			return fmt.Errorf("repository_ref must be a non-secret stable reference id")
		}
	}
	seen := map[string]bool{}
	for _, ref := range spec.CredentialRefs {
		if !referenceIDPattern.MatchString(ref.ID) {
			return fmt.Errorf("credential reference id must be a non-secret stable reference id")
		}
		if seen[ref.ID] {
			return fmt.Errorf("duplicate credential reference id %q", ref.ID)
		}
		seen[ref.ID] = true
		if strings.TrimSpace(ref.Purpose) != ref.Purpose || len(ref.Purpose) > 160 {
			return fmt.Errorf("credential reference purpose must be trimmed and at most 160 characters")
		}
	}
	return nil
}

func ValidateSavedJob(job SavedJob) error {
	if job.SchemaVersion != SavedJobSchemaVersion {
		return fmt.Errorf("saved job schema_version must be %d", SavedJobSchemaVersion)
	}
	if !jobIDPattern.MatchString(job.JobID) {
		return fmt.Errorf("job_id must contain only lower-case letters, numbers, dot, underscore, or dash")
	}
	if strings.TrimSpace(job.Name) == "" || strings.TrimSpace(job.Name) != job.Name || len(job.Name) > 120 {
		return fmt.Errorf("job name must be trimmed, non-empty, and at most 120 characters")
	}
	if strings.TrimSpace(job.Description) != job.Description || len(job.Description) > 500 {
		return fmt.Errorf("job description must be trimmed and at most 500 characters")
	}
	if job.Revision < 1 {
		return fmt.Errorf("job revision must be at least 1")
	}
	if err := ValidateOperationSpecV2(job.Operation); err != nil {
		return fmt.Errorf("invalid saved job operation: %w", err)
	}
	if err := validateTags(job.Tags); err != nil {
		return err
	}
	created, err := time.Parse(time.RFC3339Nano, job.CreatedAt)
	if err != nil {
		return fmt.Errorf("created_at must be RFC3339: %w", err)
	}
	updated, err := time.Parse(time.RFC3339Nano, job.UpdatedAt)
	if err != nil {
		return fmt.Errorf("updated_at must be RFC3339: %w", err)
	}
	if updated.Before(created) {
		return fmt.Errorf("updated_at must not be before created_at")
	}
	return nil
}

func validateTags(tags []string) error {
	seen := map[string]bool{}
	for _, tag := range tags {
		if strings.TrimSpace(tag) == "" || strings.TrimSpace(tag) != tag || len(tag) > 64 {
			return fmt.Errorf("job tags must be trimmed, non-empty, and at most 64 characters")
		}
		if seen[tag] {
			return fmt.Errorf("duplicate job tag %q", tag)
		}
		seen[tag] = true
	}
	return nil
}

var forbiddenSecretKeys = map[string]bool{
	"password": true, "passwd": true, "passphrase": true,
	"secret": true, "clientsecret": true, "privatekey": true,
	"token": true, "accesstoken": true, "refreshtoken": true,
	"authorization": true, "apikey": true, "credential": true, "credentials": true,
}

func normalizedSecretKey(key string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(key) {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func rejectInlineSecrets(value any, path string) error {
	switch typed := value.(type) {
	case map[string]any:
		keys := make([]string, 0, len(typed))
		for key := range typed {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		for _, key := range keys {
			if forbiddenSecretKeys[normalizedSecretKey(key)] {
				return fmt.Errorf("saved operation parameters must not contain inline secret field %q; use credential_refs", path+"."+key)
			}
			if err := rejectInlineSecrets(typed[key], path+"."+key); err != nil {
				return err
			}
		}
	case []any:
		for i, item := range typed {
			if err := rejectInlineSecrets(item, fmt.Sprintf("%s[%d]", path, i)); err != nil {
				return err
			}
		}
	case json.RawMessage:
		var decoded any
		if err := json.Unmarshal(typed, &decoded); err != nil {
			return fmt.Errorf("invalid JSON at %s: %w", path, err)
		}
		return rejectInlineSecrets(decoded, path)
	}
	return nil
}
