package publiccli

import (
	"os"
	"os/exec"

	"bkx/internal/berror"
)

// GO15 keeps UI startup as the sole Python-reference exception. GO16 replaces
// this bridge with external UI plugin executables and removes production
// Python console ownership entirely.
func runPythonReferenceUI(c *appContext, mode string) int {
	python := os.Getenv("BKX_PYTHON_REFERENCE")
	if python == "" {
		python = "python3"
	}
	path, err := exec.LookPath(python)
	if err != nil {
		feature := mode
		extra := mode
		dependency := python
		typed := berror.New(berror.KindOptional, "BKX "+mode+" mode requires the GO15 Python reference runtime", map[string]any{"feature": mode, "extra": "bkx[" + mode + "]", "dependency": python})
		typed.Feature, typed.Extra, typed.Dependency = &feature, &extra, &dependency
		return commandErr(c, typed)
	}
	code := "from bkx.cli.app import app; app(prog_name='bkx')"
	cmd := exec.Command(path, "-c", code, "ui", "start", "--mode", mode)
	cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, c.stdout, c.stderr
	cmd.Env = os.Environ()
	if err := cmd.Run(); err != nil {
		if exit, ok := err.(*exec.ExitError); ok {
			return exit.ExitCode()
		}
		return commandErr(c, berror.WrapExternal("launching GO15 Python UI reference", mode, path, err))
	}
	return berror.ExitOK
}
