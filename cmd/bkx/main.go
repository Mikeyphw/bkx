package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"bkx/internal/archiveio"
	"bkx/internal/config"
	"bkx/internal/contracts"
	"bkx/internal/fsutil"
	"bkx/internal/hashing"
	"bkx/internal/runstore"
	"bkx/internal/tools"
	"bkx/internal/version"
)

func main() { os.Exit(run(os.Args[1:])) }

// GO06 remains migration-only. Product commands remain Python-owned; only
// explicit contract/parity probes are accepted by this executable.
func run(args []string) int {
	if len(args) == 1 && (args[0] == "--version" || args[0] == "version") {
		fmt.Printf("bkx %s (Go migration scaffold %s, commit %s; Python baseline %s)\n", version.Version, version.MigrationPhase, version.Commit, version.PythonBaseline)
		return 0
	}
	if len(args) >= 2 && args[0] == "contract" && args[1] == "check" {
		dir := filepath.Join("migration", "contracts", "v1")
		if len(args) == 3 {
			dir = args[2]
		}
		if len(args) > 3 {
			fmt.Fprintln(os.Stderr, "usage: bkx contract check [contract-dir]")
			return 64
		}
		bundle, err := contracts.LoadDir(dir)
		if err == nil {
			err = bundle.Validate()
		}
		if err != nil {
			fmt.Fprintf(os.Stderr, "bkx: GO00 contract check failed: %v\n", err)
			return 1
		}
		fmt.Println("BKX GO00 contracts valid.")
		return 0
	}
	if len(args) == 4 && args[0] == "migration" && args[1] == "run-history" && args[2] == "append-probe" {
		store, err := runstore.New(args[3], 0, false)
		if err == nil {
			destination := "/go/probe"
			_, err = store.RecordStart("go-probe", "backup", []string{"/go/source"}, &destination, nil, map[string]any{"phase": "GO02", "verify": true})
		}
		if err == nil {
			files := int64(1)
			bytes := int64(7)
			_, err = store.RecordCompletion("go-probe", "success", &files, &bytes, nil)
		}
		if err != nil {
			fmt.Fprintf(os.Stderr, "bkx: GO02 run-history probe failed: %v\n", err)
			return 1
		}
		fmt.Println("BKX GO02 run-history probe appended.")
		return 0
	}
	if len(args) == 5 && args[0] == "migration" && args[1] == "config" && args[2] == "probe" {
		cwd, home := args[3], args[4]
		env := map[string]string{}
		for _, item := range os.Environ() {
			if key, value, ok := strings.Cut(item, "="); ok && strings.HasPrefix(key, "BKX_") {
				env[key] = value
			}
		}
		c, err := config.Load(cwd, home, env)
		if err != nil {
			fmt.Fprintf(os.Stderr, "bkx: GO03 config probe failed: %v\n", err)
			return 1
		}
		payload := map[string]any{
			"default_destination":    c.DefaultDestination,
			"verify_after_operation": c.VerifyAfterOperation,
			"compression_algorithm":  c.CompressionAlgorithm,
			"compression_level":      c.CompressionLevel,
			"fresh_restore_dir":      c.FreshRestoreDir,
			"run_history_enabled":    c.RunHistoryEnabled,
			"run_history_limit":      c.RunHistoryLimit,
			"trace":                  c.Trace,
			"sources":                map[string]string{},
		}
		sources := payload["sources"].(map[string]string)
		for _, field := range []string{"default_destination", "verify_after_operation", "compression_algorithm", "compression_level", "fresh_restore_dir", "run_history_enabled", "run_history_limit", "trace"} {
			src, _ := c.SourceOf(field)
			sources[field] = src
		}
		return printJSON(payload)
	}
	if len(args) == 5 && args[0] == "migration" && args[1] == "run-history" && args[2] == "retention-probe" {
		limit, err := strconv.Atoi(args[4])
		if err != nil || limit <= 0 {
			fmt.Fprintln(os.Stderr, "usage: bkx migration run-history retention-probe FILE LIMIT")
			return 64
		}
		store, err := runstore.New(args[3], limit, false)
		if err == nil {
			_, err = store.Load()
		}
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		fmt.Println("BKX GO02 retention probe complete.")
		return 0
	}

	// GO04+ migration probes expose parity evidence only. They are not
	// aliases for product commands and intentionally remain under "migration".
	if len(args) == 4 && args[0] == "migration" && args[1] == "filesystem" && args[2] == "stat" {
		entry, err := fsutil.Stat(args[3])
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		return printJSON(entry)
	}
	if len(args) == 7 && args[0] == "migration" && args[1] == "filesystem" && args[2] == "list" {
		showHidden, err := strconv.ParseBool(args[4])
		if err != nil {
			fmt.Fprintln(os.Stderr, "usage: bkx migration filesystem list PATH SHOW_HIDDEN LIMIT OFFSET")
			return 64
		}
		limit, err1 := strconv.Atoi(args[5])
		offset, err2 := strconv.Atoi(args[6])
		if err1 != nil || err2 != nil {
			fmt.Fprintln(os.Stderr, "usage: bkx migration filesystem list PATH SHOW_HIDDEN LIMIT OFFSET")
			return 64
		}
		result, err := fsutil.List(args[3], showHidden, limit, offset)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		return printJSON(result)
	}
	if len(args) == 4 && args[0] == "migration" && args[1] == "traversal" && args[2] == "manifest" {
		result, err := fsutil.BuildSourceManifest([]string{args[3]}, nil, nil, "", fsutil.SymlinkReject)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		return printJSON(result)
	}
	if len(args) == 5 && args[0] == "migration" && args[1] == "hash" && args[2] == "file" {
		result, err := hashing.File(args[3], args[4])
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		return printJSON(result)
	}
	if len(args) == 5 && args[0] == "migration" && args[1] == "hash" && args[2] == "directory" {
		result, err := hashing.Directory(args[3], args[4], true)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		return printJSON(result)
	}
	if len(args) == 4 && args[0] == "migration" && args[1] == "tools" && args[2] == "detect" {
		detector, err := tools.New(nil, nil, 2*time.Second)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		info, err := detector.Detect(args[3])
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		return printJSON(info)
	}

	if len(args) == 4 && args[0] == "migration" && args[1] == "restore" {
		spec, err := archiveio.ParseRestoreSpecJSON(args[3])
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		switch args[2] {
		case "preview":
			result, err := archiveio.PreviewRestore(spec)
			if err != nil {
				fmt.Fprintln(os.Stderr, err)
				return 1
			}
			return printJSON(result)
		case "extract":
			result, err := archiveio.ExtractRestore(spec, nil)
			if err != nil {
				fmt.Fprintln(os.Stderr, err)
				return 1
			}
			return printJSON(result)
		default:
			fmt.Fprintln(os.Stderr, "usage: bkx migration restore {preview|extract} SPEC_JSON")
			return 64
		}
	}

	if len(args) >= 4 && args[0] == "migration" && args[1] == "archive" {
		switch args[2] {
		case "create":
			if len(args) != 5 {
				fmt.Fprintln(os.Stderr, "usage: bkx migration archive create SPEC_JSON IMPLICIT_DEFAULT")
				return 64
			}
			implicitDefault, err := strconv.ParseBool(args[4])
			if err != nil {
				fmt.Fprintln(os.Stderr, "usage: bkx migration archive create SPEC_JSON IMPLICIT_DEFAULT")
				return 64
			}
			spec, embeddedImplicit, err := archiveio.ParseCreateSpecJSON(args[3])
			if err != nil {
				fmt.Fprintln(os.Stderr, err)
				return 1
			}
			result, err := archiveio.Create(spec, implicitDefault || embeddedImplicit, nil)
			if err != nil {
				fmt.Fprintln(os.Stderr, err)
				return 1
			}
			return printJSON(result)
		case "list":
			if len(args) != 7 {
				fmt.Fprintln(os.Stderr, "usage: bkx migration archive list PATH PAGE PAGE_SIZE FILTER_OR_DASH")
				return 64
			}
			page, err1 := strconv.Atoi(args[4])
			pageSize, err2 := strconv.Atoi(args[5])
			if err1 != nil || err2 != nil {
				fmt.Fprintln(os.Stderr, "usage: bkx migration archive list PATH PAGE PAGE_SIZE FILTER_OR_DASH")
				return 64
			}
			var filter *string
			if args[6] != "-" {
				value := args[6]
				filter = &value
			}
			result, err := archiveio.List(args[3], filter, page, pageSize)
			if err != nil {
				fmt.Fprintln(os.Stderr, err)
				return 1
			}
			return printJSON(result)
		case "info":
			if len(args) != 4 {
				fmt.Fprintln(os.Stderr, "usage: bkx migration archive info PATH")
				return 64
			}
			result, err := archiveio.Info(args[3])
			if err != nil {
				fmt.Fprintln(os.Stderr, err)
				return 1
			}
			return printJSON(result)
		case "verify":
			if len(args) != 5 {
				fmt.Fprintln(os.Stderr, "usage: bkx migration archive verify PATH ALGORITHMS")
				return 64
			}
			algorithms := []string{}
			if args[4] != "-" && args[4] != "" {
				algorithms = strings.Split(args[4], ",")
			}
			result, err := archiveio.Verify(args[3], algorithms)
			if err != nil {
				fmt.Fprintln(os.Stderr, err)
				return 1
			}
			return printJSON(result)
		case "browse":
			if len(args) != 12 {
				fmt.Fprintln(os.Stderr, "usage: bkx migration archive browse PATH QUERY FILE_TYPE MIN MAX LIMIT OFFSET SORT_BY SORT_ORDER")
				return 64
			}
			optString := func(value string) *string {
				if value == "-" {
					return nil
				}
				v := value
				return &v
			}
			optInt := func(value string) (*int64, error) {
				if value == "-" {
					return nil, nil
				}
				v, err := strconv.ParseInt(value, 10, 64)
				if err != nil {
					return nil, err
				}
				return &v, nil
			}
			minSize, err1 := optInt(args[6])
			maxSize, err2 := optInt(args[7])
			limit, err3 := strconv.Atoi(args[8])
			offset, err4 := strconv.Atoi(args[9])
			if err1 != nil || err2 != nil || err3 != nil || err4 != nil {
				fmt.Fprintln(os.Stderr, "invalid browse numeric argument")
				return 64
			}
			result, err := archiveio.Browse(args[3], archiveio.BrowseOptions{Query: optString(args[4]), FileType: optString(args[5]), MinSize: minSize, MaxSize: maxSize, Limit: limit, Offset: offset, SortBy: args[10], SortOrder: args[11]})
			if err != nil {
				fmt.Fprintln(os.Stderr, err)
				return 1
			}
			return printJSON(result)
		}
	}

	fmt.Fprintln(os.Stderr, "bkx: Go migration scaffold is not the production backend yet; use the Python BKX CLI until parity gates pass")
	return 64
}

func printJSON(value any) int {
	data, err := json.Marshal(value)
	if err != nil {
		fmt.Fprintf(os.Stderr, "bkx: migration JSON encoding failed: %v\n", err)
		return 1
	}
	fmt.Println(string(data))
	return 0
}
