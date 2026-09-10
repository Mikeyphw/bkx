package publiccli

import (
	"fmt"
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
		usageError(c.stderr, err)
		return berror.ExitUsage
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
		usageError(c.stderr, fmt.Errorf("unexpected argument: %s", a.Positionals[0]))
		return berror.ExitUsage
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
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	item, code := getRepository(c, a.Positionals[0])
	if item == nil {
		return code
	}
	return renderResult(c, item, "repository")
}

func repositoryRemove(c *appContext, args []string) int {
	a, ok := parseFor(c, "repository remove", args, map[string]optionSpec{})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "REPOSITORY_ID"); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	deleted, err := c.repositories.Delete(strings.TrimSpace(a.Positionals[0]))
	if err != nil {
		return commandErr(c, err)
	}
	if !deleted {
		return commandErr(c, berror.NotFound("Repository not found: "+a.Positionals[0], map[string]any{"repository_id": a.Positionals[0]}))
	}
	return renderResult(c, map[string]any{"repository_id": a.Positionals[0], "deleted": true}, "repository_delete")
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
		credentialRef = &raw
	}
	result, err := resticops.Init(models.RepositoryInitSpec{Engine: engine, Repository: repository, CredentialRef: credentialRef}, resticops.Options{})
	if err != nil {
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
		usageError(c.stderr, err)
		return berror.ExitUsage
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
	result, err := resticops.CheckRepository(models.RepositoryAccessSpec{Repository: def.Location, CredentialRef: def.CredentialRef}, resticops.CheckOptions{ReadData: readData, ReadDataSubset: subset}, resticops.Options{})
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
		usageError(c.stderr, err)
		return berror.ExitUsage
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
	result, err := resticops.PruneRepository(models.RepositoryAccessSpec{Repository: def.Location, CredentialRef: def.CredentialRef}, resticops.PruneOptions{DryRun: c.globals.DryRun, MaxUnused: maxUnused, MaxRepackSize: maxRepack}, resticops.Options{})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "repository_prune")
}
