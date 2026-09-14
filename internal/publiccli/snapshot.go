package publiccli

import (
	"fmt"
	"strings"

	"bkx/internal/berror"
	"bkx/internal/models"
	"bkx/internal/resticops"
	"bkx/internal/validation"
)

func snapshotAccessOptions() map[string]optionSpec {
	return map[string]optionSpec{
		"--repository":     {Key: "repository", Kind: optionValue},
		"--repository-ref": {Key: "repository_ref", Kind: optionValue},
		"--credential-ref": {Key: "credential_ref", Kind: optionValue},
	}
}

func snapshotAccess(c *appContext, a leafArgs) (models.RepositoryAccessSpec, error) {
	repository, hasRepository := a.stringValue("repository")
	ref, hasRef := a.stringValue("repository_ref")
	if hasRepository == hasRef {
		return models.RepositoryAccessSpec{}, berror.Validation("use exactly one of --repository or --repository-ref", nil)
	}
	var credentialRef *string
	if hasRef {
		def, err := c.repositories.Resolve(strings.TrimSpace(ref))
		if err != nil {
			return models.RepositoryAccessSpec{}, err
		}
		repository = def.Location
		credentialRef = def.CredentialRef
	}
	if raw, set := a.stringValue("credential_ref"); set {
		raw = strings.TrimSpace(raw)
		credentialRef = &raw
	}
	return models.RepositoryAccessSpec{Repository: repository, CredentialRef: credentialRef}, nil
}

// snapshotContextAccess supports the BR09+10 positional ergonomics used by the
// roadmap while retaining the explicit --repository/--repository-ref form.
// A context may be either a saved-job id (preferred when ids collide) or a
// named repository id. Saved jobs resolve only through their non-secret
// repository reference / credential references; no secret material is copied
// into the job or command line.
func snapshotContextAccess(c *appContext, selector string, credentialOverride *string) (models.RepositoryAccessSpec, error) {
	selector = strings.TrimSpace(selector)
	if selector == "" {
		return models.RepositoryAccessSpec{}, berror.Validation("snapshot job/repository context must not be empty", nil)
	}
	job, err := c.jobs.Get(selector)
	if err != nil {
		return models.RepositoryAccessSpec{}, err
	}
	if job != nil {
		ref := ""
		if job.Operation.RepositoryRef != nil {
			ref = strings.TrimSpace(*job.Operation.RepositoryRef)
		}
		if ref != "" {
			def, err := c.repositories.Resolve(ref)
			if err != nil {
				return models.RepositoryAccessSpec{}, err
			}
			credentialRef := def.CredentialRef
			if credentialOverride != nil {
				credentialRef = credentialOverride
			}
			return models.RepositoryAccessSpec{Repository: def.Location, CredentialRef: credentialRef}, nil
		}
		if raw, ok := job.Operation.Parameters["repository"].(string); ok && strings.TrimSpace(raw) != "" {
			credentialRef := credentialOverride
			if credentialRef == nil && len(job.Operation.CredentialRefs) > 0 {
				ref := strings.TrimSpace(job.Operation.CredentialRefs[0].ID)
				if ref != "" {
					credentialRef = &ref
				}
			}
			return models.RepositoryAccessSpec{Repository: strings.TrimSpace(raw), CredentialRef: credentialRef}, nil
		}
		return models.RepositoryAccessSpec{}, berror.Validation("saved job does not reference a snapshot repository", map[string]any{"job_id": selector, "operation": job.Operation.Operation})
	}
	def, err := c.repositories.Get(selector)
	if err != nil {
		return models.RepositoryAccessSpec{}, err
	}
	if def == nil {
		return models.RepositoryAccessSpec{}, berror.NotFound("Saved job or repository not found: "+selector, map[string]any{"selector": selector})
	}
	credentialRef := def.CredentialRef
	if credentialOverride != nil {
		credentialRef = credentialOverride
	}
	return models.RepositoryAccessSpec{Repository: def.Location, CredentialRef: credentialRef}, nil
}

