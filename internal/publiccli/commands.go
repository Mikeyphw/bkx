package publiccli

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"bkx/internal/archiveio"
	"bkx/internal/backupengine"
	"bkx/internal/backupops"
	"bkx/internal/berror"
	"bkx/internal/models"
	"bkx/internal/tools"
	"bkx/internal/transferops"
)

type commandHandler func(*appContext, []string) int

var commandHandlers = map[string]commandHandler{
	"archive browse":      archiveBrowse,
	"archive create":      archiveCreate,
	"archive info":        archiveInfo,
	"archive list":        archiveList,
	"archive verify":      archiveVerify,
	"backup create":       backupCreate,
	"backup plan":         backupPlanCommand,
	"repository add":      repositoryAdd,
	"repository check":    repositoryCheck,
	"repository init":     repositoryInit,
	"repository list":     repositoryList,
	"repository prune":    repositoryPrune,
	"repository remove":   repositoryRemove,
	"repository show":     repositoryShow,
	"repository status":   repositoryStatus,
	"snapshot browse":     snapshotBrowse,
	"snapshot diff":       snapshotDiff,
	"snapshot forget":     snapshotForget,
	"snapshot list":       snapshotList,
	"snapshot restore":    snapshotRestore,
	"snapshot show":       snapshotShow,
	"schedule add":        scheduleAdd,
	"schedule disable":    scheduleDisable,
	"schedule enable":     scheduleEnable,
	"schedule list":       scheduleList,
	"schedule remove":     scheduleRemove,
	"schedule show":       scheduleShow,
	"schedule set":        scheduleSet,
	"state status":        stateStatus,
	"retention apply":     retentionApply,
	"retention clear":     retentionClear,
	"retention set":       retentionSet,
	"retention show":      retentionShow,
	"job clone":           jobClone,
	"job create":          jobCreate,
	"job create-from-run": jobCreateFromRun,
	"job delete":          jobDelete,
	"job edit":            jobEdit,
	"job export":          jobExport,
	"job import":          jobImport,
	"job list":            jobList,
	"job plan":            jobPlan,
	"job run":             jobRun,
	"job save":            jobCreate,
	"job show":            jobShow,
	"restore extract":     restoreExtract,
	"restore pick":        restorePick,
	"run cancel":          runCancel,
	"run list":            runList,
	"run replay":          runReplay,
	"run show":            runShow,
	"tools detect":        toolsDetect,
	"tools explain":       toolsExplain,
	"transfer pull":       transferPull,
	"transfer push":       transferPush,
	"ui start":            uiStart,
}

func parseFor(c *appContext, command string, args []string, specs map[string]optionSpec) (leafArgs, bool) {
	parsed, help, err := parseLeaf(args, specs)
	if help {
		fmt.Fprint(c.stdout, LeafHelp[command])
		return leafArgs{}, false
	}
	if err != nil {
		usageError(c.stderr, err)
		return leafArgs{}, false
	}
	return parsed, true
}

func commandErr(c *appContext, err error) int {
	return renderDomainError(c.render, c.globals.Trace, err)
}

func renderResult(c *appContext, value any, kind string) int {
	if err := c.render.result(value, kind); err != nil {
		return commandErr(c, err)
	}
	// Result structs carrying Success=false follow the Python CLI's generic
	// execution failure convention. Engine methods generally return typed
	// errors instead, but preserve the boundary for result-based failures.
	if payload, err := resultPayload(value, kind); err == nil {
		if success, ok := payload["success"].(bool); ok && !success {
			return berror.ExitGeneric
		}
	}
	return berror.ExitOK
}

func readPatterns(files, inline []string) ([]string, error) {
	out := append([]string{}, inline...)
	for _, raw := range files {
		path, err := canonicalOptionalPath(raw)
		if err != nil {
			return nil, err
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return nil, berror.Validation("Could not read pattern file: "+raw, map[string]any{"path": raw, "reason": err.Error()})
		}
		for _, line := range strings.Split(string(data), "\n") {
			item := strings.TrimSpace(line)
			if item != "" && !strings.HasPrefix(item, "#") {
				out = append(out, item)
			}
		}
	}
	return out, nil
}

