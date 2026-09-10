package publiccli

import (
	"os"

	"bkx/internal/berror"
	"bkx/internal/statepaths"
)

func stateStatus(c *appContext, args []string) int {
	if len(args) != 0 {
		usageError(c.stderr, os.ErrInvalid)
		return berror.ExitUsage
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return commandErr(c, err)
	}
	resolution := statepaths.Resolve(home)
	payload := map[string]any{
		"root":                 resolution.Root,
		"source":               resolution.Source,
		"shared":               resolution.Shared,
		"jobs":                 statepaths.JobStorePath(home),
		"repositories":         statepaths.RepositoryStorePath(home),
		"schedules":            statepaths.ScheduleStorePath(home),
		"execution_control":    statepaths.ExecutionControlPath(home),
		"legacy_jobs":          statepaths.LegacyJobStorePath(home),
		"legacy_repositories":  statepaths.LegacyRepositoryStorePath(home),
		"android_shared_root":  statepaths.AndroidSharedRoot(),
		"environment_override": statepaths.EnvStateDir,
	}
	if c.control != nil {
		if active, activeErr := c.control.Active(); activeErr == nil {
			payload["active_executions"] = active
		}
	}
	return renderResult(c, payload, "state")
}
