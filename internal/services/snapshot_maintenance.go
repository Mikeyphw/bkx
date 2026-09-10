package services

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"

	"bkx/internal/berror"
	"bkx/internal/models"
	"bkx/internal/resticops"
)

func (d *Dispatcher) repositoryAccess(p map[string]any) (models.RepositoryAccessSpec, string, error) {
	repository := optionalString(p["repository"])
	repositoryRef := optionalString(p["repository_ref"])
	if repository != "" && repositoryRef != "" {
		return models.RepositoryAccessSpec{}, "", berror.Validation("use repository or repository_ref, not both", nil)
	}
	var credentialRef *string
	if repositoryRef != "" {
		if d.Repositories == nil {
			return models.RepositoryAccessSpec{}, "", berror.Validation("named repository store is unavailable", nil)
		}
		def, err := d.Repositories.Resolve(repositoryRef)
		if err != nil {
			return models.RepositoryAccessSpec{}, "", err
		}
		repository = def.Location
		credentialRef = def.CredentialRef
	}
	if repository == "" {
		return models.RepositoryAccessSpec{}, "", berror.Validation("repository or repository_ref is required", nil)
	}
	if raw := optionalString(p["credential_ref"]); raw != "" {
		credentialRef = &raw
	}
	return models.RepositoryAccessSpec{Repository: repository, CredentialRef: credentialRef}, repositoryRef, nil
}

func snapshotArtifact(repositoryRef, repository string, item models.SnapshotDescriptor) Artifact {
	meta := map[string]string{"snapshot_id": item.ID, "short_id": item.ShortID, "time": item.Time, "hostname": item.Hostname, "username": item.Username}
	if repositoryRef != "" {
		meta["repository_id"] = repositoryRef
	}
	if len(item.Paths) > 0 {
		if b, err := json.Marshal(item.Paths); err == nil {
			meta["paths"] = string(b)
		}
	}
	if len(item.Tags) > 0 {
		if b, err := json.Marshal(item.Tags); err == nil {
			meta["tags"] = string(b)
		}
	}
	if item.Size != nil {
		meta["size"] = strconv.FormatUint(*item.Size, 10)
	}
	return Artifact{Label: "restic_snapshot", Path: "restic://snapshot/" + item.ID, Metadata: meta}
}

func (d *Dispatcher) snapshotsList(ctx OperationContext, p map[string]any) (Result, error) {
	access, ref, err := d.repositoryAccess(p)
	if err != nil {
		return Result{}, err
	}
	tags, err := stringSliceDefault(p, "tags", []string{})
	if err != nil {
		return Result{}, err
	}
	listed, err := resticops.ListSnapshots(access, resticops.SnapshotListOptions{Host: optionalString(p["host"]), Path: optionalString(p["path"]), Tags: tags}, resticops.Options{Cancelled: ctx.Cancelled})
	if err != nil {
		return Result{}, err
	}
	arts := make([]Artifact, 0, len(listed.Snapshots))
	for _, item := range listed.Snapshots {
		arts = append(arts, snapshotArtifact(ref, listed.Repository, item))
	}
	return success(fmt.Sprintf("%d Restic snapshots.", listed.Total), nil, arts), nil
}

func (d *Dispatcher) snapshotsBrowse(ctx OperationContext, p map[string]any) (Result, error) {
	access, ref, err := d.repositoryAccess(p)
	if err != nil {
		return Result{}, err
	}
	selector, err := requiredString(p, "snapshot")
	if err != nil {
		return Result{}, err
	}
	path := optionalString(p["path"])
	recursive, _ := p["recursive"].(bool)
	browse, err := resticops.BrowseSnapshot(access, selector, path, recursive, resticops.Options{Cancelled: ctx.Cancelled})
	if err != nil {
		return Result{}, err
	}
	arts := make([]Artifact, 0, len(browse.Entries))
	for _, node := range browse.Entries {
		label := "snapshot_node"
		meta := map[string]string{
			"snapshot_id": browse.SnapshotID,
			"browse_path": browse.Path,
			"name":        node.Name,
			"type":        node.Type,
		}
		if ref != "" {
			meta["repository_id"] = ref
		}
		if node.Size > 0 {
			meta["size"] = strconv.FormatUint(node.Size, 10)
		}
		if node.Permissions != "" {
			meta["permissions"] = node.Permissions
		}
		if node.ModTime != "" {
			meta["mtime"] = node.ModTime
		}
		arts = append(arts, Artifact{Label: label, Path: node.Path, Metadata: meta})
	}
	return success(fmt.Sprintf("%d entries in snapshot %s at %s.", len(arts), shortSnapshotID(browse.SnapshotID), browse.Path), nil, arts), nil
}