func compression(c *appContext, a leafArgs) (models.CompressionProfile, bool, error) {
	algorithm, explicit := a.stringValue("compression")
	if !explicit {
		algorithm = c.config.CompressionAlgorithm
	}
	level, err := a.intValue("compression_level", c.config.CompressionLevel)
	if err != nil {
		return models.CompressionProfile{}, explicit, berror.Validation(err.Error(), nil)
	}
	return models.CompressionProfile{Algorithm: algorithm, Level: level}, explicit, nil
}

func resolveVerify(c *appContext, a leafArgs) bool {
	if value, ok := a.triBool("verify"); ok {
		return *value
	}
	return c.config.VerifyAfterOperation
}

func backupOptions() map[string]optionSpec {
	return map[string]optionSpec{
		"-d": {Key: "destination", Kind: optionValue}, "--destination": {Key: "destination", Kind: optionValue},
		"-e": {Key: "exclude", Kind: optionMultiValue}, "--exclude": {Key: "exclude", Kind: optionMultiValue},
		"-i": {Key: "include", Kind: optionMultiValue}, "--include": {Key: "include", Kind: optionMultiValue},
		"--exclude-from": {Key: "exclude_from", Kind: optionMultiValue}, "--include-from": {Key: "include_from", Kind: optionMultiValue},
		"-n": {Key: "name", Kind: optionValue}, "--name": {Key: "name", Kind: optionValue},
		"--compression": {Key: "compression", Kind: optionValue}, "--compression-level": {Key: "compression_level", Kind: optionValue},
		"--split-size":     {Key: "split_size", Kind: optionValue},
		"--engine":         {Key: "engine", Kind: optionValue},
		"--repository":     {Key: "repository", Kind: optionValue},
		"--repository-ref": {Key: "repository_ref", Kind: optionValue},
		"--credential-ref": {Key: "credential_ref", Kind: optionValue},
		"--tag":            {Key: "tag", Kind: optionMultiValue},
		"--verify":         {Key: "verify", Kind: optionBoolTrue}, "--no-verify": {Key: "verify", Kind: optionBoolFalse},
	}
}

