package services

import (
	"encoding/json"
	"fmt"
	"path/filepath"
	"strings"

	"bkx/internal/backupops"
	"bkx/internal/berror"
	"bkx/internal/models"
	"bkx/internal/resticops"
	"bkx/internal/transferops"
)

func savedJobArtifact(job models.SavedJob) Artifact {
	raw, _ := json.Marshal(job)
	return Artifact{
		Label: "saved_job",
		Path:  job.JobID,
		Metadata: map[string]string{
			"job_id":         job.JobID,
			"name":           job.Name,
			"revision":       fmt.Sprintf("%d", job.Revision),
			"enabled":        fmt.Sprintf("%t", job.Enabled),
			"operation":      job.Operation.Operation,
			"saved_job_json": string(raw),
		},
	}
}

func (d *Dispatcher) jobsList() (Result, error) {
	if d.Jobs == nil {
		return Result{}, berror.Validation("saved-job store is unavailable", nil)
	}
	jobs, err := d.Jobs.List()
	if err != nil {
		return Result{}, err
	}
	artifacts := make([]Artifact, 0, len(jobs))
	for _, job := range jobs {
		artifacts = append(artifacts, savedJobArtifact(job))
	}
	return success(fmt.Sprintf("%d saved jobs.", len(jobs)), nil, artifacts), nil
}

func (d *Dispatcher) jobsShow(p map[string]any) (Result, error) {
	job, err := d.resolveSavedJob(p)
	if err != nil {
		return Result{}, err
	}
	return success("Saved job "+job.JobID+".", nil, []Artifact{savedJobArtifact(*job)}), nil
}

func (d *Dispatcher) jobsCreate(p map[string]any) (Result, error) {
	if d.Jobs == nil {
		return Result{}, berror.Validation("saved-job store is unavailable", nil)
	}
	job, err := savedJobFromPayload(p)
	if err != nil {
		return Result{}, err
	}
	created, err := d.Jobs.Create(job)
	if err != nil {
		return Result{}, err
	}
	return success("Saved job "+created.JobID+" created.", nil, []Artifact{savedJobArtifact(created)}), nil
}

func (d *Dispatcher) jobsUpdate(p map[string]any) (Result, error) {
	if d.Jobs == nil {
		return Result{}, berror.Validation("saved-job store is unavailable", nil)
	}
	jobID, err := requiredString(p, "job_id")
	if err != nil {
		return Result{}, err
	}
	expected, err := int64Value(p["expected_revision"])
	if err != nil || expected < 1 {
		return Result{}, berror.Validation("expected_revision must be a positive integer", map[string]any{"job_id": jobID})
	}
	job, err := savedJobFromPayload(p)
	if err != nil {
		return Result{}, err
	}
	job.JobID = jobID
	updated, err := d.Jobs.Update(jobID, expected, job)
	if err != nil {
		return Result{}, err
	}
	return success("Saved job "+updated.JobID+" updated.", nil, []Artifact{savedJobArtifact(updated)}), nil
}

func (d *Dispatcher) jobsClone(p map[string]any) (Result, error) {
	if d.Jobs == nil {
		return Result{}, berror.Validation("saved-job store is unavailable", nil)
	}
	sourceID, err := requiredString(p, "source_job_id")
	if err != nil {
		return Result{}, err
	}
	targetID, err := requiredString(p, "target_job_id")
	if err != nil {
		return Result{}, err
	}
	source, err := d.Jobs.Get(sourceID)
	if err != nil {
		return Result{}, err
	}
	if source == nil {
		return Result{}, berror.NotFound("Saved job not found: "+sourceID, map[string]any{"job_id": sourceID})
	}
	clone := *source
	clone.JobID = targetID
	clone.Name = optionalString(p["name"])
	if clone.Name == "" {
		clone.Name = targetID
	}
	clone.Revision = 0
	clone.CreatedAt = ""
	clone.UpdatedAt = ""
	created, err := d.Jobs.Create(clone)
	if err != nil {
		return Result{}, err
	}
	return success("Saved job "+sourceID+" cloned to "+targetID+".", nil, []Artifact{savedJobArtifact(created)}), nil
}

