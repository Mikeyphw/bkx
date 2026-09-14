package publiccli

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime/debug"
	"strings"

	"bkx/internal/berror"
	"bkx/internal/config"
	"bkx/internal/executioncontrol"
	"bkx/internal/jobstore"
	"bkx/internal/repositorystore"
	"bkx/internal/runstore"
	"bkx/internal/schedulestore"
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
	globals          globalOptions
	config           config.Config
	stdout           io.Writer
	stderr           io.Writer
	render           renderer
	store            *runstore.Store
	jobs             *jobstore.Store
	repositories     *repositorystore.Store
	control          *executioncontrol.Store
	schedules        *schedulestore.Store
	parseFailureExit int
}

func Run(args []string, stdout, stderr io.Writer) int {
	globals, rest, help, err := parseGlobals(args)
	if err != nil {
		return renderUsageError(renderer{json: wantsJSON(args), stdout: stdout, stderr: stderr}, err)
	}
	if help {
		if _, err := fmt.Fprint(stdout, RootHelp); err != nil {
			return berror.ExitExternal
		}
		return berror.ExitOK
	}
	globals.DryRun = globals.DryRun || globals.Plan

	// --version must win over the no-command help path.
	if globals.Version {
		r := renderer{json: globals.JSON, stdout: stdout, stderr: stderr}
		if err := r.message("bkx " + version.Version); err != nil {
			return berror.ExitExternal
		}
		return berror.ExitOK
	}
	if len(rest) == 0 {
		if _, err := fmt.Fprint(stdout, RootHelp); err != nil {
			return berror.ExitExternal
		}
		return berror.ExitOK
	}
	if handled, code := renderHelpWithoutSideEffects(rest, stdout, stderr); handled {
		return code
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
		globals:      globals,
		config:       cfg,
		stdout:       stdout,
		stderr:       stderr,
		render:       renderer{json: globals.JSON, stdout: stdout, stderr: stderr},
		jobs:         jobstore.Default(home),
		repositories: repositorystore.Default(home),
		control:      executioncontrol.Default(home),
		schedules:    schedulestore.Default(home),
	}
	if cfg.RunHistoryEnabled {
		runHistoryPath := runstore.DefaultPath(home)
		if _, mergeErr := runstore.MergeLegacyHistory(runHistoryPath, runstore.LegacyDefaultPath(home)); mergeErr != nil {
			return renderDomainError(ctx.render, globals.Trace, mergeErr)
		}
		store, storeErr := runstore.New(runHistoryPath, cfg.RunHistoryLimit, false)
		if storeErr != nil {
			return renderDomainError(ctx.render, globals.Trace, storeErr)
		}
		if _, recoverErr := store.RecoverInterruptedWithControl(ctx.control); recoverErr != nil {
			return renderDomainError(ctx.render, globals.Trace, recoverErr)
		}
		ctx.store = store
	}
	code := ctx.dispatch(rest)
	if ctx.parseFailureExit != 0 && code == berror.ExitOK {
		return ctx.parseFailureExit
	}
	return code
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
			var raw string
			if inline, ok := strings.CutPrefix(token, "--progress="); ok {
				raw = inline
			} else {
				if i+1 >= len(args) {
					return out, nil, false, fmt.Errorf("option --progress requires a value")
				}
				i++
				raw = args[i]
			}
			value := strings.ToLower(strings.TrimSpace(raw))
			if value != "auto" && value != "plain" && value != "off" {
				return out, nil, false, fmt.Errorf("progress must be one of: auto, plain, off")
			}
			out.Progress = value
		default:
			if strings.HasPrefix(token, "--progress=") {
				value := strings.ToLower(strings.TrimSpace(strings.TrimPrefix(token, "--progress=")))
				if value != "auto" && value != "plain" && value != "off" {
					return out, nil, false, fmt.Errorf("progress must be one of: auto, plain, off")
				}
				out.Progress = value
			} else {
				return out, nil, false, fmt.Errorf("no such option: %s", token)
			}
		}
		i++
	}
	return out, args[i:], false, nil
}

func (c *appContext) dispatch(args []string) int {
	noun := args[0]
	if len(args) == 1 || (len(args) == 2 && isHelpToken(args[1])) {
		if help, ok := GroupHelp[noun]; ok {
			if _, err := fmt.Fprint(c.stdout, help); err != nil {
				return berror.ExitExternal
			}
			return berror.ExitOK
		}
		return cliUsageError(c, fmt.Errorf("no such command: %s", noun))
	}
	verb := args[1]
	key := noun + " " + verb
	handler, ok := commandHandlers[key]
	if !ok {
		return cliUsageError(c, fmt.Errorf("no such command: %s", key))
	}
	if len(args) == 3 && isHelpToken(args[2]) {
		if help, ok := LeafHelp[key]; ok {
			if _, err := fmt.Fprint(c.stdout, help); err != nil {
				return berror.ExitExternal
			}
			return berror.ExitOK
		}
	}
	return handler(c, args[2:])
}