func backupCreate(c *appContext, args []string) int {
	a, ok := parseFor(c, "backup create", args, backupOptions())
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, -1, "SOURCES..."); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	excludes, err := readPatterns(a.multiValue("exclude_from"), a.multiValue("exclude"))
	if err != nil {
		return commandErr(c, err)
	}
	includes, err := readPatterns(a.multiValue("include_from"), a.multiValue("include"))
	if err != nil {
		return commandErr(c, err)
	}
	profile, explicitAlgorithm, err := compression(c, a)
	if err != nil {
		return commandErr(c, err)
	}
	split, err := a.int64Ptr("split_size")
	if err != nil {
		return commandErr(c, berror.Validation(err.Error(), nil))
	}
	var destination *string
	if raw, set := a.stringValue("destination"); set {
		destination = &raw
	} else if c.config.DefaultDestination != nil {
		value := *c.config.DefaultDestination
		destination = &value
	}
	var name *string
	if raw, set := a.stringValue("name"); set {
		name = &raw
	}
	engine, _ := a.stringValue("engine")
	engine = strings.ToLower(strings.TrimSpace(engine))
	repository, hasRepository := a.stringValue("repository")
	repositoryRef, hasRepositoryRef := a.stringValue("repository_ref")
	if hasRepository && hasRepositoryRef {
		return commandErr(c, berror.Validation("Use either --repository or --repository-ref, not both", nil))
	}
	var credentialRef *string
	if hasRepositoryRef {
		definition, err := c.repositories.Resolve(strings.TrimSpace(repositoryRef))
		if err != nil {
			return commandErr(c, err)
		}
		repository = definition.Location
		hasRepository = true
		credentialRef = definition.CredentialRef
		if engine == "" {
			engine = definition.Engine
		} else if engine != definition.Engine {
			return commandErr(c, berror.Validation("Requested backup engine does not match named repository", map[string]any{"engine": engine, "repository_ref": repositoryRef, "repository_engine": definition.Engine}))
		}
	}
	if raw, set := a.stringValue("credential_ref"); set {
		raw = strings.TrimSpace(raw)
		credentialRef = &raw
	}
	if engine == "" {
		if hasRepository {
			engine = models.BackupEngineRestic
		} else {
			engine = models.BackupEngineBakery
		}
	}
	if engine == models.BackupEngineRestic {
		if !hasRepository || strings.TrimSpace(repository) == "" {
			return commandErr(c, berror.Validation("--repository or --repository-ref is required with --engine restic", nil))
		}
		req := backupengine.Request{Engine: engine, Sources: a.Positionals, Repository: &repository, CredentialRef: credentialRef, Excludes: excludes, Includes: includes, Tags: a.multiValue("tag"), Verify: resolveVerify(c, a), DryRun: c.globals.DryRun}
		if c.globals.DryRun {
			plan, err := backupengine.PlanBackup(req)
			if err != nil {
				return commandErr(c, err)
			}
			return renderResult(c, plan, "backup_plan")
		}
		lease, err := beginCLIExecution(c, "backup.snapshot", "", repositoryLeaseID(c, repository))
		if err != nil {
			return commandErr(c, err)
		}
		defer lease.Close()
		result, err := backupengine.Create(req, backupengine.Options{RunStore: c.store, RunID: lease.requestID, Cancelled: lease.Cancelled})
		if err != nil {
			return commandErr(c, err)
		}
		return renderResult(c, result, "backup")
	}
	if engine != models.BackupEngineBakery {
		return commandErr(c, berror.Validation("Unknown backup engine", map[string]any{"engine": engine, "supported": []string{models.BackupEngineBakery, models.BackupEngineRestic}}))
	}
	if hasRepository {
		return commandErr(c, berror.Validation("--repository and --repository-ref are only valid with the restic backup engine", nil))
	}
	spec := models.BackupSpec{Sources: a.Positionals, Destination: destination, ArchiveName: name, Excludes: excludes, Includes: includes, SplitSize: split, Compression: profile, Verify: resolveVerify(c, a), DryRun: c.globals.DryRun, SkipSymlinks: false}
	if c.globals.DryRun {
		plan, err := backupPlan(spec, "backup create")
		if err != nil {
			return commandErr(c, err)
		}
		if err := c.render.plan("backup", plan); err != nil {
			return commandErr(c, err)
		}
		return berror.ExitOK
	}
	if c.globals.Verbose {
		c.render.verbose("executing backup create")
	}
	result, err := backupops.Create(spec, !explicitAlgorithm, backupops.Options{RunStore: c.store})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "backup")
}

