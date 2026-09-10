package models

import (
	"fmt"
	"regexp"
	"strings"
	"time"
)

const (
	RepositoryDefinitionSchemaVersion   = 1
	RepositoryBackendLocal              = "local"
	RepositoryBackendRclone             = "rclone"
	RepositoryBackendManagedGoogleDrive = "managed_google_drive"
	BKXGoogleDriveRemoteName            = "bkx-google-drive"
)

var repositoryIDPattern = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,127}$`)

// RepositoryDefinition is a non-secret, named Restic repository descriptor.
// Location identifies storage; CredentialRef identifies the Restic repository
// key in a platform-specific secret store. Cloud provider credentials never
// belong in this document (rclone owns those separately).
type RepositoryDefinition struct {
	SchemaVersion int              `json:"schema_version"`
	RepositoryID  string           `json:"repository_id"`
	Name          string           `json:"name"`
	Engine        string           `json:"engine"`
	Backend       string           `json:"backend"`
	Location      string           `json:"location"`
	CredentialRef *string          `json:"credential_ref,omitempty"`
	ManagedByBKX  bool             `json:"managed_by_bkx"`
	CreatedAt     string           `json:"created_at"`
	UpdatedAt     string           `json:"updated_at"`
	Retention     *RetentionPolicy `json:"retention,omitempty"`
}

func ValidateRepositoryDefinition(def RepositoryDefinition) error {
	if def.SchemaVersion != RepositoryDefinitionSchemaVersion {
		return fmt.Errorf("repository schema_version must be %d", RepositoryDefinitionSchemaVersion)
	}
	if !repositoryIDPattern.MatchString(def.RepositoryID) {
		return fmt.Errorf("repository_id must contain only lower-case letters, numbers, dot, underscore, or dash")
	}
	if strings.TrimSpace(def.Name) == "" || strings.TrimSpace(def.Name) != def.Name || len(def.Name) > 120 {
		return fmt.Errorf("repository name must be trimmed, non-empty, and at most 120 characters")
	}
	if def.Engine != BackupEngineRestic {
		return fmt.Errorf("repository engine must be %q", BackupEngineRestic)
	}
	switch def.Backend {
	case RepositoryBackendLocal, RepositoryBackendRclone, RepositoryBackendManagedGoogleDrive:
	default:
		return fmt.Errorf("unsupported repository backend %q", def.Backend)
	}
	if strings.TrimSpace(def.Location) == "" || strings.TrimSpace(def.Location) != def.Location {
		return fmt.Errorf("repository location must be trimmed and non-empty")
	}
	if def.CredentialRef != nil {
		if !referenceIDPattern.MatchString(*def.CredentialRef) {
			return fmt.Errorf("credential_ref must be a non-secret stable reference id")
		}
	}
	if def.Backend == RepositoryBackendManagedGoogleDrive && !def.ManagedByBKX {
		return fmt.Errorf("managed_google_drive repositories must be marked managed_by_bkx")
	}
	if def.Backend != RepositoryBackendManagedGoogleDrive && def.ManagedByBKX {
		return fmt.Errorf("managed_by_bkx is reserved for managed Google Drive repositories")
	}
	created, err := time.Parse(time.RFC3339Nano, def.CreatedAt)
	if err != nil {
		return fmt.Errorf("created_at must be RFC3339: %w", err)
	}
	updated, err := time.Parse(time.RFC3339Nano, def.UpdatedAt)
	if err != nil {
		return fmt.Errorf("updated_at must be RFC3339: %w", err)
	}
	if updated.Before(created) {
		return fmt.Errorf("updated_at must not be before created_at")
	}
	if def.Retention != nil {
		if err := ValidateRetentionPolicy(*def.Retention); err != nil {
			return fmt.Errorf("invalid retention policy: %w", err)
		}
	}
	return nil
}
