package backupengine

import (
	"fmt"
	"strings"

	"bkx/internal/backupops"
	"bkx/internal/berror"
	"bkx/internal/models"
	"bkx/internal/resticops"
	"bkx/internal/runstore"
)

type Request struct {
	Engine        string
	Sources       []string
	Destination   *string
	Repository    *string
	CredentialRef *string
	ArchiveName   *string
	Excludes      []string
	Includes      []string
	Tags          []string
	SplitSize     *int64
	Compression   models.CompressionProfile
	Verify        bool
	DryRun        bool
	SkipSymlinks  bool
}

type Plan struct {
	Engine   string          `json:"engine"`
	Command  string          `json:"command"`
	DryRun   bool            `json:"dry_run"`
	Bakery   *backupops.Plan `json:"bakery,omitempty"`
	Snapshot *resticops.Plan `json:"snapshot,omitempty"`
}

type Result struct {
	Success            bool              `json:"success"`
	DryRun             bool              `json:"dry_run"`
	Engine             string            `json:"engine"`
	ArchivePaths       []string          `json:"archive_paths,omitempty"`
	SnapshotID         string            `json:"snapshot_id,omitempty"`
	Repository         string            `json:"repository,omitempty"`
	FilesProcessed     int64             `json:"files_processed"`
	BytesProcessed     int64             `json:"bytes_processed"`
	DataAdded          int64             `json:"data_added,omitempty"`
	VerificationPassed *bool             `json:"verification_passed,omitempty"`
	Warnings           []string          `json:"warnings"`
	Errors             []string          `json:"errors,omitempty"`
	Checksums          map[string]string `json:"checksums,omitempty"`
	DurationMS         int64             `json:"duration_ms"`
}

type Options struct {
	RunStore  *runstore.Store
	RunID     string
	Cancelled func() bool
	Progress  func(stage string, files, bytes int64)
	Restic    resticops.Options
}

func CanonicalEngine(raw string) (string, error) {
	value := strings.ToLower(strings.TrimSpace(raw))
	if value == "" {
		value = models.BackupEngineBakery
	}
	switch value {
	case models.BackupEngineBakery, models.BackupEngineRestic:
		return value, nil
	default:
		return "", berror.Validation("Unknown backup engine", map[string]any{"engine": raw, "supported": []string{models.BackupEngineBakery, models.BackupEngineRestic}})
	}
}

func PlanBackup(req Request) (Plan, error) {
	engine, err := CanonicalEngine(req.Engine)
	if err != nil {
		return Plan{}, err
	}
	switch engine {
	case models.BackupEngineBakery:
		if req.Repository != nil {
			return Plan{}, berror.Validation("--repository is only valid with --engine restic", nil)
		}
		p, err := backupops.PlanBackup(models.BackupSpec{Sources: req.Sources, Destination: req.Destination, ArchiveName: req.ArchiveName, Excludes: req.Excludes, Includes: req.Includes, SplitSize: req.SplitSize, Compression: req.Compression, Verify: req.Verify, DryRun: true, SkipSymlinks: req.SkipSymlinks})
		if err != nil {
			return Plan{}, err
		}
		return Plan{Engine: engine, Command: "backup create", DryRun: true, Bakery: &p}, nil
	case models.BackupEngineRestic:
		if req.Repository == nil || strings.TrimSpace(*req.Repository) == "" {
			return Plan{}, berror.Validation("--repository is required with --engine restic", nil)
		}
		if req.Destination != nil {
			return Plan{}, berror.Validation("--destination is for Bakery archives; use --repository with --engine restic", nil)
		}
		p, err := resticops.PlanBackup(models.SnapshotBackupSpec{Engine: engine, Repository: *req.Repository, CredentialRef: req.CredentialRef, Sources: req.Sources, Excludes: req.Excludes, Includes: req.Includes, Tags: req.Tags, Verify: req.Verify, DryRun: true})
		if err != nil {
			return Plan{}, err
		}
		return Plan{Engine: engine, Command: "backup snapshot", DryRun: true, Snapshot: &p}, nil
	}
	return Plan{}, fmt.Errorf("unreachable backup engine %q", engine)
}

func Create(req Request, options Options) (Result, error) {
	engine, err := CanonicalEngine(req.Engine)
	if err != nil {
		return Result{}, err
	}
	switch engine {
	case models.BackupEngineBakery:
		if req.Repository != nil {
			return Result{}, berror.Validation("--repository is only valid with --engine restic", nil)
		}
		r, err := backupops.Create(models.BackupSpec{Sources: req.Sources, Destination: req.Destination, ArchiveName: req.ArchiveName, Excludes: req.Excludes, Includes: req.Includes, SplitSize: req.SplitSize, Compression: req.Compression, Verify: req.Verify, DryRun: req.DryRun, SkipSymlinks: req.SkipSymlinks}, false, backupops.Options{RunStore: options.RunStore, RunID: options.RunID, Cancelled: options.Cancelled, Progress: func(e backupops.ProgressEvent) {
			if options.Progress != nil {
				options.Progress(e.Stage, e.Files, e.Bytes)
			}
		}})
		if err != nil {
			return Result{}, err
		}
		return Result{Success: r.Success, DryRun: r.DryRun, Engine: engine, ArchivePaths: r.ArchivePaths, FilesProcessed: r.FilesProcessed, BytesProcessed: r.BytesWritten, VerificationPassed: r.VerificationPassed, Warnings: r.Warnings, Errors: r.Errors, Checksums: r.Checksums, DurationMS: r.DurationMS}, nil
	case models.BackupEngineRestic:
		if req.Repository == nil || strings.TrimSpace(*req.Repository) == "" {
			return Result{}, berror.Validation("--repository is required with --engine restic", nil)
		}
		if req.Destination != nil {
			return Result{}, berror.Validation("--destination is for Bakery archives; use --repository with --engine restic", nil)
		}
		ro := options.Restic
		ro.RunStore = options.RunStore
		ro.RunID = options.RunID
		ro.Cancelled = options.Cancelled
		ro.Progress = func(e resticops.ProgressEvent) {
			if options.Progress != nil {
				options.Progress(e.Stage, e.Files, e.Bytes)
			}
		}
		r, err := resticops.Backup(models.SnapshotBackupSpec{Engine: engine, Repository: *req.Repository, CredentialRef: req.CredentialRef, Sources: req.Sources, Excludes: req.Excludes, Includes: req.Includes, Tags: req.Tags, Verify: req.Verify, DryRun: req.DryRun}, ro)
		if err != nil {
			return Result{Success: r.Success, DryRun: req.DryRun, Engine: engine, SnapshotID: r.SnapshotID, Repository: r.Repository, FilesProcessed: r.FilesProcessed, BytesProcessed: r.BytesProcessed, DataAdded: r.DataAdded, VerificationPassed: r.VerificationPassed, Warnings: r.Warnings, DurationMS: r.DurationMS}, err
		}
		return Result{Success: r.Success, DryRun: req.DryRun, Engine: engine, SnapshotID: r.SnapshotID, Repository: r.Repository, FilesProcessed: r.FilesProcessed, BytesProcessed: r.BytesProcessed, DataAdded: r.DataAdded, VerificationPassed: r.VerificationPassed, Warnings: r.Warnings, DurationMS: r.DurationMS}, nil
	}
	return Result{}, fmt.Errorf("unreachable backup engine %q", engine)
}
