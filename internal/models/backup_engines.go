package models

import (
	"fmt"
	"strings"
)

const (
	BackupEngineBakery = "bakery"
	BackupEngineRestic = "restic"
)

// SnapshotBackupSpec is the V2 incremental backup wire model. It intentionally
// keeps repository credentials out of the operation document; CredentialRef is
// only a stable key resolved by the caller/runtime secure store.
type SnapshotBackupSpec struct {
	Engine        string   `json:"engine"`
	Repository    string   `json:"repository"`
	CredentialRef *string  `json:"credential_ref,omitempty"`
	Sources       []string `json:"sources"`
	Excludes      []string `json:"excludes,omitempty"`
	Includes      []string `json:"includes,omitempty"`
	Tags          []string `json:"tags,omitempty"`
	Verify        bool     `json:"verify"`
	DryRun        bool     `json:"dry_run"`
}

func ValidateSnapshotBackupSpec(spec SnapshotBackupSpec) error {
	if strings.TrimSpace(spec.Engine) == "" {
		spec.Engine = BackupEngineRestic
	}
	if spec.Engine != BackupEngineRestic {
		return fmt.Errorf("snapshot backup engine must be %q", BackupEngineRestic)
	}
	if strings.TrimSpace(spec.Repository) == "" {
		return fmt.Errorf("snapshot backup repository must not be empty")
	}
	if len(spec.Sources) == 0 {
		return fmt.Errorf("snapshot backup requires at least one source")
	}
	for _, source := range spec.Sources {
		if strings.TrimSpace(source) == "" {
			return fmt.Errorf("snapshot backup sources must not contain empty values")
		}
	}
	if spec.CredentialRef != nil {
		if err := ValidateCredentialRef(*spec.CredentialRef); err != nil {
			return err
		}
	}
	return nil
}

type RepositoryInitSpec struct {
	Engine        string  `json:"engine"`
	Repository    string  `json:"repository"`
	CredentialRef *string `json:"credential_ref,omitempty"`
}

func ValidateRepositoryInitSpec(spec RepositoryInitSpec) error {
	if spec.Engine != BackupEngineRestic {
		return fmt.Errorf("repository init engine must be %q", BackupEngineRestic)
	}
	if strings.TrimSpace(spec.Repository) == "" {
		return fmt.Errorf("repository must not be empty")
	}
	if spec.CredentialRef != nil {
		if err := ValidateCredentialRef(*spec.CredentialRef); err != nil {
			return err
		}
	}
	return nil
}