func shortSnapshotID(id string) string {
	if len(id) > 8 {
		return id[:8]
	}
	return id
}

func (d *Dispatcher) snapshotsShow(ctx OperationContext, p map[string]any) (Result, error) {
	access, ref, err := d.repositoryAccess(p)
	if err != nil {
		return Result{}, err
	}
	selector, err := requiredString(p, "snapshot")
	if err != nil {
		return Result{}, err
	}
	item, err := resticops.ResolveSnapshot(access, selector, resticops.Options{Cancelled: ctx.Cancelled})
	if err != nil {
		return Result{}, err
	}
	return success("Snapshot "+item.ShortID+" loaded.", nil, []Artifact{snapshotArtifact(ref, access.Repository, item)}), nil
}

func (d *Dispatcher) snapshotsDiff(ctx OperationContext, p map[string]any) (Result, error) {
	access, _, err := d.repositoryAccess(p)
	if err != nil {
		return Result{}, err
	}
	source, err := requiredString(p, "source_snapshot")
	if err != nil {
		return Result{}, err
	}
	target, err := requiredString(p, "target_snapshot")
	if err != nil {
		return Result{}, err
	}
	metadata, _ := p["metadata"].(bool)
	diff, err := resticops.DiffSnapshots(access, source, target, metadata, resticops.Options{Cancelled: ctx.Cancelled})
	if err != nil {
		return Result{}, err
	}
	arts := make([]Artifact, 0, len(diff.Changes)+1)
	for _, change := range diff.Changes {
		arts = append(arts, Artifact{Label: "snapshot_diff_entry", Path: change.Path, Metadata: map[string]string{"modifier": change.Modifier}})
	}
	stats, _ := json.Marshal(diff.Statistics)
	arts = append(arts, Artifact{Label: "snapshot_diff_statistics", Path: "embedded://snapshot-diff/" + requestID(ctx), Metadata: map[string]string{"json": string(stats)}})
	return success(fmt.Sprintf("Snapshot diff: %d changed files, %d entries.", diff.Statistics.ChangedFiles, len(diff.Changes)), nil, arts), nil
}

func (d *Dispatcher) snapshotsRestore(ctx OperationContext, p map[string]any) (Result, error) {
	access, _, err := d.repositoryAccess(p)
	if err != nil {
		return Result{}, err
	}
	selector, err := requiredString(p, "snapshot")
	if err != nil {
		return Result{}, err
	}
	target, err := requiredString(p, "target")
	if err != nil {
		return Result{}, err
	}
	includes, err := stringSliceDefault(p, "includes", []string{})
	if err != nil {
		return Result{}, err
	}
	excludes, err := stringSliceDefault(p, "excludes", []string{})
	if err != nil {
		return Result{}, err
	}
	dry, _ := p["dry_run"].(bool)
	overwrite := optionalString(p["overwrite"])
	result, err := resticops.RestoreSnapshot(access, selector, resticops.RestoreOptions{Target: target, Includes: includes, Excludes: excludes, Overwrite: overwrite, DryRun: dry}, resticops.Options{Cancelled: ctx.Cancelled, Progress: func(e resticops.ProgressEvent) {
		message := fmt.Sprintf("Restored %d files, %d bytes.", e.Files, e.Bytes)
		emitDetailed(ctx, e.Stage, message, e.Progress, int64Ptr(e.Files), positiveInt64Ptr(e.TotalFiles), int64Ptr(e.Bytes), positiveInt64Ptr(e.TotalBytes))
	}})
	if err != nil {
		return Result{}, err
	}
	mode := "restored"
	if result.DryRun {
		mode = "restore dry-run completed for"
	}
	return success(fmt.Sprintf("Snapshot %s %s %s.", shortSnapshotID(result.SnapshotID), mode, result.Target), nil, []Artifact{{Label: "snapshot_restore_target", Path: result.Target, Metadata: map[string]string{"snapshot_id": result.SnapshotID, "overwrite": result.Overwrite, "dry_run": strconv.FormatBool(result.DryRun), "files_restored": strconv.FormatInt(result.FilesRestored, 10), "files_skipped": strconv.FormatInt(result.FilesSkipped, 10), "bytes_restored": strconv.FormatInt(result.BytesRestored, 10), "bytes_skipped": strconv.FormatInt(result.BytesSkipped, 10), "total_files": strconv.FormatInt(result.TotalFiles, 10), "total_bytes": strconv.FormatInt(result.TotalBytes, 10)}}}), nil
}