func snapshotAccessAndPositionals(c *appContext, a leafArgs, snapshotPositionals int) (models.RepositoryAccessSpec, []string, error) {
	_, hasRepository := a.stringValue("repository")
	_, hasRef := a.stringValue("repository_ref")
	var credentialOverride *string
	if raw, set := a.stringValue("credential_ref"); set {
		raw = strings.TrimSpace(raw)
		credentialOverride = &raw
	}
	if hasRepository || hasRef {
		if len(a.Positionals) != snapshotPositionals {
			return models.RepositoryAccessSpec{}, nil, berror.Validation(fmt.Sprintf("expected %d snapshot argument(s) when repository flags are used", snapshotPositionals), nil)
		}
		access, err := snapshotAccess(c, a)
		return access, append([]string(nil), a.Positionals...), err
	}
	if len(a.Positionals) != snapshotPositionals+1 {
		return models.RepositoryAccessSpec{}, nil, berror.Validation("provide a saved-job/repository context positionally, or use exactly one of --repository or --repository-ref", nil)
	}
	access, err := snapshotContextAccess(c, a.Positionals[0], credentialOverride)
	if err != nil {
		return models.RepositoryAccessSpec{}, nil, err
	}
	return access, append([]string(nil), a.Positionals[1:]...), nil
}

func mergeOptions(base map[string]optionSpec, extra map[string]optionSpec) map[string]optionSpec {
	out := map[string]optionSpec{}
	for k, v := range base {
		out[k] = v
	}
	for k, v := range extra {
		out[k] = v
	}
	return out
}

func snapshotList(c *appContext, args []string) int {
	specs := mergeOptions(snapshotAccessOptions(), map[string]optionSpec{"--host": {Key: "host", Kind: optionValue}, "--path": {Key: "path", Kind: optionValue}, "--tag": {Key: "tag", Kind: optionMultiValue}, "--limit": {Key: "limit", Kind: optionValue}})
	a, ok := parseFor(c, "snapshot list", args, specs)
	if !ok {
		return berror.ExitOK
	}
	access, _, err := snapshotAccessAndPositionals(c, a, 0)
	if err != nil {
		return commandErr(c, err)
	}
	host, _ := a.stringValue("host")
	path, _ := a.stringValue("path")
	limit, err := a.intValue("limit", 0)
	if err != nil {
		return commandErr(c, berror.Validation(err.Error(), nil))
	}
	result, err := resticops.ListSnapshots(access, resticops.SnapshotListOptions{Host: host, Path: path, Tags: a.multiValue("tag"), Limit: limit}, resticops.Options{})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "snapshot_list")
}

func snapshotBrowse(c *appContext, args []string) int {
	specs := mergeOptions(snapshotAccessOptions(), map[string]optionSpec{"--path": {Key: "path", Kind: optionValue}, "--recursive": {Key: "recursive", Kind: optionBoolTrue}, "--limit": {Key: "limit", Kind: optionValue}})
	a, ok := parseFor(c, "snapshot browse", args, specs)
	if !ok {
		return berror.ExitOK
	}
	access, positionals, err := snapshotAccessAndPositionals(c, a, 1)
	if err != nil {
		return commandErr(c, err)
	}
	path, _ := a.stringValue("path")
	recursive := false
	if v, ok := a.triBool("recursive"); ok && v != nil {
		recursive = *v
	}
	limit, err := a.intValue("limit", 0)
	if err != nil {
		return commandErr(c, berror.Validation(err.Error(), nil))
	}
	result, err := resticops.BrowseSnapshotWithOptions(access, positionals[0], path, resticops.SnapshotBrowseOptions{Recursive: recursive, Limit: limit}, resticops.Options{})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "snapshot_browse")
}

func snapshotShow(c *appContext, args []string) int {
	a, ok := parseFor(c, "snapshot show", args, snapshotAccessOptions())
	if !ok {
		return berror.ExitOK
	}
	access, positionals, err := snapshotAccessAndPositionals(c, a, 1)
	if err != nil {
		return commandErr(c, err)
	}
	item, err := resticops.ResolveSnapshot(access, positionals[0], resticops.Options{})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, item, "snapshot")
}