func backupPlanCommand(c *appContext, args []string) int {
	specs := map[string]optionSpec{
		"-d": {Key: "destination", Kind: optionValue}, "--destination": {Key: "destination", Kind: optionValue},
		"--engine": {Key: "engine", Kind: optionValue}, "--repository": {Key: "repository", Kind: optionValue}, "--repository-ref": {Key: "repository_ref", Kind: optionValue},
		"--credential-ref": {Key: "credential_ref", Kind: optionValue}, "--exclude": {Key: "exclude", Kind: optionMultiValue},
		"--tag": {Key: "tag", Kind: optionMultiValue}, "--verify": {Key: "verify", Kind: optionBoolTrue}, "--no-verify": {Key: "verify", Kind: optionBoolFalse},
	}
	a, ok := parseFor(c, "backup plan", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, -1, "SOURCES..."); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	var destination *string
	if raw, set := a.stringValue("destination"); set {
		destination = &raw
	} else if c.config.DefaultDestination != nil {
		value := *c.config.DefaultDestination
		destination = &value
	}
	engine, _ := a.stringValue("engine")
	engine = strings.ToLower(strings.TrimSpace(engine))
	repository, hasRepository := a.stringValue("repository")
	repositoryRef, hasRepositoryRef := a.stringValue("repository_ref")
	if hasRepository && hasRepositoryRef {
		return commandErr(c, berror.Validation("Use either --repository or --repository-ref, not both", nil))
	}
	var credentialRef *string
	if hasRepositoryRef {
		definition, err := c.repositories.Resolve(strings.TrimSpace(repositoryRef))
		if err != nil {
			return commandErr(c, err)
		}
		repository = definition.Location
		hasRepository = true
		credentialRef = definition.CredentialRef
		if engine == "" {
			engine = definition.Engine
		} else if engine != definition.Engine {
			return commandErr(c, berror.Validation("Requested backup engine does not match named repository", map[string]any{"engine": engine, "repository_ref": repositoryRef, "repository_engine": definition.Engine}))
		}
	}
	if raw, set := a.stringValue("credential_ref"); set {
		raw = strings.TrimSpace(raw)
		credentialRef = &raw
	}
	if engine == "" {
		if hasRepository {
			engine = models.BackupEngineRestic
		} else {
			engine = models.BackupEngineBakery
		}
	}
	if engine == models.BackupEngineRestic {
		if !hasRepository || strings.TrimSpace(repository) == "" {
			return commandErr(c, berror.Validation("--repository or --repository-ref is required with --engine restic", nil))
		}
		plan, err := backupengine.PlanBackup(backupengine.Request{Engine: engine, Sources: a.Positionals, Repository: &repository, CredentialRef: credentialRef, Excludes: a.multiValue("exclude"), Tags: a.multiValue("tag"), Verify: resolveVerify(c, a), DryRun: true})
		if err != nil {
			return commandErr(c, err)
		}
		return renderResult(c, plan, "backup_plan")
	}
	if engine != models.BackupEngineBakery {
		return commandErr(c, berror.Validation("Unknown backup engine", map[string]any{"engine": engine, "supported": []string{models.BackupEngineBakery, models.BackupEngineRestic}}))
	}
	if hasRepository {
		return commandErr(c, berror.Validation("--repository and --repository-ref are only valid with the restic backup engine", nil))
	}
	spec := models.BackupSpec{Sources: a.Positionals, Destination: destination, Compression: models.CompressionProfile{Algorithm: c.config.CompressionAlgorithm, Level: c.config.CompressionLevel}, Verify: c.config.VerifyAfterOperation, DryRun: true, Excludes: []string{}, Includes: []string{}}
	plan, err := backupPlan(spec, "backup plan")
	if err != nil {
		return commandErr(c, err)
	}
	if err := c.render.plan("backup", plan); err != nil {
		return commandErr(c, err)
	}
	return berror.ExitOK
}

