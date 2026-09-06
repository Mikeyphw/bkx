package publiccli

import (
	"fmt"
	"os"
	"os/exec"

	"bkx/internal/berror"
)

type uiPlugin struct {
	binary      string
	envOverride string
}

var uiPlugins = map[string]uiPlugin{
	"tui":         {binary: "bkx-tui", envOverride: "BKX_TUI_COMMAND"},
	"interactive": {binary: "bkx-interactive", envOverride: "BKX_INTERACTIVE_COMMAND"},
}

// runUIPlugin keeps optional frontends outside the production BKX binary. The
// override is deliberately a single executable/path, never a shell command.
func runUIPlugin(c *appContext, mode string) int {
	plugin := uiPlugins[mode]
	requested := os.Getenv(plugin.envOverride)
	if requested == "" {
		requested = plugin.binary
	}
	path, err := exec.LookPath(requested)
	if err != nil {
		feature := mode
		extra := mode
		dependency := plugin.binary
		typed := berror.New(
			berror.KindOptional,
			fmt.Sprintf("BKX %s mode requires the external %s plugin", mode, plugin.binary),
			map[string]any{
				"feature":      mode,
				"plugin":       plugin.binary,
				"dependency":   plugin.binary,
				"env_override": plugin.envOverride,
				"requested":    requested,
			},
		)
		typed.Feature, typed.Extra, typed.Dependency = &feature, &extra, &dependency
		return commandErr(c, typed)
	}

	cmd := exec.Command(path)
	cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, c.stdout, c.stderr
	cmd.Env = os.Environ()
	if err := cmd.Run(); err != nil {
		details := map[string]any{
			"operation": "launching external UI plugin",
			"feature":   mode,
			"plugin":    plugin.binary,
			"path":      path,
		}
		if exit, ok := err.(*exec.ExitError); ok {
			details["plugin_exit_code"] = exit.ExitCode()
		}
		details["reason"] = err.Error()
		return commandErr(c, berror.External("External BKX UI plugin failed", details))
	}
	return berror.ExitOK
}
