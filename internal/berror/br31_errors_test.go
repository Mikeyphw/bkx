package berror

import (
	"fmt"
	"testing"
)

func TestBR31WrappedTypedErrorsKeepExitCode(t *testing.T) {
	if got := ExitCode(fmt.Errorf("wrapped: %w", Permission("denied", nil))); got != ExitPermission {
		t.Fatalf("exit=%d", got)
	}
}

func TestBR31DetailsAreDeepCopied(t *testing.T) {
	details := map[string]any{"nested": map[string]any{"items": []any{"a"}}, "list": []string{"x"}}
	err := Validation("bad", details)
	details["nested"].(map[string]any)["items"].([]any)[0] = "changed"
	details["list"].([]string)[0] = "changed"
	if err.Details["nested"].(map[string]any)["items"].([]any)[0] != "a" || err.Details["list"].([]string)[0] != "x" {
		t.Fatalf("details were not deep copied: %#v", err.Details)
	}
}
