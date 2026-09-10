package publiccli

import (
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"strings"

	"bkx/internal/backupops"
	"bkx/internal/berror"
	"bkx/internal/models"
	"bkx/internal/resticops"
	"bkx/internal/services"
	"bkx/internal/transferops"
)

func jobCommonOptions() map[string]optionSpec {
	return map[string]optionSpec{
		"--name":           {Key: "name", Kind: optionValue},
		"--description":    {Key: "description", Kind: optionValue},
		"--operation":      {Key: "operation", Kind: optionValue},
		"--params-json":    {Key: "params_json", Kind: optionValue},
		"--repository-ref": {Key: "repository_ref", Kind: optionValue},
		"--credential-ref": {Key: "credential_ref", Kind: optionMultiValue},
		"--tag":            {Key: "tag", Kind: optionMultiValue},
		"--enabled":        {Key: "enabled", Kind: optionBoolTrue},
		"--disabled":       {Key: "enabled", Kind: optionBoolFalse},
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
	if err := models.ValidateOperationSpecV2(spec); err != nil {
		return spec, berror.Validation("invalid saved operation", map[string]any{"reason": err.Error()})
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
		usageError(c.stderr, err)
		return berror.ExitUsage
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
		usageError(c.stderr, fmt.Errorf("unexpected argument: %s", a.Positionals[0]))
		return berror.ExitUsage
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
		usageError(c.stderr, err)
		return berror.ExitUsage
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
		usageError(c.stderr, err)
		return berror.ExitUsage
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
	if tags := a.multiValue("tag"); len(tags) > 0 {
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
		usageError(c.stderr, err)
		return berror.ExitUsage
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
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	deleted, err := c.jobs.Delete(a.Positionals[0])
	if err != nil {
		return commandErr(c, err)
	}
	if !deleted {
		return commandErr(c, berror.NotFound("Saved job not found: "+a.Positionals[0], map[string]any{"job_id": a.Positionals[0]}))
	}
	return renderResult(c, map[string]any{"job_id": a.Positionals[0], "deleted": true}, "job_delete")
}

func jobExport(c *appContext, args []string) int {
	a, ok := parseFor(c, "job export", args, map[string]optionSpec{"--output": {Key: "output", Kind: optionValue}})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "JOB_ID"); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
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
		if err := os.WriteFile(path, data, 0600); err != nil {
			return commandErr(c, berror.WrapExternal("writing job export", path, "filesystem", err))
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
		usageError(c.stderr, err)
		return berror.ExitUsage
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

func dispatchSaved(c *appContext, op models.OperationSpecV2, sets []string, plan bool) int {
	params := cloneMap(op.Parameters)
	if err := applySets(params, sets); err != nil {
		return commandErr(c, err)
	}
	raw, err := json.Marshal(params)
	if err != nil {
		return commandErr(c, berror.Validation("job parameters are not JSON serializable", nil))
	}
	// Saved CLI jobs use the canonical Go operation models. This preserves
	// multi-source backup semantics and lets historical SpecSnapshots replay
	// without translating them through the Android bridge payload shape.
	switch op.Operation {
	case "backup.create":
		var spec models.BackupSpec
		if err := json.Unmarshal(raw, &spec); err != nil {
			return commandErr(c, berror.Validation("invalid backup job parameters", map[string]any{"reason": err.Error()}))
		}
		if plan {
			spec.DryRun = true
			result, err := backupops.PlanBackup(spec)
			if err != nil {
				return commandErr(c, err)
			}
			return renderResult(c, result, "job_plan")
		}
		spec.DryRun = c.globals.DryRun
		result, err := backupops.Create(spec, false, backupops.Options{RunStore: c.store})
		if err != nil {
			return commandErr(c, err)
		}
		return renderResult(c, result, "job_run")
	case "backup.snapshot":
		var spec models.SnapshotBackupSpec
		if err := json.Unmarshal(raw, &spec); err != nil {
			return commandErr(c, berror.Validation("invalid snapshot backup job parameters", map[string]any{"reason": err.Error()}))
		}
		if spec.Engine == "" {
			spec.Engine = models.BackupEngineRestic
		}
		if spec.CredentialRef == nil && len(op.CredentialRefs) > 0 {
			ref := op.CredentialRefs[0].ID
			spec.CredentialRef = &ref
		}
		if plan || c.globals.DryRun {
			spec.DryRun = true
			result, err := resticops.PlanBackup(spec)
			if err != nil {
				return commandErr(c, err)
			}
			return renderResult(c, result, "job_plan")
		}
		result, err := resticops.Backup(spec, resticops.Options{RunStore: c.store})
		if err != nil {
			return commandErr(c, err)
		}
		return renderResult(c, result, "job_run")
	case "transfer.run":
		var spec models.TransferSpec
		if err := json.Unmarshal(raw, &spec); err != nil {
			return commandErr(c, berror.Validation("invalid transfer job parameters", map[string]any{"reason": err.Error()}))
		}
		if plan || c.globals.DryRun {
			spec.DryRun = true
		}
		result, err := transferops.Run(spec, transferops.Options{RunStore: c.store})
		if err != nil {
			return commandErr(c, err)
		}
		return renderResult(c, result, map[bool]string{true: "job_plan", false: "job_run"}[plan])
	}
	operation := op.Operation
	if plan {
		switch operation {
		case "backup.create":
			operation = "backup.plan"
		case "transfer.run":
			operation = "transfer.plan"
		}
	}
	d := services.New(c.store)
	result, err := d.Dispatch(services.OperationContext{}, operation, raw)
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, map[bool]string{true: "job_plan", false: "job_run"}[plan])
}

func jobRun(c *appContext, args []string) int {
	specs := map[string]optionSpec{"--set": {Key: "set", Kind: optionMultiValue}}
	a, ok := parseFor(c, "job run", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "JOB_ID"); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	j, code := getJob(c, a.Positionals[0])
	if j == nil {
		return code
	}
	if !j.Enabled {
		return commandErr(c, berror.Validation("Saved job is disabled", map[string]any{"job_id": j.JobID}))
	}
	return dispatchSaved(c, j.Operation, a.multiValue("set"), false)
}
func jobPlan(c *appContext, args []string) int {
	specs := map[string]optionSpec{"--set": {Key: "set", Kind: optionMultiValue}}
	a, ok := parseFor(c, "job plan", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "JOB_ID"); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	j, code := getJob(c, a.Positionals[0])
	if j == nil {
		return code
	}
	return dispatchSaved(c, j.Operation, a.multiValue("set"), true)
}

func replayOperation(record models.RunRecord) (models.OperationSpecV2, error) {
	values := record.SpecSnapshot
	if typed, ok := values["values"].(map[string]any); ok {
		values = cloneMap(typed)
	}
	delete(values, "_resolved_destination")
	delete(values, "_archive_path")
	op := ""
	switch record.Operation {
	case "backup":
		if engine, _ := values["engine"].(string); strings.EqualFold(engine, models.BackupEngineRestic) {
			op = "backup.snapshot"
		} else {
			op = "backup.create"
		}
	case "transfer":
		op = "transfer.run"
	default:
		return models.OperationSpecV2{}, berror.Validation("historical operation is not replayable yet", map[string]any{"operation": record.Operation})
	}
	spec := models.NewOperationSpecV2(op, values)
	if op == "backup.snapshot" {
		if rawRef, ok := values["credential_ref"].(string); ok && strings.TrimSpace(rawRef) != "" {
			delete(spec.Parameters, "credential_ref")
			spec.CredentialRefs = []models.SecretReference{{ID: strings.TrimSpace(rawRef), Purpose: "restic repository password"}}
		}
	}
	if err := models.ValidateOperationSpecV2(spec); err != nil {
		return spec, berror.Validation("historical spec snapshot cannot be replayed", map[string]any{"reason": err.Error()})
	}
	return spec, nil
}

func runReplay(c *appContext, args []string) int {
	a, ok := parseFor(c, "run replay", args, map[string]optionSpec{"--set": {Key: "set", Kind: optionMultiValue}})
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
	return dispatchSaved(c, op, a.multiValue("set"), false)
}

func jobCreateFromRun(c *appContext, args []string) int {
	specs := map[string]optionSpec{"--name": {Key: "name", Kind: optionValue}, "--description": {Key: "description", Kind: optionValue}, "--tag": {Key: "tag", Kind: optionMultiValue}}
	a, ok := parseFor(c, "job create-from-run", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 2, 2, "RUN_ID JOB_ID"); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
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
