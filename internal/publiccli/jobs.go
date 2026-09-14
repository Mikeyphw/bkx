package publiccli

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"bkx/internal/berror"
	"bkx/internal/models"
	"bkx/internal/runstore"
	"bkx/internal/services"
)

func jobCommonOptions() map[string]optionSpec {
	return map[string]optionSpec{
		"--name":                  {Key: "name", Kind: optionValue},
		"--description":           {Key: "description", Kind: optionValue},
		"--operation":             {Key: "operation", Kind: optionValue},
		"--params-json":           {Key: "params_json", Kind: optionValue},
		"--repository-ref":        {Key: "repository_ref", Kind: optionValue},
		"--credential-ref":        {Key: "credential_ref", Kind: optionMultiValue},
		"--tag":                   {Key: "tag", Kind: optionMultiValue},
		"--enabled":               {Key: "enabled", Kind: optionBoolTrue},
		"--disabled":              {Key: "enabled", Kind: optionBoolFalse},
		"--clear-tags":            {Key: "clear_tags", Kind: optionBoolTrue},
		"--clear-credential-refs": {Key: "clear_credential_refs", Kind: optionBoolTrue},
	}
}

func decodeParams(raw string) (map[string]any, error) {
	if strings.TrimSpace(raw) == "" {
		return map[string]any{}, nil
	}
	dec := json.NewDecoder(strings.NewReader(raw))
	dec.UseNumber()
	var out map[string]any
	if err := dec.Decode(&out); err != nil {
		return nil, berror.Validation("--params-json must be a JSON object", map[string]any{"reason": err.Error()})
	}
	if out == nil {
		return nil, berror.Validation("--params-json must be a JSON object", nil)
	}
	return normalizeJSONNumbers(out).(map[string]any), nil
}

func normalizeJSONNumbers(v any) any {
	switch x := v.(type) {
	case json.Number:
		if i, err := x.Int64(); err == nil {
			return i
		}
		if f, err := x.Float64(); err == nil {
			return f
		}
		return x.String()
	case map[string]any:
		for k, v := range x {
			x[k] = normalizeJSONNumbers(v)
		}
		return x
	case []any:
		for i := range x {
			x[i] = normalizeJSONNumbers(x[i])
		}
		return x
	default:
		return v
	}
}

func makeOperation(a leafArgs, fallback *models.OperationSpecV2) (models.OperationSpecV2, error) {
	var spec models.OperationSpecV2
	if fallback != nil {
		spec = *fallback
		spec.Parameters = cloneMap(spec.Parameters)
	} else {
		spec = models.NewOperationSpecV2("backup.create", map[string]any{})
	}
	if op, ok := a.stringValue("operation"); ok {
		spec.Operation = strings.TrimSpace(op)
	}
	if raw, ok := a.stringValue("params_json"); ok {
		p, err := decodeParams(raw)
		if err != nil {
			return spec, err
		}
		spec.Parameters = p
	}
	if repo, ok := a.stringValue("repository_ref"); ok {
		repo = strings.TrimSpace(repo)
		if repo == "" {
			spec.RepositoryRef = nil
		} else {
			spec.RepositoryRef = &repo
		}
	}
	if refs := a.multiValue("credential_ref"); len(refs) > 0 {
		spec.CredentialRefs = make([]models.SecretReference, 0, len(refs))
		for _, ref := range refs {
			spec.CredentialRefs = append(spec.CredentialRefs, models.SecretReference{ID: strings.TrimSpace(ref)})
		}
	}
	if err := validateExecutableOperation(spec); err != nil {
		return spec, err
	}
	return spec, nil
}

func cloneMap(in map[string]any) map[string]any {
	data, _ := json.Marshal(in)
	var out map[string]any
	_ = json.Unmarshal(data, &out)
	if out == nil {
		out = map[string]any{}
	}
	return out
}