func usageError(w io.Writer, err error) {
	fmt.Fprintf(w, "Error: %s\nTry 'bkx --help' for help.\n", err)
}

func wantsJSON(args []string) bool {
	for _, arg := range args {
		if arg == "--json" {
			return true
		}
	}
	return false
}

func isHelpToken(arg string) bool { return arg == "--help" || arg == "-h" }

func renderHelpWithoutSideEffects(args []string, stdout, stderr io.Writer) (bool, int) {
	if len(args) == 2 && isHelpToken(args[1]) {
		if help, ok := GroupHelp[args[0]]; ok {
			if _, err := fmt.Fprint(stdout, help); err != nil {
				return true, berror.ExitExternal
			}
			return true, berror.ExitOK
		}
	}
	if len(args) >= 3 && isHelpToken(args[2]) {
		key := args[0] + " " + args[1]
		if help, ok := LeafHelp[key]; ok {
			if _, err := fmt.Fprint(stdout, help); err != nil {
				return true, berror.ExitExternal
			}
			return true, berror.ExitOK
		}
	}
	return false, 0
}

func renderUsageError(r renderer, err error) int {
	if r.json {
		if renderErr := r.domainError(berror.Validation(err.Error(), map[string]any{"category": "usage"})); renderErr != nil {
			return berror.ExitExternal
		}
		return berror.ExitUsage
	}
	usageError(r.stderr, err)
	return berror.ExitUsage
}

func cliUsageError(c *appContext, err error) int {
	return renderUsageError(c.render, err)
}

func renderDomainError(r renderer, trace *bool, err error) int {
	if trace != nil && *trace {
		fmt.Fprintf(r.stderr, "%T: %v\n", err, err)
		debug.PrintStack()
	}
	var typed *berror.Error
	if errors.As(err, &typed) {
		if renderErr := r.domainError(typed); renderErr != nil {
			return berror.ExitExternal
		}
		return berror.ExitCode(typed)
	}
	if renderErr := r.typedError(err.Error(), nil); renderErr != nil {
		return berror.ExitExternal
	}
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
  job       Saved and replayable operations
  run       View and replay run history
  repository Local and remote backup repositories
  snapshot  Incremental snapshot lifecycle
  retention Snapshot retention policies
  schedule  Saved-job schedules
  state     Shared control-plane state
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
	"repository": `Usage: bkx repository COMMAND [ARGS]...

Backup repositories

Commands:
  add
  check
  init
  list
  prune
  remove
  show
  status
`,
	"snapshot": `Usage: bkx snapshot COMMAND [ARGS]...

Incremental snapshot lifecycle

Commands:
  browse
  diff
  forget
  list
  restore
  show
`,
	"retention": `Usage: bkx retention COMMAND [ARGS]...

Snapshot retention policies

Commands:
  apply
  clear
  set
  show
`,
	"schedule": `Usage: bkx schedule COMMAND [ARGS]...

Saved-job schedules

Commands:
  add
  disable
  enable
  list
  remove
  show
  set
`,
	"job": `Usage: bkx job COMMAND [ARGS]...

Saved and replayable operations

Commands:
  clone
  create
  create-from-run
  delete
  edit
  export
  import
  list
  plan
  run
  save
  show
`,
	"run": `Usage: bkx run COMMAND [ARGS]...

View and replay run history

Commands:
  cancel
  list
  replay
  show
`,
	"state": `Usage: bkx state COMMAND [ARGS]...

Shared control-plane state

Commands:
  status
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
  sync
`,
	"ui": `Usage: bkx ui COMMAND [ARGS]...

Optional user interfaces

Commands:
  start
`,
}