func (d *Dispatcher) jobsDelete(p map[string]any) (Result, error) {
	if d.Jobs == nil {
		return Result{}, berror.Validation("saved-job store is unavailable", nil)
	}
	jobID, err := requiredString(p, "job_id")
	if err != nil {
		return Result{}, err
	}
	deleted, err := d.Jobs.Delete(jobID)
	if err != nil {
		return Result{}, err
	}
	if !deleted {
		return Result{}, berror.NotFound("Saved job not found: "+jobID, map[string]any{"job_id": jobID})
	}
	if d.Schedules != nil {
		schedules, scheduleErr := d.Schedules.List()
		if scheduleErr != nil {
			return Result{}, scheduleErr
		}
		for _, schedule := range schedules {
			if schedule.JobID == jobID {
				if _, scheduleErr := d.Schedules.Delete(schedule.ScheduleID); scheduleErr != nil {
					return Result{}, scheduleErr
				}
			}
		}
	}
	return success("Saved job "+jobID+" deleted.", nil, []Artifact{{Label: "saved_job_deleted", Path: jobID}}), nil
}

func (d *Dispatcher) resolveSavedJob(p map[string]any) (*models.SavedJob, error) {
	if d.Jobs == nil {
		return nil, berror.Validation("saved-job store is unavailable", nil)
	}
	jobID, err := requiredString(p, "job_id")
	if err != nil {
		return nil, err
	}
	job, err := d.Jobs.Get(jobID)
	if err != nil {
		return nil, err
	}
	if job == nil {
		return nil, berror.NotFound("Saved job not found: "+jobID, map[string]any{"job_id": jobID})
	}
	return job, nil
}

func savedJobFromPayload(p map[string]any) (models.SavedJob, error) {
	raw, err := json.Marshal(p)
	if err != nil {
		return models.SavedJob{}, berror.Validation("saved job payload is not JSON serializable", nil)
	}
	var input struct {
		JobID       string                 `json:"job_id"`
		Name        string                 `json:"name"`
		Description string                 `json:"description"`
		Tags        []string               `json:"tags"`
		Enabled     *bool                  `json:"enabled"`
		Operation   models.OperationSpecV2 `json:"operation"`
	}
	if err := json.Unmarshal(raw, &input); err != nil {
		return models.SavedJob{}, berror.Validation("invalid saved job payload", map[string]any{"reason": err.Error()})
	}
	enabled := true
	if input.Enabled != nil {
		enabled = *input.Enabled
	}
	if input.Operation.SchemaVersion == 0 {
		input.Operation.SchemaVersion = models.OperationSpecV2SchemaVersion
	}
	if input.Operation.Parameters == nil {
		input.Operation.Parameters = map[string]any{}
	}
	job := models.SavedJob{
		SchemaVersion: models.SavedJobSchemaVersion,
		JobID:         strings.TrimSpace(input.JobID),
		Name:          strings.TrimSpace(input.Name),
		Description:   strings.TrimSpace(input.Description),
		Tags:          input.Tags,
		Revision:      1,
		Enabled:       enabled,
		Operation:     input.Operation,
		CreatedAt:     "2000-01-01T00:00:00Z",
		UpdatedAt:     "2000-01-01T00:00:00Z",
	}
	if err := models.ValidateSavedJob(job); err != nil {
		return models.SavedJob{}, berror.Validation("invalid saved job", map[string]any{"reason": err.Error()})
	}
	// Store.Create/Update owns revision and timestamps; placeholders above exist
	// only so validation can reject bad UI payloads before persistence.
	job.CreatedAt = ""
	job.UpdatedAt = ""
	return job, nil
}

func (d *Dispatcher) jobsExecute(ctx OperationContext, p map[string]any, plan bool) (Result, error) {
	job, err := d.resolveSavedJob(p)
	if err != nil {
		return Result{}, err
	}
	if !plan && !job.Enabled {
		return Result{}, berror.Validation("Saved job is disabled", map[string]any{"job_id": job.JobID})
	}
	operation := job.Operation
	if rawOverrides, ok := p["overrides"]; ok && rawOverrides != nil {
		overrides, err := executionOverrideStrings(rawOverrides)
		if err != nil {
			return Result{}, berror.Validation("saved-job overrides must be a list of KEY=VALUE strings", nil)
		}
		params, err := cloneParameters(operation.Parameters)
		if err != nil {
			return Result{}, err
		}
		if err := applyExecutionOverrides(params, overrides); err != nil {
			return Result{}, err
		}
		operation.Parameters = params
		if err := models.ValidateOperationSpecV2(operation); err != nil {
			return Result{}, berror.Validation("invalid saved-job execution override", map[string]any{"reason": err.Error()})
		}
	}
	emit(ctx, "preparing", "Preparing saved job "+job.Name+".", floatPtr(.02))
	return d.executeSavedOperation(ctx, operation, plan)
}