func jobCreate(c *appContext, args []string) int {
	a, ok := parseFor(c, "job create", args, jobCommonOptions())
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "JOB_ID"); err != nil {
		return cliUsageError(c, err)
	}
	jobID := a.Positionals[0]
	if _, set := a.stringValue("operation"); !set {
		return commandErr(c, berror.Validation("job create requires --operation", map[string]any{"job_id": jobID}))
	}
	name, _ := a.stringValue("name")
	if name == "" {
		name = jobID
	}
	op, err := makeOperation(a, nil)
	if err != nil {
		return commandErr(c, err)
	}
	enabled := true
	if v, set := a.triBool("enabled"); set {
		enabled = *v
	}
	desc, _ := a.stringValue("description")
	created, err := c.jobs.Create(models.SavedJob{JobID: jobID, Name: name, Description: desc, Tags: a.multiValue("tag"), Enabled: enabled, Operation: op})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, created, "job")
}

func jobList(c *appContext, args []string) int {
	a, ok := parseFor(c, "job list", args, map[string]optionSpec{})
	if !ok {
		return berror.ExitOK
	}
	if len(a.Positionals) > 0 {
		return cliUsageError(c, fmt.Errorf("unexpected argument: %s", a.Positionals[0]))
	}
	jobs, err := c.jobs.List()
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, struct {
		Jobs  []models.SavedJob `json:"jobs"`
		Total int               `json:"total"`
	}{jobs, len(jobs)}, "job_list")
}

func getJob(c *appContext, id string) (*models.SavedJob, int) {
	j, err := c.jobs.Get(id)
	if err != nil {
		return nil, commandErr(c, err)
	}
	if j == nil {
		return nil, commandErr(c, berror.NotFound("Saved job not found: "+id, map[string]any{"job_id": id}))
	}
	return j, -1
}

func jobShow(c *appContext, args []string) int {
	a, ok := parseFor(c, "job show", args, map[string]optionSpec{})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "JOB_ID"); err != nil {
		return cliUsageError(c, err)
	}
	j, code := getJob(c, a.Positionals[0])
	if j == nil {
		return code
	}
	return renderResult(c, j, "job")
}

func jobEdit(c *appContext, args []string) int {
	specs := jobCommonOptions()
	specs["--expected-revision"] = optionSpec{Key: "expected_revision", Kind: optionValue}
	a, ok := parseFor(c, "job edit", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "JOB_ID"); err != nil {
		return cliUsageError(c, err)
	}
	cur, code := getJob(c, a.Positionals[0])
	if cur == nil {
		return code
	}
	repl := *cur
	if v, set := a.stringValue("name"); set {
		repl.Name = v
	}
	if v, set := a.stringValue("description"); set {
		repl.Description = v
	}
	if v, set := a.triBool("enabled"); set {
		repl.Enabled = *v
	}
	if a.Bools["clear_tags"] {
		repl.Tags = nil
	} else if tags := a.multiValue("tag"); len(tags) > 0 {
		repl.Tags = tags
	}
	op, err := makeOperation(a, &cur.Operation)
	if err != nil {
		return commandErr(c, err)
	}
	repl.Operation = op
	expected := cur.Revision
	if raw, set := a.stringValue("expected_revision"); set {
		v, e := strconv.ParseInt(raw, 10, 64)
		if e != nil {
			return commandErr(c, berror.Validation("expected revision must be an integer", nil))
		}
		expected = v
	}
	updated, err := c.jobs.Update(cur.JobID, expected, repl)
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, updated, "job")
}

func jobClone(c *appContext, args []string) int {
	a, ok := parseFor(c, "job clone", args, map[string]optionSpec{"--name": {Key: "name", Kind: optionValue}})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 2, 2, "SOURCE_JOB_ID TARGET_JOB_ID"); err != nil {
		return cliUsageError(c, err)
	}
	src, code := getJob(c, a.Positionals[0])
	if src == nil {
		return code
	}
	clone := *src
	clone.JobID = a.Positionals[1]
	clone.Revision = 0
	clone.CreatedAt = ""
	clone.UpdatedAt = ""
	if name, set := a.stringValue("name"); set {
		clone.Name = name
	} else {
		clone.Name = a.Positionals[1]
	}
	created, err := c.jobs.Create(clone)
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, created, "job")
}

