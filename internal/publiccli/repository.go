package publiccli

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"bkx/internal/berror"
	"bkx/internal/models"
	"bkx/internal/repositorystore"
	"bkx/internal/resticops"
)

func repositoryAdd(c *appContext, args []string) int {
	specs := map[string]optionSpec{
		"--name":                 {Key: "name", Kind: optionValue},
		"--engine":               {Key: "engine", Kind: optionValue},
		"--backend":              {Key: "backend", Kind: optionValue},
		"--location":             {Key: "location", Kind: optionValue},
		"--remote":               {Key: "remote", Kind: optionValue},
		"--managed-google-drive": {Key: "managed_google_drive", Kind: optionBoolTrue},
		"--path":                 {Key: "path", Kind: optionValue},
		"--credential-ref":       {Key: "credential_ref", Kind: optionValue},
	}
	a, ok := parseFor(c, "repository add", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "REPOSITORY_ID"); err != nil {
		return cliUsageError(c, err)
	}
	location, hasLocation := a.stringValue("location")
	remote, hasRemote := a.stringValue("remote")
	managedDrive := false
	if value, ok := a.triBool("managed_google_drive"); ok && value != nil {
		managedDrive = *value
	}
	selected := 0
	if hasLocation {
		selected++
	}
	if hasRemote {
		selected++
	}
	if managedDrive {
		selected++
	}
	if selected != 1 {
		return commandErr(c, berror.Validation("repository add requires exactly one of --location, --remote, or --managed-google-drive", nil))
	}
	if hasRemote {
		var err error
		location, err = repositorystore.RcloneURIFromRemote(remote)
		if err != nil {
			return commandErr(c, err)
		}
	}
	if managedDrive {
		child, _ := a.stringValue("path")
		child = strings.Trim(strings.TrimSpace(child), "/")
		if child == "" {
			child = "restic/" + strings.TrimSpace(a.Positionals[0])
		}
		if strings.Contains(child, "..") {
			return commandErr(c, berror.Validation("managed Google Drive repository path must not escape the BKX root", map[string]any{"path": child}))
		}
		location = "rclone://" + models.BKXGoogleDriveRemoteName + "/BKX/" + child
	}
	engine, _ := a.stringValue("engine")
	engine = strings.ToLower(strings.TrimSpace(engine))
	if engine == "" {
		engine = models.BackupEngineRestic
	}
	if engine != models.BackupEngineRestic {
		return commandErr(c, berror.Validation("named repositories currently use the restic engine", map[string]any{"engine": engine}))
	}
	backend, _ := a.stringValue("backend")
	backend = strings.ToLower(strings.TrimSpace(backend))
	if managedDrive {
		if backend != "" && backend != models.RepositoryBackendManagedGoogleDrive {
			return commandErr(c, berror.Validation("--managed-google-drive conflicts with --backend", map[string]any{"backend": backend}))
		}
		backend = models.RepositoryBackendManagedGoogleDrive
	} else if backend == "" && hasRemote {
		backend = models.RepositoryBackendRclone
	}
	name, _ := a.stringValue("name")
	if strings.TrimSpace(name) == "" {
		name = a.Positionals[0]
	}
	var credentialRef *string
	if raw, set := a.stringValue("credential_ref"); set {
		raw = strings.TrimSpace(raw)
		credentialRef = &raw
	}
	created, err := c.repositories.Create(models.RepositoryDefinition{
		RepositoryID: a.Positionals[0], Name: name, Engine: engine, Backend: backend,
		Location: location, CredentialRef: credentialRef,
	})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, created, "repository")
}

func repositoryList(c *appContext, args []string) int {
	a, ok := parseFor(c, "repository list", args, map[string]optionSpec{})
	if !ok {
		return berror.ExitOK
	}
	if len(a.Positionals) != 0 {
		return cliUsageError(c, fmt.Errorf("unexpected argument: %s", a.Positionals[0]))
	}
	items, err := c.repositories.List()
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, struct {
		Repositories []models.RepositoryDefinition `json:"repositories"`
		Total        int                           `json:"total"`
	}{items, len(items)}, "repository_list")
}

