package main

import (
	"fmt"
	"os"
	"path/filepath"

	"bkx/internal/contracts"
	"bkx/internal/version"
)

func main() { os.Exit(run(os.Args[1:])) }

// GO01 is intentionally non-production. Only migration introspection paths are
// accepted until later phases prove parity against migration/contracts/v1.
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
	fmt.Fprintln(os.Stderr, "bkx: Go migration scaffold is not the production backend yet; use the Python BKX CLI until parity gates pass")
	return 64
}