func jobDelete(c *appContext, args []string) int {
	a, ok := parseFor(c, "job delete", args, map[string]optionSpec{})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "JOB_ID"); err != nil {
		return cliUsageError(c, err)
	}
	removedSchedules, schedErr := c.schedules.DeleteForJob(a.Positionals[0])
	if schedErr != nil {
		return commandErr(c, schedErr)
	}
	deleted, err := c.jobs.Delete(a.Positionals[0])
	if err != nil {
		return commandErr(c, err)
	}
	if !deleted {
		return commandErr(c, berror.NotFound("Saved job not found: "+a.Positionals[0], map[string]any{"job_id": a.Positionals[0]}))
	}
	if schedules, scheduleErr := c.schedules.List(); scheduleErr != nil {
		return commandErr(c, scheduleErr)
	} else {
		for _, schedule := range schedules {
			if schedule.JobID == a.Positionals[0] {
				if _, scheduleErr := c.schedules.Delete(schedule.ScheduleID); scheduleErr != nil {
					return commandErr(c, scheduleErr)
				}
			}
		}
	}
	notifyAndroidScheduler()
	return renderResult(c, map[string]any{"job_id": a.Positionals[0], "deleted": true, "schedules_removed": removedSchedules}, "job_delete")
}

func jobExport(c *appContext, args []string) int {
	a, ok := parseFor(c, "job export", args, map[string]optionSpec{"--output": {Key: "output", Kind: optionValue}})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "JOB_ID"); err != nil {
		return cliUsageError(c, err)
	}
	j, code := getJob(c, a.Positionals[0])
	if j == nil {
		return code
	}
	data, _ := json.MarshalIndent(j, "", "  ")
	data = append(data, '\n')
	if out, set := a.stringValue("output"); set {
		path, err := canonicalOptionalPath(out)
		if err != nil {
			return commandErr(c, err)
		}
		if err := safeWriteExport(path, data); err != nil {
			return commandErr(c, err)
		}
		return renderResult(c, map[string]any{"job_id": j.JobID, "output": path}, "job_export")
	}
	fmt.Fprint(c.stdout, string(data))
	return berror.ExitOK
}

func jobImport(c *appContext, args []string) int {
	a, ok := parseFor(c, "job import", args, map[string]optionSpec{"--job-id": {Key: "job_id", Kind: optionValue}, "--name": {Key: "name", Kind: optionValue}})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "FILE"); err != nil {
		return cliUsageError(c, err)
	}
	path, err := canonicalOptionalPath(a.Positionals[0])
	if err != nil {
		return commandErr(c, err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return commandErr(c, berror.WrapExternal("reading job import", path, "filesystem", err))
	}
	var j models.SavedJob
	if err := json.Unmarshal(data, &j); err != nil {
		return commandErr(c, berror.Validation("job import is not valid JSON", map[string]any{"reason": err.Error()}))
	}
	if v, set := a.stringValue("job_id"); set {
		j.JobID = v
	}
	if v, set := a.stringValue("name"); set {
		j.Name = v
	}
	j.Revision = 0
	j.CreatedAt = ""
	j.UpdatedAt = ""
	if err := validateExecutableOperation(j.Operation); err != nil {
		return commandErr(c, err)
	}
	created, err := c.jobs.Create(j)
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, created, "job")
}

func applySets(params map[string]any, values []string) error {
	for _, raw := range values {
		pair := strings.SplitN(raw, "=", 2)
		if len(pair) != 2 || strings.TrimSpace(pair[0]) == "" {
			return berror.Validation("--set must use KEY=VALUE", map[string]any{"value": raw})
		}
		var value any
		if err := json.Unmarshal([]byte(pair[1]), &value); err != nil {
			value = pair[1]
		}
		keys := strings.Split(pair[0], ".")
		m := params
		for _, k := range keys[:len(keys)-1] {
			next, ok := m[k].(map[string]any)
			if !ok {
				next = map[string]any{}
				m[k] = next
			}
			m = next
		}
		m[keys[len(keys)-1]] = value
	}
	return nil
}