func (d *Dispatcher) snapshotsForget(ctx OperationContext, p map[string]any) (Result, error) {
	access, _, err := d.repositoryAccess(p)
	if err != nil {
		return Result{}, err
	}
	selector, err := requiredString(p, "snapshot")
	if err != nil {
		return Result{}, err
	}
	confirm, _ := p["confirm"].(bool)
	if !confirm {
		return Result{}, berror.Validation("snapshot forget requires confirm=true", map[string]any{"action": "Confirm the exact snapshot ID before forgetting it."})
	}
	result, err := resticops.ForgetSnapshot(access, selector, resticops.Options{Cancelled: ctx.Cancelled})
	if err != nil {
		return Result{}, err
	}
	return success("Snapshot "+result.SnapshotID+" forgotten. Repository data has not been pruned.", nil, nil), nil
}

func (d *Dispatcher) repositoryCheck(ctx OperationContext, p map[string]any) (Result, error) {
	access, _, err := d.repositoryAccess(p)
	if err != nil {
		return Result{}, err
	}
	readData, _ := p["read_data"].(bool)
	result, err := resticops.CheckRepository(access, resticops.CheckOptions{ReadData: readData, ReadDataSubset: optionalString(p["read_data_subset"])}, resticops.Options{Cancelled: ctx.Cancelled})
	if err != nil {
		return Result{}, err
	}
	return success("Restic repository check passed.", nil, []Artifact{{Label: "repository_check", Path: result.Repository}}), nil
}

func (d *Dispatcher) repositoryPrune(ctx OperationContext, p map[string]any) (Result, error) {
	access, _, err := d.repositoryAccess(p)
	if err != nil {
		return Result{}, err
	}
	dry, _ := p["dry_run"].(bool)
	confirm, _ := p["confirm"].(bool)
	if !dry && !confirm {
		return Result{}, berror.Validation("repository prune requires confirm=true unless dry_run=true", map[string]any{"action": "Run a prune dry-run first, then confirm."})
	}
	result, err := resticops.PruneRepository(access, resticops.PruneOptions{DryRun: dry, MaxUnused: optionalString(p["max_unused"]), MaxRepackSize: optionalString(p["max_repack_size"])}, resticops.Options{Cancelled: ctx.Cancelled})
	if err != nil {
		return Result{}, err
	}
	return success("Restic repository prune completed.", nil, []Artifact{{Label: "repository_prune", Path: result.Repository, Metadata: map[string]string{"dry_run": strconv.FormatBool(result.DryRun)}}}), nil
}

func retentionPolicyFromPayload(p map[string]any) (models.RetentionPolicy, error) {
	raw, ok := p["policy"]
	if !ok {
		return models.RetentionPolicy{}, berror.Validation("retention policy is required", nil)
	}
	data, err := json.Marshal(raw)
	if err != nil {
		return models.RetentionPolicy{}, err
	}
	var policy models.RetentionPolicy
	if err := json.Unmarshal(data, &policy); err != nil {
		return policy, berror.Validation("invalid retention policy", map[string]any{"reason": err.Error()})
	}
	if policy.SchemaVersion == 0 {
		policy.SchemaVersion = models.RetentionPolicySchemaVersion
	}
	if err := models.ValidateRetentionPolicy(policy); err != nil {
		return policy, berror.Validation("invalid retention policy", map[string]any{"reason": err.Error()})
	}
	return policy, nil
}