func snapshotDiff(c *appContext, args []string) int {
	specs := mergeOptions(snapshotAccessOptions(), map[string]optionSpec{"--metadata": {Key: "metadata", Kind: optionBoolTrue}, "--limit": {Key: "limit", Kind: optionValue}})
	a, ok := parseFor(c, "snapshot diff", args, specs)
	if !ok {
		return berror.ExitOK
	}
	access, positionals, err := snapshotAccessAndPositionals(c, a, 2)
	if err != nil {
		return commandErr(c, err)
	}
	metadata := false
	if v, ok := a.triBool("metadata"); ok && v != nil {
		metadata = *v
	}
	limit, err := a.intValue("limit", 0)
	if err != nil {
		return commandErr(c, berror.Validation(err.Error(), nil))
	}
	result, err := resticops.DiffSnapshotsWithOptions(access, positionals[0], positionals[1], resticops.SnapshotDiffOptions{Metadata: metadata, MaxChanges: limit}, resticops.Options{})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "snapshot_diff")
}

func snapshotRestore(c *appContext, args []string) int {
	specs := mergeOptions(snapshotAccessOptions(), map[string]optionSpec{
		"--target": {Key: "target", Kind: optionValue}, "--include": {Key: "include", Kind: optionMultiValue}, "--exclude": {Key: "exclude", Kind: optionMultiValue}, "--overwrite": {Key: "overwrite", Kind: optionValue},
	})
	a, ok := parseFor(c, "snapshot restore", args, specs)
	if !ok {
		return berror.ExitOK
	}
	access, positionals, err := snapshotAccessAndPositionals(c, a, 1)
	if err != nil {
		return commandErr(c, err)
	}
	target, set := a.stringValue("target")
	if !set {
		return commandErr(c, berror.Validation("--target is required", nil))
	}
	abs, err := canonicalOptionalPath(target)
	if err != nil {
		return commandErr(c, err)
	}
	overwrite, _ := a.stringValue("overwrite")
	var options resticops.Options
	var lease *cliExecutionLease
	if !c.globals.DryRun {
		lease, err = beginCLIExecution(c, "snapshots.restore", "", repositoryLeaseID(c, access.Repository))
		if err != nil {
			return commandErr(c, err)
		}
		defer lease.Close()
		options.Cancelled = lease.Cancelled
	}
	includes, err := validation.NormalizePatterns(a.multiValue("include"), "includes")
	if err != nil {
		return commandErr(c, err)
	}
	excludes, err := validation.NormalizePatterns(a.multiValue("exclude"), "excludes")
	if err != nil {
		return commandErr(c, err)
	}
	result, err := resticops.RestoreSnapshot(access, positionals[0], resticops.RestoreOptions{Target: abs, Includes: includes, Excludes: excludes, Overwrite: overwrite, DryRun: c.globals.DryRun}, options)
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "snapshot_restore")
}

func snapshotForget(c *appContext, args []string) int {
	specs := mergeOptions(snapshotAccessOptions(), map[string]optionSpec{"--confirm": {Key: "confirm", Kind: optionBoolTrue}})
	a, ok := parseFor(c, "snapshot forget", args, specs)
	if !ok {
		return berror.ExitOK
	}
	access, positionals, err := snapshotAccessAndPositionals(c, a, 1)
	if err != nil {
		return commandErr(c, err)
	}
	confirm := false
	if v, ok := a.triBool("confirm"); ok && v != nil {
		confirm = *v
	}
	if c.globals.DryRun {
		resolved, err := resticops.ResolveSnapshot(access, positionals[0], resticops.Options{})
		if err != nil {
			return commandErr(c, err)
		}
		return renderResult(c, map[string]any{"success": true, "operation": "forget", "dry_run": true, "repository": access.Repository, "snapshot_id": resolved.ID}, "snapshot_forget")
	}
	if !confirm {
		return commandErr(c, berror.Validation("snapshot forget requires --confirm; it never prunes automatically", map[string]any{"action": "Review the snapshot ID, then rerun with --confirm."}))
	}
	lease, err := beginCLIExecution(c, "snapshots.forget", "", repositoryLeaseID(c, access.Repository))
	if err != nil {
		return commandErr(c, err)
	}
	defer lease.Close()
	result, err := resticops.ForgetSnapshot(access, positionals[0], resticops.Options{Cancelled: lease.Cancelled})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "snapshot_forget")
}
