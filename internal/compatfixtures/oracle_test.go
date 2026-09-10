package compatfixtures

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"bkx/internal/bridge"
	"bkx/internal/publiccli"
)

type oracle struct {
	SchemaVersion   int                `json:"schema_version"`
	ContractVersion int                `json:"contract_version"`
	CLICases        []cliCase          `json:"cli_cases"`
	UsageCases      []cliCase          `json:"usage_cases"`
	Bridge          bridgeExpectations `json:"bridge_expectations"`
}

type cliCase struct {
	Name     string   `json:"name"`
	Args     []string `json:"args"`
	ExitCode int      `json:"exit_code"`
	Stdout   any      `json:"stdout"`
	Stderr   string   `json:"stderr"`
}

type bridgeExpectations struct {
	ProtocolVersion          int      `json:"protocol_version"`
	Runtime                  string   `json:"runtime"`
	PythonVersion            string   `json:"python_version"`
	HandlerCount             int      `json:"handler_count"`
	AdvertisedOperationCount int      `json:"advertised_operation_count"`
	CapabilityMinimum        []string `json:"capability_minimum"`
}

var backupID = regexp.MustCompile(`source_[0-9a-f]{12}\.bakery`)

func loadOracle(t *testing.T) oracle {
	t.Helper()
	path := filepath.Join("..", "..", "migration", "fixtures", "v1", "reference_oracle.json")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var out oracle
	if err := json.Unmarshal(data, &out); err != nil {
		t.Fatal(err)
	}
	if out.SchemaVersion != 1 || out.ContractVersion != 1 {
		t.Fatalf("unsupported oracle version: %#v", out)
	}
	return out
}

func copyFile(t *testing.T, src, dst string) {
	t.Helper()
	data, err := os.ReadFile(src)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(dst, data, 0o644); err != nil {
		t.Fatal(err)
	}
}