func (d *Dispatcher) retentionSet(p map[string]any) (Result, error) {
	if d.Repositories == nil {
		return Result{}, berror.Validation("named repository store is unavailable", nil)
	}
	id, err := requiredString(p, "repository_id")
	if err != nil {
		return Result{}, err
	}
	def, err := d.Repositories.Resolve(id)
	if err != nil {
		return Result{}, err
	}
	policy, err := retentionPolicyFromPayload(p)
	if err != nil {
		return Result{}, err
	}
	def.Retention = &policy
	updated, err := d.Repositories.Update(id, def)
	if err != nil {
		return Result{}, err
	}
	return success("Retention policy saved for "+id+".", nil, []Artifact{repositoryArtifact(updated)}), nil
}
func (d *Dispatcher) retentionShow(p map[string]any) (Result, error) {
	if d.Repositories == nil {
		return Result{}, berror.Validation("named repository store is unavailable", nil)
	}
	id, err := requiredString(p, "repository_id")
	if err != nil {
		return Result{}, err
	}
	def, err := d.Repositories.Resolve(id)
	if err != nil {
		return Result{}, err
	}
	if def.Retention == nil {
		return Result{}, berror.NotFound("No retention policy for "+id, nil)
	}
	data, _ := json.Marshal(def.Retention)
	return success("Retention policy loaded for "+id+".", nil, []Artifact{{Label: "retention_policy", Path: "embedded://repositories/" + id + "/retention", Metadata: map[string]string{"repository_id": id, "json": string(data)}}}), nil
}
func (d *Dispatcher) retentionClear(p map[string]any) (Result, error) {
	if d.Repositories == nil {
		return Result{}, berror.Validation("named repository store is unavailable", nil)
	}
	id, err := requiredString(p, "repository_id")
	if err != nil {
		return Result{}, err
	}
	def, err := d.Repositories.Resolve(id)
	if err != nil {
		return Result{}, err
	}
	def.Retention = nil
	updated, err := d.Repositories.Update(id, def)
	if err != nil {
		return Result{}, err
	}
	return success("Retention policy cleared for "+id+".", nil, []Artifact{repositoryArtifact(updated)}), nil
}
func (d *Dispatcher) retentionApply(ctx OperationContext, p map[string]any) (Result, error) {
	if d.Repositories == nil {
		return Result{}, berror.Validation("named repository store is unavailable", nil)
	}
	id, err := requiredString(p, "repository_id")
	if err != nil {
		return Result{}, err
	}
	def, err := d.Repositories.Resolve(id)
	if err != nil {
		return Result{}, err
	}
	if def.Retention == nil {
		return Result{}, berror.NotFound("No retention policy for "+id, nil)
	}
	dry, _ := p["dry_run"].(bool)
	confirm, _ := p["confirm"].(bool)
	if !dry && !confirm {
		return Result{}, berror.Validation("retention apply requires confirm=true unless dry_run=true", map[string]any{"action": "Run dry_run first, then confirm."})
	}
	result, err := resticops.ApplyRetention(models.RepositoryAccessSpec{Repository: def.Location, CredentialRef: def.CredentialRef}, *def.Retention, dry, resticops.Options{Cancelled: ctx.Cancelled})
	if err != nil {
		return Result{}, err
	}
	return success("Retention policy applied without pruning.", nil, []Artifact{{Label: "retention_apply", Path: result.Repository, Metadata: map[string]string{"repository_id": id, "dry_run": strconv.FormatBool(dry)}}}), nil
}

func positiveInt64Ptr(value int64) *int64 {
	if value <= 0 {
		return nil
	}
	return int64Ptr(value)
}

var _ = strings.TrimSpace
