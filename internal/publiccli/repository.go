package publiccli

import (
	"strings"

	"bkx/internal/berror"
	"bkx/internal/models"
	"bkx/internal/resticops"
)

func repositoryInit(c *appContext, args []string) int {
	specs := map[string]optionSpec{
		"--engine":         {Key: "engine", Kind: optionValue},
		"--credential-ref": {Key: "credential_ref", Kind: optionValue},
	}
	a, ok := parseFor(c, "repository init", args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "REPOSITORY"); err != nil {
		usageError(c.stderr, err)
		return berror.ExitUsage
	}
	engine, _ := a.stringValue("engine")
	engine = strings.ToLower(strings.TrimSpace(engine))
	if engine == "" {
		engine = models.BackupEngineRestic
	}
	if engine != models.BackupEngineRestic {
		return commandErr(c, berror.Validation("repository init currently supports the restic engine", map[string]any{"engine": engine}))
	}
	var credentialRef *string
	if raw, set := a.stringValue("credential_ref"); set {
		raw = strings.TrimSpace(raw)
		credentialRef = &raw
	}
	result, err := resticops.Init(models.RepositoryInitSpec{Engine: engine, Repository: a.Positionals[0], CredentialRef: credentialRef}, resticops.Options{})
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, result, "repository")
}