func setupWorkspace(t *testing.T) (string, string) {
	t.Helper()
	root := t.TempDir()
	work := filepath.Join(root, "work")
	home := filepath.Join(root, "home")
	if err := os.MkdirAll(filepath.Join(work, "source"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(home, 0o755); err != nil {
		t.Fatal(err)
	}
	base := filepath.Join("..", "..", "migration", "fixtures", "v1", "data")
	copyFile(t, filepath.Join(base, "source", "alpha.txt"), filepath.Join(work, "source", "alpha.txt"))
	copyFile(t, filepath.Join(base, "source", "beta.bin"), filepath.Join(work, "source", "beta.bin"))
	copyFile(t, filepath.Join(base, "fixture.zip"), filepath.Join(work, "fixture.zip"))
	copyFile(t, filepath.Join(base, "exclude.txt"), filepath.Join(work, "exclude.txt"))
	return work, home
}

func expandArgs(args []string, work, home string) []string {
	out := make([]string, len(args))
	for i, arg := range args {
		arg = strings.ReplaceAll(arg, "$WORK", work)
		arg = strings.ReplaceAll(arg, "$HOME", home)
		out[i] = arg
	}
	return out
}

func canonicalizeString(value, work, home string) string {
	value = strings.ReplaceAll(value, work, "$WORK")
	value = strings.ReplaceAll(value, home, "$HOME")
	return backupID.ReplaceAllString(value, "source_<id>.bakery")
}

func canonicalize(v any, work, home string) any {
	switch x := v.(type) {
	case map[string]any:
		out := make(map[string]any, len(x))
		for k, val := range x {
			out[k] = canonicalize(val, work, home)
		}
		if _, ok := out["created_at"]; ok {
			out["created_at"] = "<archive-created-at>"
		}
		return out
	case []any:
		out := make([]any, len(x))
		for i, val := range x {
			out[i] = canonicalize(val, work, home)
		}
		return out
	case string:
		return canonicalizeString(x, work, home)
	default:
		return v
	}
}

func normalizeJSON(t *testing.T, raw string, work, home string) any {
	t.Helper()
	var v any
	if err := json.Unmarshal([]byte(raw), &v); err != nil {
		t.Fatalf("decode JSON: %v\n%s", err, raw)
	}
	return canonicalize(v, work, home)
}

func jsonEqual(got, want any) bool {
	a, _ := json.Marshal(got)
	b, _ := json.Marshal(want)
	return bytes.Equal(a, b)
}

func TestFrozenReferenceOracleCLI(t *testing.T) {
	// The frozen Python oracle interpreted ZIP DOS timestamps in UTC. Pin the
	// fixture timezone without changing the production local-time semantics.
	originalLocal := time.Local
	time.Local = time.UTC
	t.Cleanup(func() { time.Local = originalLocal })
	o := loadOracle(t)
	work, home := setupWorkspace(t)
	oldHome := os.Getenv("HOME")
	oldTUI := os.Getenv("BKX_TUI_COMMAND")
	t.Cleanup(func() { _ = os.Setenv("HOME", oldHome); _ = os.Setenv("BKX_TUI_COMMAND", oldTUI) })
	if err := os.Setenv("HOME", home); err != nil {
		t.Fatal(err)
	}
	if err := os.Setenv("BKX_TUI_COMMAND", "bkx-go17-definitely-missing"); err != nil {
		t.Fatal(err)
	}
	oldWD, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chdir(work); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chdir(oldWD) })

	for _, tc := range o.CLICases {
		t.Run(tc.Name, func(t *testing.T) {
			var stdout, stderr bytes.Buffer
			rc := publiccli.Run(expandArgs(tc.Args, work, home), &stdout, &stderr)
			if rc != tc.ExitCode {
				t.Fatalf("exit=%d want=%d stderr=%s", rc, tc.ExitCode, stderr.String())
			}
			got := normalizeJSON(t, stdout.String(), work, home)
			if !jsonEqual(got, tc.Stdout) {
				g, _ := json.MarshalIndent(got, "", "  ")
				w, _ := json.MarshalIndent(tc.Stdout, "", "  ")
				t.Fatalf("stdout drift\nGOT:\n%s\nWANT:\n%s", g, w)
			}
			if canonicalizeString(stderr.String(), work, home) != tc.Stderr {
				t.Fatalf("stderr=%q want=%q", stderr.String(), tc.Stderr)
			}
		})
	}

	for _, tc := range o.UsageCases {
		t.Run("usage-"+tc.Name, func(t *testing.T) {
			var stdout, stderr bytes.Buffer
			rc := publiccli.Run(expandArgs(tc.Args, work, home), &stdout, &stderr)
			if rc != tc.ExitCode {
				t.Fatalf("exit=%d want=%d", rc, tc.ExitCode)
			}
			if stdout.String() != fmt.Sprint(tc.Stdout) {
				t.Fatalf("stdout=%q want=%q", stdout.String(), tc.Stdout)
			}
			if canonicalizeString(stderr.String(), work, home) != tc.Stderr {
				t.Fatalf("stderr=%q want=%q", stderr.String(), tc.Stderr)
			}
		})
	}
}

func TestFrozenReferenceOracleBridge(t *testing.T) {
	o := loadOracle(t)
	ready := bridge.NewAndroid(nil).Readiness()
	if ready.ProtocolVersion != o.Bridge.ProtocolVersion || ready.Runtime != o.Bridge.Runtime || ready.PythonVersion != o.Bridge.PythonVersion {
		t.Fatalf("readiness identity drift: %#v", ready)
	}
	if len(bridge.HandlerOperations()) != o.Bridge.HandlerCount {
		t.Fatalf("handler count=%d want=%d", len(bridge.HandlerOperations()), o.Bridge.HandlerCount)
	}
	if len(bridge.SupportedOperations()) != o.Bridge.AdvertisedOperationCount {
		t.Fatalf("advertised count=%d want=%d", len(bridge.SupportedOperations()), o.Bridge.AdvertisedOperationCount)
	}
	caps := map[string]bool{}
	for _, cap := range ready.Capabilities {
		caps[cap] = true
	}
	for _, cap := range o.Bridge.CapabilityMinimum {
		if !caps[cap] {
			t.Fatalf("missing frozen capability %q", cap)
		}
	}
}