func archiveCreate(c *appContext, args []string) int {
	specs := map[string]optionSpec{
		"-o": {Key: "output", Kind: optionValue}, "--output": {Key: "output", Kind: optionValue},
		"-e": {Key: "exclude", Kind: optionMultiValue}, "--exclude": {Key: "exclude", Kind: optionMultiValue},
		"-i": {Key: "include", Kind: optionMultiValue}, "--include": {Key: "include", Kind: optionMultiValue},
		"--exclude-from": {Key: "exclude_from", Kind: optionMultiValue}, "--include-from": {Key: "include_from", Kind: optionMultiValue},
		"--compression": {Key: "compression", Kind: optionValue}, "--compression-level": {Key: "compression_level", Kind: optionValue},
		"--split-size": {Key: "split_size", Kind: optionValue}, "--verify": {Key: "verify", Kind: optionBoolTrue}, "--no-verify": {Key: "verify", Kind: optionBoolFalse},
	}
	a, ok := parseFor(c, "archive create", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, -1, "SOURCES..."); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	output, hasOutput := a.stringValue("output")
	if !hasOutput {
		usageError(c.stderr, fmt.Errorf("missing option: --output"))
		return berror.ExitUsage
	}
	excludes, err := readPatterns(a.multiValue("exclude_from"), a.multiValue("exclude"))
	if err != nil {
		return commandErr(c, err)
	}
	includes, err := readPatterns(a.multiValue("include_from"), a.multiValue("include"))
	if err != nil {
		return commandErr(c, err)
	}
	profile, explicitAlgorithm, err := compression(c, a)
	if err != nil {
		return commandErr(c, err)
	}
	split, err := a.int64Ptr("split_size")
	if err != nil {
		return commandErr(c, berror.Validation(err.Error(), nil))
	}
	spec := models.ArchiveCreateSpec{Sources: a.Positionals, OutputPath: output, Excludes: excludes, Includes: includes, SplitSize: split, Compression: profile, Verify: resolveVerify(c, a), DryRun: c.globals.DryRun}
	if c.globals.DryRun {
		plan, err := archivePlan(spec)
		if err != nil {
			return commandErr(c, err)
		}
		if err := c.render.plan("archive", plan); err != nil {
			return commandErr(c, err)
		}
		return berror.ExitOK
	}
	if c.globals.Verbose {
		c.render.verbose("executing archive create")
	}
	result, err := archiveio.Create(spec, !explicitAlgorithm, nil)
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "archive")
}

func archiveList(c *appContext, args []string) int {
	specs := map[string]optionSpec{"--filter": {Key: "filter", Kind: optionValue}, "--page": {Key: "page", Kind: optionValue}, "--page-size": {Key: "page_size", Kind: optionValue}}
	a, ok := parseFor(c, "archive list", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "ARCHIVE"); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	page, err := a.intValue("page", 0)
	if err != nil {
		return commandErr(c, berror.Validation(err.Error(), nil))
	}
	pageSize, err := a.intValue("page_size", 100)
	if err != nil {
		return commandErr(c, berror.Validation(err.Error(), nil))
	}
	var filter *string
	if raw, set := a.stringValue("filter"); set {
		filter = &raw
	}
	result, err := archiveio.List(a.Positionals[0], filter, page, pageSize)
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "archive_list")
}

func archiveInfo(c *appContext, args []string) int {
	a, ok := parseFor(c, "archive info", args, map[string]optionSpec{})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "ARCHIVE"); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	result, err := archiveio.Info(a.Positionals[0])
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "archive_info")
}

func archiveBrowse(c *appContext, args []string) int {
	specs := map[string]optionSpec{
		"-q": {Key: "query", Kind: optionValue}, "--query": {Key: "query", Kind: optionValue}, "-t": {Key: "type", Kind: optionValue}, "--type": {Key: "type", Kind: optionValue},
		"--min-size": {Key: "min_size", Kind: optionValue}, "--max-size": {Key: "max_size", Kind: optionValue}, "-l": {Key: "limit", Kind: optionValue}, "--limit": {Key: "limit", Kind: optionValue},
		"--offset": {Key: "offset", Kind: optionValue}, "-s": {Key: "sort", Kind: optionValue}, "--sort": {Key: "sort", Kind: optionValue}, "-o": {Key: "order", Kind: optionValue}, "--order": {Key: "order", Kind: optionValue},
	}
	a, ok := parseFor(c, "archive browse", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "ARCHIVE"); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	minSize, err := a.int64Ptr("min_size")
	if err != nil {
		return commandErr(c, berror.Validation(err.Error(), nil))
	}
	maxSize, err := a.int64Ptr("max_size")
	if err != nil {
		return commandErr(c, berror.Validation(err.Error(), nil))
	}
	limit, err := a.intValue("limit", 50)
	if err != nil {
		return commandErr(c, berror.Validation(err.Error(), nil))
	}
	offset, err := a.intValue("offset", 0)
	if err != nil {
		return commandErr(c, berror.Validation(err.Error(), nil))
	}
	query, querySet := a.stringValue("query")
	var queryPtr *string
	if querySet {
		queryPtr = &query
	}
	fileType, typeSet := a.stringValue("type")
	var typePtr *string
	if typeSet {
		typePtr = &fileType
	}
	sortBy, set := a.stringValue("sort")
	if !set {
		sortBy = "path"
	}
	order, set := a.stringValue("order")
	if !set {
		order = "asc"
	}
	result, err := archiveio.Browse(a.Positionals[0], archiveio.BrowseOptions{Query: queryPtr, FileType: typePtr, MinSize: minSize, MaxSize: maxSize, Limit: limit, Offset: offset, SortBy: sortBy, SortOrder: order})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "archive_browse")
}