func jobRevisions(c *appContext, args []string) int {
	a, ok := parseFor(c, "job revisions", args, map[string]optionSpec{})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "JOB_ID"); err != nil {
		return cliUsageError(c, err)
	}
	revisions, err := c.jobs.Revisions(a.Positionals[0])
	if err != nil {
		return commandErr(c, err)
	}
	if len(revisions) == 0 {
		return commandErr(c, berror.NotFound("Saved job not found: "+a.Positionals[0], map[string]any{"job_id": a.Positionals[0]}))
	}
	return renderResult(c, map[string]any{"job_id": a.Positionals[0], "revisions": revisions, "total": len(revisions)}, "job_revisions")
}

func jobRun(c *appContext, args []string) int {
	specs := map[string]optionSpec{"--set": {Key: "set", Kind: optionMultiValue}, "--request-id": {Key: "request_id", Kind: optionValue}, "--dry-run": {Key: "dry_run", Kind: optionBoolTrue}}
	a, ok := parseFor(c, "job run", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "JOB_ID"); err != nil {
		return cliUsageError(c, err)
	}
	j, code := getJob(c, a.Positionals[0])
	if j == nil {
		return code
	}
	if !j.Enabled {
		return commandErr(c, berror.Validation("Saved job is disabled", map[string]any{"job_id": j.JobID}))
	}
	requestID, set := a.stringValue("request_id")
	if !set || strings.TrimSpace(requestID) == "" {
		requestID = fmt.Sprintf("cli-job-%d-%d", os.Getpid(), time.Now().UnixNano())
	}
	if err := validateJobOverrideKeys(j.Operation.Operation, a.multiValue("set")); err != nil {
		return commandErr(c, err)
	}
	plan := c.globals.DryRun || a.Bools["dry_run"]
	payload, _ := json.Marshal(map[string]any{"job_id": j.JobID, "overrides": a.multiValue("set")})
	d := services.NewWithStores(c.store, c.repositories, c.jobs)
	operation := "jobs.run"
	renderKind := "job_run"
	if plan {
		operation = "jobs.plan"
		renderKind = "job_plan"
	}
	result, err := d.Dispatch(services.OperationContext{RequestID: requestID, Owner: "cli"}, operation, payload)
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, renderKind)
}
func jobPlan(c *appContext, args []string) int {
	specs := map[string]optionSpec{"--set": {Key: "set", Kind: optionMultiValue}}
	a, ok := parseFor(c, "job plan", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "JOB_ID"); err != nil {
		return cliUsageError(c, err)
	}
	j, code := getJob(c, a.Positionals[0])
	if j == nil {
		return code
	}
	if err := validateJobOverrideKeys(j.Operation.Operation, a.multiValue("set")); err != nil {
		return commandErr(c, err)
	}
	payload, _ := json.Marshal(map[string]any{"job_id": j.JobID, "overrides": a.multiValue("set")})
	d := services.NewWithStores(c.store, c.repositories, c.jobs)
	result, err := d.Dispatch(services.OperationContext{Owner: "cli"}, "jobs.plan", payload)
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "job_plan")
}

func replayOperation(record models.RunRecord) (models.OperationSpecV2, error) {
	return runstore.ReplayOperation(record)
}

func runReplay(c *appContext, args []string) int {
	specs := map[string]optionSpec{
		"--set":                   {Key: "set", Kind: optionMultiValue},
		"--clear-tags":            {Key: "clear_tags", Kind: optionBoolTrue},
		"--clear-credential-refs": {Key: "clear_credential_refs", Kind: optionBoolTrue},
		"--request-id":            {Key: "request_id", Kind: optionValue},
		"--dry-run":               {Key: "dry_run", Kind: optionBoolTrue},
	}
	a, ok := parseFor(c, "run replay", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "RUN_ID"); err != nil {
		return cliUsageError(c, err)
	}
	if c.store == nil {
		return commandErr(c, berror.Validation("Run history is disabled", nil))
	}
	requestID, set := a.stringValue("request_id")
	if !set || strings.TrimSpace(requestID) == "" {
		requestID = fmt.Sprintf("cli-replay-%d-%d", os.Getpid(), time.Now().UnixNano())
	}
	payload, _ := json.Marshal(map[string]any{
		"run_id":    a.Positionals[0],
		"overrides": a.multiValue("set"),
		"dry_run":   c.globals.DryRun || a.Bools["dry_run"],
	})
	d := services.NewWithStores(c.store, c.repositories, c.jobs)
	result, err := d.Dispatch(services.OperationContext{RequestID: requestID, Owner: "cli"}, "runs.replay", payload)
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, map[bool]string{true: "job_plan", false: "job_run"}[c.globals.DryRun || a.Bools["dry_run"]])
}