func (d *Dispatcher) executeSavedOperation(ctx OperationContext, operation models.OperationSpecV2, plan bool) (Result, error) {
	raw, err := json.Marshal(operation.Parameters)
	if err != nil {
		return Result{}, berror.Validation("saved job parameters are not JSON serializable", nil)
	}
	switch operation.Operation {
	case "backup.create":
		var spec models.BackupSpec
		if err := json.Unmarshal(raw, &spec); err != nil {
			return Result{}, berror.Validation("invalid backup job parameters", map[string]any{"reason": err.Error()})
		}
		if plan {
			spec.DryRun = true
			p, err := backupops.PlanBackup(spec)
			if err != nil {
				return Result{}, err
			}
			result := success(fmt.Sprintf("Backup plan ready: %d files, %d bytes.", p.TotalFiles, p.TotalBytes), nil, nil)
			result.Metrics = &ResultMetrics{FilesTotal: int64Ptr(p.TotalFiles), BytesTotal: int64Ptr(p.TotalBytes)}
			return result, nil
		}
		spec.DryRun = false
		r, err := backupops.Create(spec, false, backupops.Options{RunStore: d.RunStore, RunID: requestID(ctx), Cancelled: ctx.Cancelled, Progress: func(e backupops.ProgressEvent) {
			emitDetailed(ctx, e.Stage, fmt.Sprintf("%s: %d files, %d bytes.", title(e.Stage), e.Files, e.Bytes), nil, int64Ptr(e.Files), nil, int64Ptr(e.Bytes), nil)
		}})
		if err != nil {
			return Result{}, err
		}
		issues := []Issue{}
		for _, w := range r.Warnings {
			issues = append(issues, warningIssue("backup_warning", w))
		}
		for _, e := range r.Errors {
			issues = append(issues, errorIssue("backup_error", e))
		}
		artifacts := []Artifact{}
		for _, path := range r.ArchivePaths {
			digest := r.Checksums[filepath.Base(path)]
			artifacts = append(artifacts, artifact("backup_archive", path, digest))
		}
		return Result{Status: map[bool]string{true: StatusSuccess, false: "failure"}[r.Success], Summary: fmt.Sprintf("Saved backup completed: %d files, %d bytes.", r.FilesProcessed, r.BytesWritten), Errors: issues, Artifacts: artifacts}, nil

	case "backup.snapshot":
		var spec models.SnapshotBackupSpec
		if err := json.Unmarshal(raw, &spec); err != nil {
			return Result{}, berror.Validation("invalid snapshot job parameters", map[string]any{"reason": err.Error()})
		}
		if spec.Engine == "" {
			spec.Engine = models.BackupEngineRestic
		}
		if operation.RepositoryRef != nil {
			if d.Repositories == nil {
				return Result{}, berror.Validation("named repository store is unavailable", nil)
			}
			def, err := d.Repositories.Resolve(*operation.RepositoryRef)
			if err != nil {
				return Result{}, err
			}
			if spec.Repository != "" && strings.TrimSpace(spec.Repository) != def.Location {
				return Result{}, berror.Validation("Saved snapshot job contains conflicting repository location", map[string]any{"repository_ref": *operation.RepositoryRef})
			}
			spec.Repository = def.Location
			if spec.CredentialRef == nil {
				spec.CredentialRef = def.CredentialRef
			}
		}
		if spec.CredentialRef == nil && len(operation.CredentialRefs) > 0 {
			ref := operation.CredentialRefs[0].ID
			spec.CredentialRef = &ref
		}
		if plan {
			spec.DryRun = true
			p, err := resticops.PlanBackup(spec)
			if err != nil {
				return Result{}, err
			}
			return success("Incremental snapshot plan ready for "+p.Repository+".", nil, nil), nil
		}
		spec.DryRun = false
		r, err := resticops.Backup(spec, resticops.Options{RunStore: d.RunStore, RunID: requestID(ctx), Cancelled: ctx.Cancelled, Progress: func(e resticops.ProgressEvent) {
			emitDetailed(ctx, e.Stage, fmt.Sprintf("%s: %d files, %d bytes.", title(e.Stage), e.Files, e.Bytes), nil, int64Ptr(e.Files), nil, int64Ptr(e.Bytes), nil)
		}})
		if err != nil {
			return Result{}, err
		}
		issues := []Issue{}
		for _, w := range r.Warnings {
			issues = append(issues, warningIssue("snapshot_warning", w))
		}
		artifacts := []Artifact{}
		if r.SnapshotID != "" {
			artifacts = append(artifacts, artifact("restic_snapshot", "restic://"+r.Repository+"/snapshots/"+r.SnapshotID, ""))
		}
		result := success("Restic snapshot "+r.SnapshotID+" completed.", issues, artifacts)
		result.Metrics = &ResultMetrics{FilesTotal: int64Ptr(r.FilesProcessed), BytesTotal: int64Ptr(r.BytesProcessed)}
		return result, nil

	case "transfer.run":
		var spec models.TransferSpec
		if err := json.Unmarshal(raw, &spec); err != nil {
			return Result{}, berror.Validation("invalid transfer job parameters", map[string]any{"reason": err.Error()})
		}
		if plan {
			spec.DryRun = true
			p, err := transferops.PlanTransfer(spec)
			if err != nil {
				return Result{}, err
			}
			result := success(fmt.Sprintf("Transfer plan ready: %d files, %d bytes.", p.TotalFiles, p.TotalBytes), nil, nil)
			result.Metrics = &ResultMetrics{FilesTotal: int64Ptr(p.TotalFiles), BytesTotal: int64Ptr(p.TotalBytes)}
			return result, nil
		}
		spec.DryRun = false
		r, err := transferops.Run(spec, transferops.Options{RunStore: d.RunStore, RunID: requestID(ctx), Cancelled: ctx.Cancelled, Progress: func(e transferops.ProgressEvent) {
			emitDetailed(ctx, "transferring", "Running saved transfer.", nil, int64Ptr(e.FilesTransferred), int64Ptr(e.TotalFiles), int64Ptr(e.BytesTransferred), int64Ptr(e.TotalBytes))
		}})
		if err != nil {
			return Result{}, err
		}
		issues := []Issue{}
		for _, w := range r.Warnings {
			issues = append(issues, warningIssue("transfer_warning", w))
		}
		for _, e := range r.Errors {
			issues = append(issues, errorIssue("transfer_error", e))
		}
		return Result{Status: map[bool]string{true: StatusSuccess, false: "failure"}[r.Success], Summary: fmt.Sprintf("Saved transfer completed: %d files, %d bytes.", r.FilesTransferred, r.BytesTransferred), Errors: issues, Artifacts: []Artifact{}}, nil
	}

	params := clonePayload(operation.Parameters)
	if operation.RepositoryRef != nil {
		params["repository_ref"] = *operation.RepositoryRef
	}
	if len(operation.CredentialRefs) > 0 {
		if _, exists := params["credential_ref"]; !exists {
			params["credential_ref"] = operation.CredentialRefs[0].ID
		}
	}
	target := operation.Operation
	if plan {
		switch target {
		case "backup.create":
			target = "backup.plan"
		case "transfer.run":
			target = "transfer.plan"
		default:
			return Result{}, berror.Validation("saved operation does not support planning", map[string]any{"operation": operation.Operation})
		}
	}
	encoded, _ := json.Marshal(params)
	return d.Dispatch(ctx, target, encoded)
}