func getRepository(c *appContext, id string) (*models.RepositoryDefinition, int) {
	item, err := c.repositories.Get(strings.TrimSpace(id))
	if err != nil {
		return nil, commandErr(c, err)
	}
	if item == nil {
		return nil, commandErr(c, berror.NotFound("Repository not found: "+id, map[string]any{"repository_id": id}))
	}
	return item, -1
}

func repositoryShow(c *appContext, args []string) int {
	a, ok := parseFor(c, "repository show", args, map[string]optionSpec{})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "REPOSITORY_ID"); err != nil {
		return cliUsageError(c, err)
	}
	item, code := getRepository(c, a.Positionals[0])
	if item == nil {
		return code
	}
	return renderResult(c, item, "repository")
}

func repositoryStatus(c *appContext, args []string) int {
	a, ok := parseFor(c, "repository status", args, map[string]optionSpec{})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "REPOSITORY_ID"); err != nil {
		return cliUsageError(c, err)
	}
	def, code := getRepository(c, a.Positionals[0])
	if def == nil {
		return code
	}
	listed, err := resticops.ListSnapshots(
		models.RepositoryAccessSpec{Repository: def.Location, CredentialRef: def.CredentialRef},
		resticops.SnapshotListOptions{Limit: 1},
		resticops.Options{},
	)
	if err != nil {
		return commandErr(c, err)
	}
	type repositoryStatusResult struct {
		Repository     models.RepositoryDefinition `json:"repository"`
		Reachable      bool                        `json:"reachable"`
		SnapshotCount  int                         `json:"snapshot_count"`
		LatestSnapshot *models.SnapshotDescriptor  `json:"latest_snapshot,omitempty"`
	}
	var latest *models.SnapshotDescriptor
	if len(listed.Snapshots) > 0 {
		copy := listed.Snapshots[0]
		latest = &copy
	}
	return renderResult(c, repositoryStatusResult{
		Repository: *def, Reachable: true, SnapshotCount: listed.Total, LatestSnapshot: latest,
	}, "repository_status")
}

func repositoryRemove(c *appContext, args []string) int {
	a, ok := parseFor(c, "repository remove", args, map[string]optionSpec{"--force": {Key: "force", Kind: optionBoolTrue}})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "REPOSITORY_ID"); err != nil {
		return cliUsageError(c, err)
	}
	repositoryID := strings.TrimSpace(a.Positionals[0])
	if _, code := getRepository(c, repositoryID); code != -1 {
		return code
	}
	force := a.Bools["force"]
	deps, depErr := repositoryDependencies(c, repositoryID)
	if depErr != nil {
		return commandErr(c, depErr)
	}
	if !force && len(deps) > 0 {
		return commandErr(c, berror.Validation("repository is still referenced by saved jobs or schedules", map[string]any{"repository_id": repositoryID, "dependencies": deps, "action": "delete or disable dependents first, or rerun with --force"}))
	}
	deleted, err := c.repositories.Delete(repositoryID)
	if err != nil {
		return commandErr(c, err)
	}
	if !deleted {
		return commandErr(c, berror.NotFound("Repository not found: "+repositoryID, map[string]any{"repository_id": repositoryID}))
	}
	return renderResult(c, map[string]any{"repository_id": repositoryID, "deleted": true, "dependencies": deps}, "repository_delete")
}

