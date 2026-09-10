package publiccli

import (
	"fmt"
	"strings"

	"bkx/internal/berror"
	"bkx/internal/models"
	"bkx/internal/resticops"
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
	specs := mergeOptions(snapshotAccessOptions(), map[string]optionSpec{"--host": {Key: "host", Kind: optionValue}, "--path": {Key: "path", Kind: optionValue}, "--tag": {Key: "tag", Kind: optionMultiValue}})
	a, ok := parseFor(c, "snapshot list", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if len(a.Positionals) != 0 {
		usageError(c.stderr, fmt.Errorf("unexpected argument: %s", a.Positionals[0]))
		return berror.ExitUsage
	}
	access, err := snapshotAccess(c, a)
	if err != nil {
		return commandErr(c, err)
	}
	host, _ := a.stringValue("host")
	path, _ := a.stringValue("path")
	result, err := resticops.ListSnapshots(access, resticops.SnapshotListOptions{Host: host, Path: path, Tags: a.multiValue("tag")}, resticops.Options{})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "snapshot_list")
}

func snapshotBrowse(c *appContext, args []string) int {
	specs := mergeOptions(snapshotAccessOptions(), map[string]optionSpec{"--path": {Key: "path", Kind: optionValue}, "--recursive": {Key: "recursive", Kind: optionBoolTrue}})
	a, ok := parseFor(c, "snapshot browse", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "SNAPSHOT"); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	access, err := snapshotAccess(c, a)
	if err != nil {
		return commandErr(c, err)
	}
	path, _ := a.stringValue("path")
	recursive := false
	if v, ok := a.triBool("recursive"); ok && v != nil {
		recursive = *v
	}
	result, err := resticops.BrowseSnapshot(access, a.Positionals[0], path, recursive, resticops.Options{})
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
	if err := requirePositionals(a.Positionals, 1, 1, "SNAPSHOT"); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	access, err := snapshotAccess(c, a)
	if err != nil {
		return commandErr(c, err)
	}
	item, err := resticops.ResolveSnapshot(access, a.Positionals[0], resticops.Options{})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, item, "snapshot")
}

func snapshotDiff(c *appContext, args []string) int {
	specs := mergeOptions(snapshotAccessOptions(), map[string]optionSpec{"--metadata": {Key: "metadata", Kind: optionBoolTrue}})
	a, ok := parseFor(c, "snapshot diff", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 2, 2, "SOURCE TARGET"); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	access, err := snapshotAccess(c, a)
	if err != nil {
		return commandErr(c, err)
	}
	metadata := false
	if v, ok := a.triBool("metadata"); ok && v != nil {
		metadata = *v
	}
	result, err := resticops.DiffSnapshots(access, a.Positionals[0], a.Positionals[1], metadata, resticops.Options{})
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
	if err := requirePositionals(a.Positionals, 1, 1, "SNAPSHOT"); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	target, set := a.stringValue("target")
	if !set {
		return commandErr(c, berror.Validation("--target is required", nil))
	}
	abs, err := canonicalOptionalPath(target)
	if err != nil {
		return commandErr(c, err)
	}
	access, err := snapshotAccess(c, a)
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
	result, err := resticops.RestoreSnapshot(access, a.Positionals[0], resticops.RestoreOptions{Target: abs, Includes: a.multiValue("include"), Excludes: a.multiValue("exclude"), Overwrite: overwrite, DryRun: c.globals.DryRun}, options)
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
	if err := requirePositionals(a.Positionals, 1, 1, "SNAPSHOT_ID"); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	confirm := false
	if v, ok := a.triBool("confirm"); ok && v != nil {
		confirm = *v
	}
	if !confirm {
		return commandErr(c, berror.Validation("snapshot forget requires --confirm; it never prunes automatically", map[string]any{"action": "Review the snapshot ID, then rerun with --confirm."}))
	}
	access, err := snapshotAccess(c, a)
	if err != nil {
		return commandErr(c, err)
	}
	lease, err := beginCLIExecution(c, "snapshots.forget", "", repositoryLeaseID(c, access.Repository))
	if err != nil {
		return commandErr(c, err)
	}
	defer lease.Close()
	result, err := resticops.ForgetSnapshot(access, a.Positionals[0], resticops.Options{Cancelled: lease.Cancelled})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "snapshot_forget")
}