func clonePayload(in map[string]any) map[string]any {
	raw, _ := json.Marshal(in)
	out := map[string]any{}
	_ = json.Unmarshal(raw, &out)
	return out
}

func cloneParameters(src map[string]any) (map[string]any, error) {
	raw, err := json.Marshal(src)
	if err != nil {
		return nil, berror.Validation("saved job parameters are not JSON serializable", nil)
	}
	var out map[string]any
	dec := json.NewDecoder(strings.NewReader(string(raw)))
	dec.UseNumber()
	if err := dec.Decode(&out); err != nil {
		return nil, err
	}
	if out == nil {
		out = map[string]any{}
	}
	return out, nil
}

func applyExecutionOverrides(params map[string]any, values []string) error {
	for _, raw := range values {
		pair := strings.SplitN(raw, "=", 2)
		if len(pair) != 2 || strings.TrimSpace(pair[0]) == "" {
			return berror.Validation("execution override must use KEY=VALUE", map[string]any{"value": raw})
		}
		keys := strings.Split(strings.TrimSpace(pair[0]), ".")
		for _, key := range keys {
			if strings.TrimSpace(key) == "" {
				return berror.Validation("execution override path contains an empty segment", map[string]any{"value": raw})
			}
		}
		var value any
		dec := json.NewDecoder(strings.NewReader(pair[1]))
		dec.UseNumber()
		if err := dec.Decode(&value); err != nil {
			value = pair[1]
		}
		current := params
		for _, key := range keys[:len(keys)-1] {
			next, ok := current[key].(map[string]any)
			if !ok {
				next = map[string]any{}
				current[key] = next
			}
			current = next
		}
		current[keys[len(keys)-1]] = value
	}
	return nil
}

func executionOverrideStrings(value any) ([]string, error) {
	items, ok := value.([]any)
	if !ok {
		return nil, fmt.Errorf("expected list")
	}
	out := make([]string, 0, len(items))
	for _, item := range items {
		text, ok := item.(string)
		if !ok {
			return nil, fmt.Errorf("expected string")
		}
		out = append(out, text)
	}
	return out, nil
}
