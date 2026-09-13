package publiccli

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"

	"bkx/internal/berror"
)

type failingWriter struct{}

func (failingWriter) Write([]byte) (int, error) { return 0, errors.New("write failed") }

func TestBR31LeafParserFailuresUseUsageExitAndJSONBoundary(t *testing.T) {
	var stdout, stderr bytes.Buffer
	code := Run([]string{"--json", "archive", "info", "--bogus"}, &stdout, &stderr)
	if code != berror.ExitUsage {
		t.Fatalf("code=%d stdout=%q stderr=%q", code, stdout.String(), stderr.String())
	}
	if stderr.Len() != 0 {
		t.Fatalf("json usage errors must not write stderr: %q", stderr.String())
	}
	lines := strings.Split(strings.TrimSpace(stdout.String()), "\n")
	if len(lines) == 0 || !strings.HasPrefix(strings.TrimSpace(stdout.String()), "{") {
		t.Fatalf("expected one json object, got %q", stdout.String())
	}
	var payload map[string]any
	if err := json.Unmarshal(stdout.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload["type"] != "error" || payload["error_type"] != string(berror.KindValidation) || payload["exit_code"].(float64) != float64(berror.ExitUsage) {
		t.Fatalf("bad payload: %#v", payload)
	}
}

func TestBR31UnknownCommandJSONAndHelpAvoidConfigLoad(t *testing.T) {
	var stdout, stderr bytes.Buffer
	code := Run([]string{"--json", "nope", "thing"}, &stdout, &stderr)
	if code != berror.ExitUsage || stderr.Len() != 0 {
		t.Fatalf("code=%d stdout=%q stderr=%q", code, stdout.String(), stderr.String())
	}
	var help bytes.Buffer
	code = Run([]string{"state", "status", "--help"}, &help, &stderr)
	if code != berror.ExitOK || !strings.Contains(help.String(), "Usage: bkx state status") {
		t.Fatalf("help code=%d help=%q", code, help.String())
	}
}

func TestBR31InlineEqualsOptionsAndProgressAreParsed(t *testing.T) {
	parsed, help, err := parseLeaf([]string{"--name=my-archive", "--include=one", "--include", "two", "src"}, backupOptions())
	if err != nil || help {
		t.Fatalf("parse failed help=%v err=%v", help, err)
	}
	if got, _ := parsed.stringValue("name"); got != "my-archive" {
		t.Fatalf("name=%q", got)
	}
	if got := strings.Join(parsed.multiValue("include"), ","); got != "one,two" {
		t.Fatalf("include=%q", got)
	}
	globals, rest, _, err := parseGlobals([]string{"--progress=plain", "backup", "create"})
	if err != nil || globals.Progress != "plain" || strings.Join(rest, " ") != "backup create" {
		t.Fatalf("globals=%#v rest=%v err=%v", globals, rest, err)
	}
}

func TestBR31JSONNumbersTimingAndErrorsAreStable(t *testing.T) {
	value := map[string]any{"success": true, "big": int64(9223372036854775807), "duration_ms": int64(12)}
	payload, err := resultPayload(value, "probe")
	if err != nil {
		t.Fatal(err)
	}
	if _, exists := payload["timing"]; exists {
		t.Fatalf("renderer fabricated timing: %#v", payload)
	}
	if _, ok := payload["big"].(json.Number); !ok {
		t.Fatalf("int64 should survive as json.Number, got %T %#v", payload["big"], payload["big"])
	}
	var stdout, stderr bytes.Buffer
	tool := "restic"
	exit := 99
	st := "secret-free stderr"
	typed := berror.External("tool failed", map[string]any{"nested": map[string]any{"a": []any{"b"}}})
	typed.ToolName = &tool
	typed.ExitStatus = &exit
	typed.Stderr = &st
	code := renderDomainError(renderer{json: true, stdout: &stdout, stderr: &stderr}, nil, fmt.Errorf("wrapped: %w", typed))
	if code != berror.ExitExternal || stderr.Len() != 0 {
		t.Fatalf("code=%d stdout=%q stderr=%q", code, stdout.String(), stderr.String())
	}
	var out map[string]any
	if err := json.Unmarshal(stdout.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	if out["error_type"] != string(berror.KindExternal) || out["tool_name"] != tool || out["tool_exit_code"].(float64) != float64(exit) || out["tool_stderr"] != st {
		t.Fatalf("extended metadata lost: %#v", out)
	}
}

func TestBR31OutputWriteFailuresReturnExternal(t *testing.T) {
	code := Run([]string{"--version"}, failingWriter{}, &bytes.Buffer{})
	if code != berror.ExitExternal {
		t.Fatalf("code=%d", code)
	}
}
