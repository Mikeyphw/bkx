package models

// GO03 keeps JSON names aligned with the frozen Python contracts. These structs
// are shared production models and are validated against the immutable v1 compatibility fixtures.

type CompressionProfile struct {
	Algorithm string `json:"algorithm"`
	Level     int    `json:"level"`
}

type BackupSpec struct {
	ArchiveName  *string            `json:"archive_name"`
	Compression  CompressionProfile `json:"compression"`
	Destination  *string            `json:"destination"`
	DryRun       bool               `json:"dry_run"`
	Excludes     []string           `json:"excludes"`
	Includes     []string           `json:"includes"`
	Sources      []string           `json:"sources"`
	SplitSize    *int64             `json:"split_size"`
	SkipSymlinks bool               `json:"skip_symlinks"`
	Verify       bool               `json:"verify"`
}

type ArchiveCreateSpec struct {
	Compression  CompressionProfile `json:"compression"`
	DryRun       bool               `json:"dry_run"`
	Excludes     []string           `json:"excludes"`
	Includes     []string           `json:"includes"`
	OutputPath   string             `json:"output_path"`
	Sources      []string           `json:"sources"`
	SplitSize    *int64             `json:"split_size"`
	SkipSymlinks bool               `json:"skip_symlinks"`
	Verify       bool               `json:"verify"`
}

type RestoreSpec struct {
	Archive          string   `json:"archive"`
	ConfirmOverwrite bool     `json:"confirm_overwrite"`
	Destination      *string  `json:"destination"`
	DryRun           bool     `json:"dry_run"`
	FreshDir         bool     `json:"fresh_dir"`
	Overwrite        bool     `json:"overwrite"`
	Paths            []string `json:"paths"`
	Verify           bool     `json:"verify"`
}

type TransferSpec struct {
	Checksum               *string  `json:"checksum"`
	Destination            string   `json:"destination"`
	Direction              string   `json:"direction"`
	DryRun                 bool     `json:"dry_run"`
	Overwrite              bool     `json:"overwrite"`
	Sources                []string `json:"sources"`
	Verify                 bool     `json:"verify"`
	DeleteExtraneousOnSync bool     `json:"delete_extraneous_on_sync,omitempty"`
	SyncDeleteLimit        int      `json:"sync_delete_limit,omitempty"`
}

type RunRecord struct {
	ArchivePath           *string          `json:"archive_path"`
	BytesProcessed        *int64           `json:"bytes_processed"`
	CompletedAt           *string          `json:"completed_at"`
	DestinationPath       *string          `json:"destination_path"`
	ErrorMessage          *string          `json:"error_message"`
	FilesCount            *int64           `json:"files_count"`
	Operation             string           `json:"operation"`
	RunID                 string           `json:"run_id"`
	SourcePaths           []string         `json:"source_paths"`
	SpecSnapshot          map[string]any   `json:"spec_snapshot"`
	StartedAt             string           `json:"started_at"`
	Status                string           `json:"status"`
	Phase                 string           `json:"phase,omitempty"`
	JobID                 string           `json:"job_id,omitempty"`
	JobRevision           int64            `json:"job_revision,omitempty"`
	OperationSpecSnapshot *OperationSpecV2 `json:"operation_spec_snapshot,omitempty"`
}