func archiveVerify(c *appContext, args []string) int {
	a, ok := parseFor(c, "archive verify", args, map[string]optionSpec{})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "ARCHIVE"); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	result, err := archiveio.Verify(a.Positionals[0], []string{"sha256"})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "archive_verify")
}

func transferOptions() map[string]optionSpec {
	return map[string]optionSpec{"--verify": {Key: "verify", Kind: optionBoolTrue}, "--no-verify": {Key: "verify", Kind: optionBoolFalse}, "--overwrite": {Key: "overwrite", Kind: optionBoolTrue}}
}

func transferCommand(c *appContext, args []string, direction string) int {
	a, ok := parseFor(c, "transfer "+direction, args, transferOptions())
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 2, 2, "SOURCE DESTINATION"); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	spec := models.TransferSpec{Sources: []string{a.Positionals[0]}, Destination: a.Positionals[1], Direction: direction, Verify: resolveVerify(c, a), DryRun: c.globals.DryRun, Overwrite: a.Bools["overwrite"]}
	if c.globals.DryRun {
		plan, err := transferPlan(spec, "transfer "+direction)
		if err != nil {
			return commandErr(c, err)
		}
		if err := c.render.plan("transfer", plan); err != nil {
			return commandErr(c, err)
		}
		return berror.ExitOK
	}
	result, err := transferops.Run(spec, transferops.Options{RunStore: c.store})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "transfer")
}
func transferPush(c *appContext, args []string) int { return transferCommand(c, args, "push") }
func transferPull(c *appContext, args []string) int { return transferCommand(c, args, "pull") }

func restoreOptions() map[string]optionSpec {
	return map[string]optionSpec{
		"-d": {Key: "destination", Kind: optionValue}, "--destination": {Key: "destination", Kind: optionValue},
		"-o": {Key: "overwrite", Kind: optionBoolTrue}, "--overwrite": {Key: "overwrite", Kind: optionBoolTrue}, "-y": {Key: "yes", Kind: optionBoolTrue}, "--yes": {Key: "yes", Kind: optionBoolTrue},
		"--fresh-dir": {Key: "fresh_dir", Kind: optionBoolTrue}, "--no-fresh-dir": {Key: "fresh_dir", Kind: optionBoolFalse},
		"--verify": {Key: "verify", Kind: optionBoolTrue}, "--no-verify": {Key: "verify", Kind: optionBoolFalse},
	}
}

