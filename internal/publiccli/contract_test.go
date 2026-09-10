package publiccli

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

type cliContract struct {
	CommandNodeCount int `json:"commandNodeCount"`
	LeafCommandCount int `json:"leafCommandCount"`
	CommandNodes     []struct {
		Command    string `json:"command"`
		IsGroup    bool   `json:"isGroup"`
		Parameters []struct {
			Kind                   string   `json:"kind"`
			OptionStrings          []string `json:"optionStrings"`
			SecondaryOptionStrings []string `json:"secondaryOptionStrings"`
		} `json:"parameters"`
	} `json:"commandNodes"`
	RootParameters []struct {
		OptionStrings          []string `json:"optionStrings"`
		SecondaryOptionStrings []string `json:"secondaryOptionStrings"`
	} `json:"rootParameters"`
}

func loadCLIContract(t *testing.T) cliContract {
	t.Helper()
	path := filepath.Join("..", "..", "migration", "contracts", "v1", "cli.json")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var contract cliContract
	if err := json.Unmarshal(data, &contract); err != nil {
		t.Fatal(err)
	}
	return contract
}

func TestFrozenCLIContractIsOwnedByPublicGoDispatcher(t *testing.T) {
	contract := loadCLIContract(t)
	if contract.CommandNodeCount != 23 || contract.LeafCommandCount != 16 {
		t.Fatalf("unexpected frozen counts: nodes=%d leaves=%d", contract.CommandNodeCount, contract.LeafCommandCount)
	}
	var leaves []string
	for _, node := range contract.CommandNodes {
		if !node.IsGroup {
			leaves = append(leaves, node.Command)
		}
	}
	sort.Strings(leaves)
	// The v1 contract is an immutable compatibility floor. New public commands
	// may be added, but every frozen leaf must remain owned by the Go dispatcher.
	for _, leaf := range leaves {
		if _, ok := commandHandlers[leaf]; !ok {
			t.Fatalf("frozen CLI leaf lost Go ownership: %s", leaf)
		}
	}
}

func TestFrozenRootOptionSurface(t *testing.T) {
	contract := loadCLIContract(t)
	var got []string
	for _, parameter := range contract.RootParameters {
		got = append(got, parameter.OptionStrings...)
		got = append(got, parameter.SecondaryOptionStrings...)
	}
	sort.Strings(got)
	want := []string{"--dry-run", "--json", "--no-trace", "--plan", "--progress", "--trace", "--verbose", "--version", "-v"}
	sort.Strings(want)
	if strings.Join(got, "\n") != strings.Join(want, "\n") {
		t.Fatalf("root option drift: got=%v want=%v", got, want)
	}
}

func TestVersionPrecedesNoCommandHelp(t *testing.T) {
	var stdout, stderr bytes.Buffer
	code := Run([]string{"--version"}, &stdout, &stderr)
	if code != 0 {
		t.Fatalf("code=%d stderr=%q", code, stderr.String())
	}
	if !strings.Contains(stdout.String(), "bkx 1.0.0") {
		t.Fatalf("version missing: %q", stdout.String())
	}
	if strings.Contains(stdout.String(), "Usage:") {
		t.Fatalf("version incorrectly fell through to help: %q", stdout.String())
	}
}

func TestInternalProbesStayOutOfPublicHelp(t *testing.T) {
	for _, hidden := range []string{"migration", "bridge-session", "contract check", "android-runtime"} {
		if strings.Contains(RootHelp, hidden) {
			t.Fatalf("internal route %q leaked into help", hidden)
		}
	}
}

func TestRootHelpPreservesFrozenV1Surface(t *testing.T) {
	path := filepath.Join("..", "..", "migration", "fixtures", "v1", "go_cli", "root_help.txt")
	want, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	// Do not rewrite the immutable v1 fixture when additive commands ship.
	for _, required := range []string{"archive   Archive operations", "backup    Backup operations", "restore   Restore operations", "tools     Tool management", "transfer  Transfer operations", "ui        Optional user interfaces"} {
		if !strings.Contains(string(want), required) || !strings.Contains(RootHelp, required) {
			t.Fatalf("frozen root-help surface missing %q", required)
		}
	}
	if !strings.Contains(RootHelp, "job       Saved and replayable operations") || !strings.Contains(RootHelp, "run       View") {
		t.Fatalf("saved-job/replay surface missing from root help")
	}
}

func TestInvalidArgumentGolden(t *testing.T) {
	var stdout, stderr bytes.Buffer
	code := Run([]string{"archive", "info"}, &stdout, &stderr)
	if code != 2 {
		t.Fatalf("code=%d stdout=%q stderr=%q", code, stdout.String(), stderr.String())
	}
	path := filepath.Join("..", "..", "migration", "fixtures", "v1", "go_cli", "missing_archive.stderr")
	want, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if stderr.String() != string(want) {
		t.Fatalf("invalid-arg output drifted: got=%q want=%q", stderr.String(), string(want))
	}
}
