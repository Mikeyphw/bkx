package publiccli

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"bkx/internal/archiveio"
	"bkx/internal/backupops"
	"bkx/internal/berror"
	"bkx/internal/fsutil"
	"bkx/internal/models"
	"bkx/internal/transferops"
	"bkx/internal/validation"
)

type planStep struct {
	StepType      string         `json:"step_type"`
	Description   string         `json:"description"`
	Source        *string        `json:"source"`
	Destination   *string        `json:"destination"`
	FilesAffected *int64         `json:"files_affected"`
	BytesAffected *int64         `json:"bytes_affected"`
	Details       map[string]any `json:"details"`
	Warnings      []string       `json:"warnings"`
}

type executionPlan struct {
	Command        string     `json:"command"`
	DryRun         bool       `json:"dry_run"`
	Steps          []planStep `json:"steps"`
	TotalFiles     *int64     `json:"total_files"`
	TotalBytes     *int64     `json:"total_bytes"`
	TimingEstimate string     `json:"timing_estimate"`
	Warnings       []string   `json:"warnings"`
}

func archivePlan(spec models.ArchiveCreateSpec, implicitDefault bool) (executionPlan, error) {
	valid, err := validation.ValidateArchiveCreateSpec(spec)
	if err != nil {
		return executionPlan{}, err
	}
	output, err := validation.CanonicalLocalPath(valid.OutputPath)
	if err != nil {
		return executionPlan{}, err
	}
	profile, warnings, err := archiveio.ResolveCreateProfileForPlan(output, valid.Compression, implicitDefault)
	if err != nil {
		return executionPlan{}, err
	}
	policy := fsutil.SymlinkReject
	if valid.SkipSymlinks {
		policy = fsutil.SymlinkSkip
	}
	manifest, err := fsutil.BuildSourceManifest(valid.Sources, valid.Includes, valid.Excludes, output, policy)
	if err != nil {
		return executionPlan{}, err
	}
	files := int64(len(manifest.Files()))
	bytes := manifest.TotalSize()
	var source *string
	if len(manifest.Roots) == 1 {
		value := manifest.Roots[0]
		source = &value
	}
	destination := output
	split := any(nil)
	if valid.SplitSize != nil {
		split = *valid.SplitSize
	}
	steps := []planStep{{
		StepType: "archive", Description: "Create archive " + filepath.Base(output), Source: source, Destination: &destination,
		FilesAffected: &files, BytesAffected: &bytes,
		Details: map[string]any{"compression": profile.Algorithm, "split_size": split}, Warnings: warnings,
	}}
	if valid.Verify {
		one := int64(1)
		steps = append(steps, planStep{StepType: "verify", Description: "Verify archive integrity", Source: &destination, FilesAffected: &one, Details: map[string]any{}, Warnings: []string{}})
	}
	return executionPlan{Command: "archive create", DryRun: true, Steps: steps, TotalFiles: &files, TotalBytes: &bytes, TimingEstimate: "unknown", Warnings: warnings}, nil
}

func restorePlan(spec models.RestoreSpec) (executionPlan, error) {
	valid, err := validation.ValidateRestoreSpec(spec)
	if err != nil {
		return executionPlan{}, err
	}
	archive, err := validation.CanonicalLocalPath(valid.Archive)
	if err != nil {
		return executionPlan{}, err
	}
	if _, err := os.Stat(archive); err != nil {
		if os.IsNotExist(err) {
			return executionPlan{}, berror.NotFound("Archive not found: "+archive, map[string]any{"path": archive})
		}
		return executionPlan{}, err
	}
	planSpec := valid
	planSpec.Archive = archive
	var destination *string
	if valid.Destination != nil {
		base, err := canonicalOptionalPath(*valid.Destination)
		if err != nil {
			return executionPlan{}, err
		}
		planSpec.Destination = &base
		if valid.FreshDir {
			value := filepath.Join(base, freshPlanRestoreLeaf(archive))
			destination = &value
		} else {
			destination = &base
		}
	} else if valid.FreshDir {
		value, err := filepath.Abs(freshPlanRestoreLeaf(archive))
		if err != nil {
			return executionPlan{}, err
		}
		destination = &value
	}
	preview, err := archiveio.PreviewRestore(planSpec)
	if err != nil {
		return executionPlan{}, err
	}
	selectedFiles := int64(preview.TotalFiles)
	selectedBytes := preview.TotalSize
	if destination == nil && len(preview.Files) > 0 {
		common := filepath.Dir(preview.Files[0].DiskPath)
		destination = &common
	}
	steps := []planStep{}
	if valid.Verify {
		one := int64(1)
		steps = append(steps, planStep{StepType: "verify", Description: "Verify archive before restore", Source: &archive, FilesAffected: &one, Details: map[string]any{}, Warnings: []string{}})
	}
	requiresConfirmation := preview.ExistingFiles > 0 || (destination != nil && pathExistsPublicCLI(*destination))
	steps = append(steps, planStep{
		StepType: "restore", Description: "Extract from " + filepath.Base(archive), Source: &archive, Destination: destination,
		FilesAffected: &selectedFiles, BytesAffected: &selectedBytes,
		Details: map[string]any{
			"full_restore": len(valid.Paths) == 0, "selected_paths": valid.Paths, "overwrite": valid.Overwrite,
			"fresh_dir": valid.FreshDir, "confirm_overwrite": valid.ConfirmOverwrite, "requires_confirmation": requiresConfirmation,
			"existing_files": preview.ExistingFiles,
		}, Warnings: preview.Warnings,
	})
	return executionPlan{Command: "restore extract", DryRun: true, Steps: steps, TotalFiles: &selectedFiles, TotalBytes: &selectedBytes, TimingEstimate: "unknown", Warnings: preview.Warnings}, nil
}

func freshPlanRestoreLeaf(archive string) string {
	stem := filepath.Base(archive)
	for _, suffix := range []string{".tar.gz", ".tar.bz2", ".tar.xz", ".tar", ".zip", ".bakery"} {
		if strings.HasSuffix(stem, suffix) && len(stem) > len(suffix) {
			stem = strings.TrimSuffix(stem, suffix)
			break
		}
	}
	stem = strings.Trim(stem, " .")
	if stem == "" {
		stem = "archive"
	}
	return "restored_" + stem + "_" + strconv.FormatInt(time.Now().UnixNano(), 10)
}

func pathExistsPublicCLI(path string) bool {
	_, err := os.Lstat(path)
	return err == nil
}

func backupPlan(spec models.BackupSpec, command string) (backupops.Plan, error) {
	plan, err := backupops.PlanBackup(spec)
	if err == nil {
		plan.Command = command
	}
	return plan, err
}

func transferPlan(spec models.TransferSpec, command string) (transferops.Plan, error) {
	plan, err := transferops.PlanTransfer(spec)
	if err == nil {
		plan.Command = command
	}
	return plan, err
}