func restoreCommand(c *appContext, args []string, pick bool) int {
	command := "restore extract"
	min := 1
	label := "ARCHIVE"
	if pick {
		command = "restore pick"
		min = 2
		label = "ARCHIVE PATHS..."
	}
	a, ok := parseFor(c, command, args, restoreOptions())
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, min, -1, label); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	if !pick && len(a.Positionals) != 1 {
		usageError(c.stderr, fmt.Errorf("too many arguments for ARCHIVE"))
		return berror.ExitUsage
	}
	fresh := c.config.FreshRestoreDir
	if value, set := a.triBool("fresh_dir"); set {
		fresh = *value
	}
	verify := resolveVerify(c, a)
	var destination *string
	if raw, set := a.stringValue("destination"); set {
		destination = &raw
	} else if c.config.DefaultDestination != nil {
		value := *c.config.DefaultDestination
		destination = &value
	}
	overwrite := a.Bools["overwrite"]
	confirm := !overwrite
	if a.Bools["yes"] {
		confirm = false
	}
	paths := []string{}
	if pick {
		paths = append(paths, a.Positionals[1:]...)
	}
	spec := models.RestoreSpec{Archive: a.Positionals[0], Destination: destination, Paths: paths, Overwrite: overwrite, Verify: verify, DryRun: c.globals.DryRun, FreshDir: fresh, ConfirmOverwrite: confirm}
	if c.globals.DryRun {
		plan, err := restorePlan(spec)
		if err != nil {
			return commandErr(c, err)
		}
		plan.Command = command
		if err := c.render.plan("restore", plan); err != nil {
			return commandErr(c, err)
		}
		return berror.ExitOK
	}
	result, err := archiveio.ExtractRestore(spec, nil)
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "restore")
}
func restoreExtract(c *appContext, args []string) int { return restoreCommand(c, args, false) }
func restorePick(c *appContext, args []string) int    { return restoreCommand(c, args, true) }

func toolDetector(c *appContext) (*tools.Detector, error) {
	prefs := map[string]string{}
	candidates := map[string]*string{"archiver": c.config.PreferredArchiver, "compression": c.config.PreferredCompressor, "hashing": c.config.PreferredHasher, "transfer": c.config.PreferredTransfer}
	for key, value := range candidates {
		if value != nil {
			prefs[key] = *value
		}
	}
	return tools.New(prefs, environmentMap(), 2*time.Second)
}

func toolsDetect(c *appContext, args []string) int {
	a, ok := parseFor(c, "tools detect", args, map[string]optionSpec{})
	if !ok {
		return berror.ExitOK
	}
	names := a.Positionals
	if len(names) == 0 {
		names = []string{"zstd", "tar", "rsync", "rclone", "sha256sum"}
	}
	detector, err := toolDetector(c)
	if err != nil {
		return commandErr(c, err)
	}
	found, err := detector.DetectAll(names)
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, struct {
		Tools map[string]tools.Info `json:"tools"`
	}{Tools: found}, "tools_detect")
}

func toolsExplain(c *appContext, args []string) int {
	a, ok := parseFor(c, "tools explain", args, map[string]optionSpec{})
	if !ok {
		return berror.ExitOK
	}
	cats := a.Positionals
	if len(cats) == 0 {
		cats = []string{"archiver", "compression", "hashing", "transfer"}
	}
	detector, err := toolDetector(c)
	if err != nil {
		return commandErr(c, err)
	}
	preferred := map[string]any{}
	for _, cat := range cats {
		if cat != "archiver" && cat != "compression" && cat != "hashing" && cat != "transfer" {
			return commandErr(c, berror.Validation("Invalid tool preference category", map[string]any{"categories": cats}))
		}
		value, err := detector.Preferred(cat)
		if err != nil {
			return commandErr(c, err)
		}
		if value == "" {
			preferred[cat] = nil
		} else {
			preferred[cat] = value
		}
	}
	return renderResult(c, struct {
		Preferred map[string]any `json:"preferred"`
	}{Preferred: preferred}, "tools_explain")
}

type runSummary struct {
	RunID       string  `json:"run_id"`
	Operation   string  `json:"operation"`
	Status      string  `json:"status"`
	Phase       string  `json:"phase,omitempty"`
	JobID       string  `json:"job_id,omitempty"`
	JobRevision int64   `json:"job_revision,omitempty"`
	StartedAt   string  `json:"started_at"`
	CompletedAt *string `json:"completed_at"`
	SourceCount int     `json:"source_count"`
}
type runListResult struct {
	Runs       []runSummary `json:"runs"`
	TotalCount int          `json:"total_count"`
	Limit      int          `json:"limit"`
}