var LeafHelp = map[string]string{
	"backup create":       "Usage: bkx backup create [OPTIONS] SOURCES...\n",
	"backup plan":         "Usage: bkx backup plan [OPTIONS] SOURCES...\n",
	"archive create":      "Usage: bkx archive create [OPTIONS] SOURCES...\n",
	"archive list":        "Usage: bkx archive list [OPTIONS] ARCHIVE\n",
	"archive info":        "Usage: bkx archive info ARCHIVE\n",
	"archive browse":      "Usage: bkx archive browse [OPTIONS] ARCHIVE\n",
	"archive verify":      "Usage: bkx archive verify ARCHIVE\n",
	"restore extract":     "Usage: bkx restore extract [OPTIONS] ARCHIVE\n",
	"restore pick":        "Usage: bkx restore pick [OPTIONS] ARCHIVE PATHS...\n",
	"job create":          "Usage: bkx job create [OPTIONS] JOB_ID\n",
	"job save":            "Usage: bkx job save [OPTIONS] JOB_ID\n",
	"job list":            "Usage: bkx job list\n",
	"job show":            "Usage: bkx job show JOB_ID\n",
	"job edit":            "Usage: bkx job edit [OPTIONS] JOB_ID\n",
	"job clone":           "Usage: bkx job clone SOURCE_JOB_ID TARGET_JOB_ID [OPTIONS]\n",
	"job delete":          "Usage: bkx job delete JOB_ID\n",
	"job plan":            "Usage: bkx job plan [OPTIONS] JOB_ID\n",
	"job revisions":       "Usage: bkx job revisions JOB_ID\n",
	"job run":             "Usage: bkx job run [OPTIONS] JOB_ID\n",
	"job export":          "Usage: bkx job export [OPTIONS] JOB_ID\n",
	"job import":          "Usage: bkx job import [OPTIONS] FILE\n",
	"job create-from-run": "Usage: bkx job create-from-run [OPTIONS] RUN_ID JOB_ID\n",
	"run cancel":          "Usage: bkx run cancel REQUEST_ID\n",
	"run list":            "Usage: bkx run list [OPTIONS]\n",
	"run replay":          "Usage: bkx run replay [OPTIONS] RUN_ID\n",
	"run show":            "Usage: bkx run show RUN_ID\n",
	"repository add":      "Usage: bkx repository add [OPTIONS] REPOSITORY_ID\n",
	"repository check":    "Usage: bkx repository check [OPTIONS] REPOSITORY_ID\n",
	"repository init":     "Usage: bkx repository init [OPTIONS] [REPOSITORY]\n",
	"repository list":     "Usage: bkx repository list\n",
	"repository prune":    "Usage: bkx repository prune [OPTIONS] REPOSITORY_ID\n",
	"repository remove":   "Usage: bkx repository remove REPOSITORY_ID\n",
	"repository show":     "Usage: bkx repository show REPOSITORY_ID\n",
	"repository status":   "Usage: bkx repository status REPOSITORY_ID\n",
	"snapshot list":       "Usage: bkx snapshot list [OPTIONS] [JOB_OR_REPOSITORY]\n",
	"snapshot browse":     "Usage: bkx snapshot browse [OPTIONS] [JOB_OR_REPOSITORY] SNAPSHOT\n",
	"snapshot show":       "Usage: bkx snapshot show [OPTIONS] [JOB_OR_REPOSITORY] SNAPSHOT\n",
	"snapshot diff":       "Usage: bkx snapshot diff [OPTIONS] [JOB_OR_REPOSITORY] SOURCE TARGET\n",
	"snapshot restore":    "Usage: bkx snapshot restore [OPTIONS] [JOB_OR_REPOSITORY] SNAPSHOT\n",
	"snapshot forget":     "Usage: bkx snapshot forget [OPTIONS] [JOB_OR_REPOSITORY] SNAPSHOT_ID\n",
	"retention set":       "Usage: bkx retention set [OPTIONS] REPOSITORY_ID\n",
	"retention show":      "Usage: bkx retention show REPOSITORY_ID\n",
	"retention clear":     "Usage: bkx retention clear REPOSITORY_ID\n",
	"retention apply":     "Usage: bkx retention apply [OPTIONS] REPOSITORY_ID\n",
	"schedule add":        "Usage: bkx schedule add [OPTIONS] SCHEDULE_ID\n",
	"schedule disable":    "Usage: bkx schedule disable SCHEDULE_ID\n",
	"schedule enable":     "Usage: bkx schedule enable SCHEDULE_ID\n",
	"schedule list":       "Usage: bkx schedule list\n",
	"schedule remove":     "Usage: bkx schedule remove SCHEDULE_ID\n",
	"schedule show":       "Usage: bkx schedule show SCHEDULE_ID\n",
	"schedule set":        "Usage: bkx schedule set [OPTIONS] SCHEDULE_ID\n",
	"state status":        "Usage: bkx state status\n",
	"tools detect":        "Usage: bkx tools detect [TOOL_NAME]...\n",
	"tools explain":       "Usage: bkx tools explain [CATEGORY]...\n",
	"transfer push":       "Usage: bkx transfer push [OPTIONS] SOURCE DESTINATION\n",
	"transfer pull":       "Usage: bkx transfer pull [OPTIONS] SOURCE DESTINATION\n",
	"transfer sync":       "Usage: bkx transfer sync [OPTIONS] SOURCE DESTINATION\n",
	"ui start":            "Usage: bkx ui start [OPTIONS]\n",
}