func repositoryInit(c *appContext, args []string) int {
	specs := map[string]optionSpec{
		"--engine":         {Key: "engine", Kind: optionValue},
		"--credential-ref": {Key: "credential_ref", Kind: optionValue},
		"--ref":            {Key: "repository_ref", Kind: optionValue},
	}
	a, ok := parseFor(c, "repository init", args, specs)
	if !ok {
		return berror.ExitOK
	}
	ref, hasRef := a.stringValue("repository_ref")
	if hasRef && len(a.Positionals) != 0 {
		return commandErr(c, berror.Validation("repository init accepts either --ref REPOSITORY_ID or a repository location, not both", nil))
	}
	if !hasRef {
		if err := requirePositionals(a.Positionals, 1, 1, "REPOSITORY"); err != nil {
			usageError(c.stderr, err)
			return berror.ExitUsage
		}
	}

	engine, _ := a.stringValue("engine")
	engine = strings.ToLower(strings.TrimSpace(engine))
	if engine == "" {
		engine = models.BackupEngineRestic
	}
	if engine != models.BackupEngineRestic {
		return commandErr(c, berror.Validation("repository init currently supports the restic engine", map[string]any{"engine": engine}))
	}
	var repository string
	var credentialRef *string
	if hasRef {
		definition, err := c.repositories.Resolve(strings.TrimSpace(ref))
		if err != nil {
			return commandErr(c, err)
		}
		repository = definition.Location
		credentialRef = definition.CredentialRef
		engine = definition.Engine
	} else {
		repository = a.Positionals[0]
	}
	if raw, set := a.stringValue("credential_ref"); set {
		raw = strings.TrimSpace(raw)
		if _, err := models.CredentialEnvironmentKey(raw); err != nil {
			return commandErr(c, err)
		}
		credentialRef = &raw
	}
	normalized, normalizeErr := resticops.NormalizeRepository(repository)
	if normalizeErr != nil {
		return commandErr(c, normalizeErr)
	}
	repository = normalized
	parent := ""
	parentMissing := false
	if !strings.Contains(repository, "://") && !strings.Contains(repository, ":") {
		parent = filepath.Dir(repository)
		if parent != "." {
			if _, statErr := os.Stat(parent); os.IsNotExist(statErr) {
				parentMissing = true
			}
		}
	}
	lease, err := beginCLIExecution(c, "repository.init", "", repositoryLeaseID(c, repository))
	if err != nil {
		return commandErr(c, err)
	}
	defer lease.Close()
	result, err := resticops.Init(models.RepositoryInitSpec{Engine: engine, Repository: repository, CredentialRef: credentialRef}, resticops.Options{Cancelled: lease.Cancelled})
	if err != nil {
		cleanupRepositoryInitParent(parent, parentMissing)
		return commandErr(c, err)
	}
	return renderResult(c, result, "repository")
}

func repositoryCheck(c *appContext, args []string) int {
	specs := map[string]optionSpec{
		"--read-data":        {Key: "read_data", Kind: optionBoolTrue},
		"--read-data-subset": {Key: "read_data_subset", Kind: optionValue},
	}
	a, ok := parseFor(c, "repository check", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "REPOSITORY_ID"); err != nil {
		return cliUsageError(c, err)
	}
	def, code := getRepository(c, a.Positionals[0])
	if def == nil {
		return code
	}
	readData := false
	if v, ok := a.triBool("read_data"); ok && v != nil {
		readData = *v
	}
	subset, _ := a.stringValue("read_data_subset")
	if readData && strings.TrimSpace(subset) != "" {
		return commandErr(c, berror.Validation("use either --read-data or --read-data-subset, not both", nil))
	}
	lease, err := beginCLIExecution(c, "repository.check", "", def.RepositoryID)
	if err != nil {
		return commandErr(c, err)
	}
	defer lease.Close()
	result, err := resticops.CheckRepository(models.RepositoryAccessSpec{Repository: def.Location, CredentialRef: def.CredentialRef}, resticops.CheckOptions{ReadData: readData, ReadDataSubset: subset}, resticops.Options{Cancelled: lease.Cancelled})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "repository_check")
}

