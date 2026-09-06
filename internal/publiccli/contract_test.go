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
	var handlers []string
	for name := range commandHandlers {
		handlers = append(handlers, name)
	}
	sort.Strings(handlers)
	if strings.Join(leaves, "\n") != strings.Join(handlers, "\n") {
		t.Fatalf("Go leaf ownership differs from frozen contract\ncontract=%v\nhandlers=%v", leaves, handlers)
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

func TestRootHelpGolden(t *testing.T) {
	path := filepath.Join("..", "..", "migration", "fixtures", "v1", "go_cli", "root_help.txt")
	want, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if RootHelp != string(want) {
		t.Fatalf("root help drifted\n--- want ---\n%s\n--- got ---\n%s", want, RootHelp)
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