func runList(c *appContext, args []string) int {
	specs := map[string]optionSpec{"-l": {Key: "limit", Kind: optionValue}, "--limit": {Key: "limit", Kind: optionValue}, "-o": {Key: "operation", Kind: optionValue}, "--operation": {Key: "operation", Kind: optionValue}}
	a, ok := parseFor(c, "run list", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if len(a.Positionals) != 0 {
		usageError(c.stderr, fmt.Errorf("unexpected argument: %s", a.Positionals[0]))
		return berror.ExitUsage
	}
	limit, err := a.intValue("limit", 20)
	if err != nil {
		return commandErr(c, berror.Validation(err.Error(), nil))
	}
	operation, _ := a.stringValue("operation")
	if c.store == nil {
		return commandErr(c, berror.Validation("Run history is disabled", nil))
	}
	records, err := c.store.List(limit, operation)
	if err != nil {
		return commandErr(c, err)
	}
	count, err := c.store.Count(operation)
	if err != nil {
		return commandErr(c, err)
	}
	summaries := make([]runSummary, 0, len(records))
	for _, r := range records {
		summaries = append(summaries, runSummary{
			RunID: r.RunID, Operation: r.Operation, Status: r.Status, Phase: r.Phase, JobID: r.JobID, JobRevision: r.JobRevision,
			StartedAt: r.StartedAt, CompletedAt: r.CompletedAt, SourceCount: len(r.SourcePaths),
		})
	}
	return renderResult(c, runListResult{Runs: summaries, TotalCount: count, Limit: limit}, "run_list")
}

func runShow(c *appContext, args []string) int {
	a, ok := parseFor(c, "run show", args, map[string]optionSpec{})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "RUN_ID"); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	if c.store == nil {
		return commandErr(c, berror.Validation("Run history is disabled", nil))
	}
	record, err := c.store.Get(a.Positionals[0])
	if err != nil {
		return commandErr(c, err)
	}
	if record == nil {
		return commandErr(c, berror.NotFound("Run not found: "+a.Positionals[0], map[string]any{"run_id": a.Positionals[0]}))
	}
	var timing any
	if record.CompletedAt != nil {
		start, e1 := time.Parse("2006-01-02T15:04:05.999999999", record.StartedAt)
		end, e2 := time.Parse("2006-01-02T15:04:05.999999999", *record.CompletedAt)
		if e1 == nil && e2 == nil {
			timing = []any{record.StartedAt, end.Sub(start).Seconds()}
		}
	}
	payload := map[string]any{
		"run_id": record.RunID, "operation": record.Operation, "status": record.Status, "phase": record.Phase,
		"job_id": record.JobID, "job_revision": record.JobRevision, "timing": timing,
		"source_paths": record.SourcePaths, "destination_path": record.DestinationPath, "archive_path": record.ArchivePath,
		"files_count": record.FilesCount, "bytes_processed": record.BytesProcessed, "error_message": record.ErrorMessage,
		"spec_snapshot": record.SpecSnapshot, "operation_spec_snapshot": record.OperationSpecSnapshot,
	}
	return renderResult(c, payload, "run_show")
}

func uiStart(c *appContext, args []string) int {
	specs := map[string]optionSpec{"--mode": {Key: "mode", Kind: optionValue}}
	a, ok := parseFor(c, "ui start", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if len(a.Positionals) != 0 {
		usageError(c.stderr, fmt.Errorf("unexpected argument: %s", a.Positionals[0]))
		return berror.ExitUsage
	}
	mode, set := a.stringValue("mode")
	if !set {
		mode = "tui"
	}
	mode = strings.ToLower(strings.TrimSpace(mode))
	if mode != "tui" && mode != "interactive" {
		return commandErr(c, berror.Validation("Unsupported UI mode", map[string]any{"mode": mode, "valid": []string{"tui", "interactive"}}))
	}
	return runUIPlugin(c, mode)
}

// Keep filepath referenced here because the public parser intentionally accepts
// native path strings and delegates canonicalization to domain validation.
var _ = filepath.Separator