func jobCreateFromRun(c *appContext, args []string) int {
	specs := map[string]optionSpec{"--name": {Key: "name", Kind: optionValue}, "--description": {Key: "description", Kind: optionValue}, "--tag": {Key: "tag", Kind: optionMultiValue}}
	a, ok := parseFor(c, "job create-from-run", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 2, 2, "RUN_ID JOB_ID"); err != nil {
		return cliUsageError(c, err)
	}
	if c.store == nil {
		return commandErr(c, berror.Validation("Run history is disabled", nil))
	}
	r, err := c.store.Get(a.Positionals[0])
	if err != nil {
		return commandErr(c, err)
	}
	if r == nil {
		return commandErr(c, berror.NotFound("Run not found: "+a.Positionals[0], map[string]any{"run_id": a.Positionals[0]}))
	}
	op, err := replayOperation(*r)
	if err != nil {
		return commandErr(c, err)
	}
	name, _ := a.stringValue("name")
	if name == "" {
		name = a.Positionals[1]
	}
	desc, _ := a.stringValue("description")
	created, err := c.jobs.Create(models.SavedJob{JobID: a.Positionals[1], Name: name, Description: desc, Tags: a.multiValue("tag"), Enabled: true, Operation: op})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, created, "job")
}

func safeWriteExport(path string, data []byte) error {
	clean, err := filepath.Abs(strings.TrimSpace(path))
	if err != nil || strings.TrimSpace(path) == "" {
		return berror.Validation("invalid job export path", map[string]any{"path": path})
	}
	if info, err := os.Lstat(clean); err == nil {
		if info.Mode()&os.ModeSymlink != 0 {
			return berror.Validation("job export refuses to overwrite a symbolic link", map[string]any{"path": clean})
		}
		return berror.Validation("job export refuses to overwrite an existing file", map[string]any{"path": clean})
	} else if !os.IsNotExist(err) {
		return berror.WrapExternal("checking job export target", clean, "filesystem", err)
	}
	f, err := os.OpenFile(clean, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return berror.WrapExternal("creating job export", clean, "filesystem", err)
	}
	defer f.Close()
	if _, err := f.Write(data); err != nil {
		return berror.WrapExternal("writing job export", clean, "filesystem", err)
	}
	return f.Sync()
}

func validateExecutableOperation(op models.OperationSpecV2) error {
	if err := models.ValidateOperationSpecV2(op); err != nil {
		return berror.Validation("Invalid operation spec", map[string]any{"reason": err.Error()})
	}
	allowed := map[string]bool{
		"archive.create": true, "backup.create": true, "backup.snapshot": true, "repository.check": true, "repository.prune": true,
		"retention.apply": true, "restore.extract": true, "snapshots.restore": true, "transfer.run": true,
	}
	if !allowed[op.Operation] {
		return berror.Validation("operation is not allowed in saved jobs", map[string]any{"operation": op.Operation})
	}
	return nil
}

func validateJobOverrideKeys(operation string, sets []string) error {
	for _, raw := range sets {
		key := strings.TrimSpace(strings.SplitN(raw, "=", 2)[0])
		if key == "" || strings.Contains(key, "..") || strings.ContainsAny(key, "/\\") {
			return berror.Validation("job override key is invalid", map[string]any{"key": key})
		}
		lower := strings.ToLower(strings.ReplaceAll(strings.ReplaceAll(key, "-", "_"), ".", "_"))
		for _, token := range []string{"secret", "password", "token", "credential", "client_secret", "private_key", "session", "cookie", "bearer", "auth"} {
			if strings.Contains(lower, token) {
				return berror.Validation("job overrides cannot change secret or credential fields", map[string]any{"operation": operation, "key": key})
			}
		}
		if lower == "operation" || lower == "schema_version" || lower == "repository_ref" {
			return berror.Validation("job override key targets immutable operation metadata", map[string]any{"operation": operation, "key": key})
		}
	}
	return nil
}