func repositoryPrune(c *appContext, args []string) int {
	specs := map[string]optionSpec{
		"--confirm":         {Key: "confirm", Kind: optionBoolTrue},
		"--max-unused":      {Key: "max_unused", Kind: optionValue},
		"--max-repack-size": {Key: "max_repack_size", Kind: optionValue},
	}
	a, ok := parseFor(c, "repository prune", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "REPOSITORY_ID"); err != nil {
		return cliUsageError(c, err)
	}
	def, code := getRepository(c, a.Positionals[0])
	if def == nil {
		return code
	}
	confirm := false
	if v, ok := a.triBool("confirm"); ok && v != nil {
		confirm = *v
	}
	if !c.globals.DryRun && !confirm {
		return commandErr(c, berror.Validation("repository prune requires --confirm unless --dry-run is active", map[string]any{"action": "Run with --dry-run first, then rerun with --confirm."}))
	}
	maxUnused, _ := a.stringValue("max_unused")
	maxRepack, _ := a.stringValue("max_repack_size")
	if err := validateResticPruneOption("--max-unused", maxUnused); err != nil {
		return commandErr(c, err)
	}
	if err := validateResticSizeOption("--max-repack-size", maxRepack); err != nil {
		return commandErr(c, err)
	}
	var options resticops.Options
	var lease *cliExecutionLease
	var err error
	if !c.globals.DryRun {
		lease, err = beginCLIExecution(c, "repository.prune", "", def.RepositoryID)
		if err != nil {
			return commandErr(c, err)
		}
		defer lease.Close()
		options.Cancelled = lease.Cancelled
	}
	result, err := resticops.PruneRepository(models.RepositoryAccessSpec{Repository: def.Location, CredentialRef: def.CredentialRef}, resticops.PruneOptions{DryRun: c.globals.DryRun, MaxUnused: maxUnused, MaxRepackSize: maxRepack}, options)
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "repository_prune")
}

func repositoryDependencies(c *appContext, repositoryID string) ([]map[string]string, error) {
	deps := []map[string]string{}
	jobs, err := c.jobs.List()
	if err != nil {
		return nil, err
	}
	for _, job := range jobs {
		if job.Operation.RepositoryRef != nil && strings.TrimSpace(*job.Operation.RepositoryRef) == repositoryID {
			deps = append(deps, map[string]string{"type": "job", "id": job.JobID})
		}
	}
	schedules, err := c.schedules.List()
	if err != nil {
		return nil, err
	}
	for _, schedule := range schedules {
		for _, dep := range deps {
			if dep["type"] == "job" && dep["id"] == schedule.JobID {
				deps = append(deps, map[string]string{"type": "schedule", "id": schedule.ScheduleID, "job_id": schedule.JobID})
			}
		}
	}
	return deps, nil
}

func cleanupRepositoryInitParent(parent string, wasMissing bool) {
	if !wasMissing || parent == "" || parent == "." || parent == string(filepath.Separator) {
		return
	}
	entries, err := os.ReadDir(parent)
	if err == nil && len(entries) == 0 {
		_ = os.Remove(parent)
	}
}

var resticPercentPattern = regexp.MustCompile(`^(?:100|[1-9]?[0-9])%$`)
var resticSizePattern = regexp.MustCompile(`^[1-9][0-9]*(?:[KMGTP]i?B?|B)?$`)

func validateResticPruneOption(flag, value string) error {
	value = strings.TrimSpace(value)
	if value == "" {
		return nil
	}
	if !resticPercentPattern.MatchString(value) {
		return berror.Validation(flag+" must be a Restic percentage such as 5% or 100%", map[string]any{"value": value})
	}
	return nil
}

func validateResticSizeOption(flag, value string) error {
	value = strings.TrimSpace(value)
	if value == "" {
		return nil
	}
	if !resticSizePattern.MatchString(value) {
		return berror.Validation(flag+" must be a Restic size such as 512MiB or 10G", map[string]any{"value": value})
	}
	return nil
}
