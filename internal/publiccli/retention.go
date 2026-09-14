package publiccli

import (
	"strings"

	"bkx/internal/berror"
	"bkx/internal/models"
	"bkx/internal/resticops"
)

func intPtrOption(a leafArgs, key string) (*int, error) {
	raw, set := a.stringValue(key)
	if !set {
		return nil, nil
	}
	value, err := a.intValue(key, 0)
	if err != nil {
		return nil, berror.Validation(err.Error(), nil)
	}
	_ = raw
	return &value, nil
}

func retentionSpecs() map[string]optionSpec {
	return map[string]optionSpec{
		"--keep-last": {Key: "keep_last", Kind: optionValue}, "--keep-hourly": {Key: "keep_hourly", Kind: optionValue}, "--keep-daily": {Key: "keep_daily", Kind: optionValue}, "--keep-weekly": {Key: "keep_weekly", Kind: optionValue}, "--keep-monthly": {Key: "keep_monthly", Kind: optionValue}, "--keep-yearly": {Key: "keep_yearly", Kind: optionValue}, "--keep-within": {Key: "keep_within", Kind: optionValue}, "--keep-tag": {Key: "keep_tag", Kind: optionMultiValue}, "--group-by": {Key: "group_by", Kind: optionValue},
	}
}

func retentionSet(c *appContext, args []string) int {
	a, ok := parseFor(c, "retention set", args, retentionSpecs())
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
	var err error
	policy := models.RetentionPolicy{SchemaVersion: models.RetentionPolicySchemaVersion, KeepTags: a.multiValue("keep_tag")}
	if policy.KeepLast, err = intPtrOption(a, "keep_last"); err != nil {
		return commandErr(c, err)
	}
	if policy.KeepHourly, err = intPtrOption(a, "keep_hourly"); err != nil {
		return commandErr(c, err)
	}
	if policy.KeepDaily, err = intPtrOption(a, "keep_daily"); err != nil {
		return commandErr(c, err)
	}
	if policy.KeepWeekly, err = intPtrOption(a, "keep_weekly"); err != nil {
		return commandErr(c, err)
	}
	if policy.KeepMonthly, err = intPtrOption(a, "keep_monthly"); err != nil {
		return commandErr(c, err)
	}
	if policy.KeepYearly, err = intPtrOption(a, "keep_yearly"); err != nil {
		return commandErr(c, err)
	}
	if raw, set := a.stringValue("keep_within"); set {
		raw = strings.TrimSpace(raw)
		policy.KeepWithin = &raw
	}
	policy.GroupBy, _ = a.stringValue("group_by")
	policy.GroupBy = strings.TrimSpace(policy.GroupBy)
	if err := models.ValidateRetentionPolicy(policy); err != nil {
		return commandErr(c, berror.Validation("invalid retention policy", map[string]any{"reason": err.Error()}))
	}
	replacement := *def
	replacement.Retention = &policy
	if c.globals.DryRun {
		return renderResult(c, map[string]any{"success": true, "dry_run": true, "repository_id": def.RepositoryID, "policy": policy}, "retention")
	}
	updated, err := c.repositories.Update(def.RepositoryID, replacement)
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, updated, "retention")
}

func retentionShow(c *appContext, args []string) int {
	a, ok := parseFor(c, "retention show", args, map[string]optionSpec{})
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
	if def.Retention == nil {
		return commandErr(c, berror.NotFound("No retention policy is configured for repository "+def.RepositoryID, map[string]any{"repository_id": def.RepositoryID}))
	}
	return renderResult(c, struct {
		RepositoryID string                  `json:"repository_id"`
		Policy       *models.RetentionPolicy `json:"policy"`
	}{def.RepositoryID, def.Retention}, "retention")
}

func retentionClear(c *appContext, args []string) int {
	a, ok := parseFor(c, "retention clear", args, map[string]optionSpec{})
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
	replacement := *def
	replacement.Retention = nil
	if c.globals.DryRun {
		return renderResult(c, map[string]any{"success": true, "dry_run": true, "repository_id": def.RepositoryID, "cleared": true}, "retention")
	}
	updated, err := c.repositories.Update(def.RepositoryID, replacement)
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, updated, "retention")
}

func retentionApply(c *appContext, args []string) int {
	a, ok := parseFor(c, "retention apply", args, map[string]optionSpec{"--confirm": {Key: "confirm", Kind: optionBoolTrue}})
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
	if def.Retention == nil {
		return commandErr(c, berror.NotFound("No retention policy is configured for repository "+def.RepositoryID, nil))
	}
	confirm := false
	if v, ok := a.triBool("confirm"); ok && v != nil {
		confirm = *v
	}
	if !c.globals.DryRun && !confirm {
		return commandErr(c, berror.Validation("retention apply requires --confirm unless --dry-run is active", map[string]any{"action": "Run with --dry-run first, then rerun with --confirm."}))
	}
	var options resticops.Options
	var lease *cliExecutionLease
	var err error
	if !c.globals.DryRun {
		lease, err = beginCLIExecution(c, "retention.apply", "", def.RepositoryID)
		if err != nil {
			return commandErr(c, err)
		}
		defer lease.Close()
		options.Cancelled = lease.Cancelled
	}
	result, err := resticops.ApplyRetention(models.RepositoryAccessSpec{Repository: def.Location, CredentialRef: def.CredentialRef}, *def.Retention, c.globals.DryRun, options)
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "retention_apply")
}
