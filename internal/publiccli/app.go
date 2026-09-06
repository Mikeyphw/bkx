package publiccli

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime/debug"
	"strings"

	"bkx/internal/berror"
	"bkx/internal/config"
	"bkx/internal/runstore"
	"bkx/internal/version"
)

type globalOptions struct {
	JSON     bool
	DryRun   bool
	Plan     bool
	Progress string
	Verbose  bool
	Trace    *bool
	Version  bool
}

type appContext struct {
	globals globalOptions
	config  config.Config
	stdout  io.Writer
	stderr  io.Writer
	render  renderer
	store   *runstore.Store
}

func Run(args []string, stdout, stderr io.Writer) int {
	globals, rest, help, err := parseGlobals(args)
	if err != nil {
		usageError(stderr, err)
		return berror.ExitUsage
	}
	if help {
		fmt.Fprint(stdout, RootHelp)
		return berror.ExitOK
	}
	globals.DryRun = globals.DryRun || globals.Plan

	// --version must win over the no-command help path.
	if globals.Version {
		r := renderer{json: globals.JSON, stdout: stdout, stderr: stderr}
		_ = r.message("bkx " + version.Version)
		return berror.ExitOK
	}
	if len(rest) == 0 {
		fmt.Fprint(stdout, RootHelp)
		return berror.ExitOK
	}

	cwd, err := os.Getwd()
	if err != nil {
		fmt.Fprintln(stderr, err)
		return berror.ExitExternal
	}
	home, err := os.UserHomeDir()
	if err != nil {
		fmt.Fprintln(stderr, err)
		return berror.ExitExternal
	}
	env := environmentMap()
	cfg, err := config.Load(cwd, home, env)
	if err != nil {
		return renderDomainError(renderer{json: globals.JSON, stdout: stdout, stderr: stderr}, globals.Trace, err)
	}
	if globals.Trace == nil {
		trace := cfg.Trace
		globals.Trace = &trace
	}
	ctx := &appContext{
		globals: globals,
		config:  cfg,
		stdout:  stdout,
		stderr:  stderr,
		render:  renderer{json: globals.JSON, stdout: stdout, stderr: stderr},
	}
	if cfg.RunHistoryEnabled {
		store, storeErr := runstore.New(runstore.DefaultPath(home), cfg.RunHistoryLimit, true)
		if storeErr != nil {
			return renderDomainError(ctx.render, globals.Trace, storeErr)
		}
		ctx.store = store
	}
	return ctx.dispatch(rest)
}

func IsInternalInvocation(args []string) bool {
	if len(args) == 0 {
		return false
	}
	switch args[0] {
	case "android-runtime", "contract":
		return true
	default:
		return false
	}
}

func parseGlobals(args []string) (globalOptions, []string, bool, error) {
	out := globalOptions{Progress: "auto"}
	i := 0
	for i < len(args) {
		token := args[i]
		if token == "--" {
			return out, args[i+1:], false, nil
		}
		if !strings.HasPrefix(token, "-") {
			break
		}
		switch token {
		case "--help", "-h":
			return out, nil, true, nil
		case "--json":
			out.JSON = true
		case "--dry-run":
			out.DryRun = true
		case "--plan":
			out.Plan = true
		case "--verbose", "-v":
			out.Verbose = true
		case "--trace":
			value := true
			out.Trace = &value
		case "--no-trace":
			value := false
			out.Trace = &value
		case "--version":
			out.Version = true
		case "--progress":
			if i+1 >= len(args) {
				return out, nil, false, fmt.Errorf("option --progress requires a value")
			}
			i++
			value := strings.ToLower(strings.TrimSpace(args[i]))
			if value != "auto" && value != "plain" && value != "off" {
				return out, nil, false, fmt.Errorf("progress must be one of: auto, plain, off")
			}
			out.Progress = value
		default:
			return out, nil, false, fmt.Errorf("no such option: %s", token)
		}
		i++
	}
	return out, args[i:], false, nil
}

func (c *appContext) dispatch(args []string) int {
	noun := args[0]
	if len(args) == 1 || (len(args) == 2 && (args[1] == "--help" || args[1] == "-h")) {
		if help, ok := GroupHelp[noun]; ok {
			fmt.Fprint(c.stdout, help)
			return berror.ExitOK
		}
		usageError(c.stderr, fmt.Errorf("no such command: %s", noun))
		return berror.ExitUsage
	}
	verb := args[1]
	key := noun + " " + verb
	handler, ok := commandHandlers[key]
	if !ok {
		usageError(c.stderr, fmt.Errorf("no such command: %s", key))
		return berror.ExitUsage
	}
	return handler(c, args[2:])
}

func usageError(w io.Writer, err error) {
	fmt.Fprintf(w, "Error: %s\nTry 'bkx --help' for help.\n", err)
}

func renderDomainError(r renderer, trace *bool, err error) int {
	if trace != nil && *trace {
		fmt.Fprintf(r.stderr, "%T: %v\n", err, err)
		debug.PrintStack()
	}
	if typed, ok := err.(*berror.Error); ok {
		_ = r.typedError(typed.Message, typed.Details)
		return berror.ExitCode(typed)
	}
	_ = r.typedError(err.Error(), nil)
	return berror.ExitGeneric
}

func environmentMap() map[string]string {
	out := map[string]string{}
	for _, item := range os.Environ() {
		if key, value, ok := strings.Cut(item, "="); ok {
			out[key] = value
		}
	}
	return out
}

func canonicalOptionalPath(raw string) (string, error) {
	if strings.TrimSpace(raw) == "" {
		return "", berror.Validation("path must not be empty", nil)
	}
	if strings.HasPrefix(raw, "~") {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		if raw == "~" {
			raw = home
		} else if strings.HasPrefix(raw, "~/") {
			raw = filepath.Join(home, raw[2:])
		}
	}
	return filepath.Abs(raw)
}

const RootHelp = `Usage: bkx [OPTIONS] COMMAND [ARGS]...

Contract-controlled backup, archive, restore, and transfer tool.

Options:
  --json              Output exactly one JSON document
  --dry-run           Show what would be done without executing
  --plan              Alias for --dry-run plan rendering
  --progress TEXT     Progress display: auto|plain|off  [default: auto]
  --verbose, -v       Emit diagnostics to stderr
  --trace / --no-trace
                      Show full stack traces on typed errors without changing exit codes
  --version           Show version and exit
  --help              Show this message and exit.

Commands:
  archive   Archive operations
  backup    Backup operations
  restore   Restore operations
  run       View run history
  tools     Tool management
  transfer  Transfer operations
  ui        Optional user interfaces
`

var GroupHelp = map[string]string{
	"archive": `Usage: bkx archive COMMAND [ARGS]...

Archive operations

Commands:
  browse
  create
  info
  list
  verify
`,
	"backup": `Usage: bkx backup COMMAND [ARGS]...

Backup operations

Commands:
  create
  plan
`,
	"restore": `Usage: bkx restore COMMAND [ARGS]...

Restore operations

Commands:
  extract
  pick
`,
	"run": `Usage: bkx run COMMAND [ARGS]...

View run history

Commands:
  list
  show
`,
	"tools": `Usage: bkx tools COMMAND [ARGS]...

Tool management

Commands:
  detect
  explain
`,
	"transfer": `Usage: bkx transfer COMMAND [ARGS]...

Transfer operations

Commands:
  pull
  push
`,
	"ui": `Usage: bkx ui COMMAND [ARGS]...

Optional user interfaces

Commands:
  start
`,
}

var LeafHelp = map[string]string{
	"backup create":   "Usage: bkx backup create [OPTIONS] SOURCES...\n",
	"backup plan":     "Usage: bkx backup plan [OPTIONS] SOURCES...\n",
	"archive create":  "Usage: bkx archive create [OPTIONS] SOURCES...\n",
	"archive list":    "Usage: bkx archive list [OPTIONS] ARCHIVE\n",
	"archive info":    "Usage: bkx archive info ARCHIVE\n",
	"archive browse":  "Usage: bkx archive browse [OPTIONS] ARCHIVE\n",
	"archive verify":  "Usage: bkx archive verify ARCHIVE\n",
	"restore extract": "Usage: bkx restore extract [OPTIONS] ARCHIVE\n",
	"restore pick":    "Usage: bkx restore pick [OPTIONS] ARCHIVE PATHS...\n",
	"run list":        "Usage: bkx run list [OPTIONS]\n",
	"run show":        "Usage: bkx run show RUN_ID\n",
	"tools detect":    "Usage: bkx tools detect [TOOL_NAME]...\n",
	"tools explain":   "Usage: bkx tools explain [CATEGORY]...\n",
	"transfer push":   "Usage: bkx transfer push [OPTIONS] SOURCE DESTINATION\n",
	"transfer pull":   "Usage: bkx transfer pull [OPTIONS] SOURCE DESTINATION\n",
	"ui start":        "Usage: bkx ui start [OPTIONS]\n",
}
