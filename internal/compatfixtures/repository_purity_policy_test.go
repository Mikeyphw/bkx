package compatfixtures

import (
	"testing"
)

func TestReviewedPythonToolPolicy(t *testing.T) {
	cases := []struct {
		path    string
		allowed bool
	}{
		{"scripts/bkx-devtool-release.py", true},
		{"scripts/bkx-rel07-build-matching-instrumentation.py", true},
		{"scripts/verify-bkx-go-android-executable.py", true},
		{"scripts/test-bkx-rel09-ci-hf01.py", true},
		{"scripts/rogue-release-script.py", false},
		{"scripts/evil.pyc", false},
		{"internal/bridge/rogue.py", false},
		{"cmd/bkx/legacy.py", false},
		{"src/bkx/legacy.py", false},
		{"tools/go_migration/legacy.py", false},
	}
	for _, tc := range cases {
		if allowedOperationalPython(tc.path) != tc.allowed {
			t.Errorf("path %q: allowed=%t, want=%t", tc.path, allowedOperationalPython(tc.path), tc.allowed)
		}
	}
}

func TestAndroidGoRuntimePythonHookFailClosed(t *testing.T) {
	const ok = `python3 "$repo/scripts/verify-bkx-go-android-executable.py" "$tmp"`
	cases := []struct {
		script   string
		approved bool
	}{
		{ok, true},
		{"echo native\n" + ok, true},
		{ok + "\n" + ok, false},
		{ok + "\npython3 unexpected.py", false},
		{`python3 "$repo/scripts/verify-bkx-go-android-executable.py" "$wrong"`, false},
		{`python3 "$repo/scripts/other-check.py" "$tmp"`, false},
		{"", false},
	}
	for _, tc := range cases {
		if reviewedRuntimePythonHook(tc.script) != tc.approved {
			t.Errorf("script %q: accepted=%t, want=%t", tc.script, reviewedRuntimePythonHook(tc.script), tc.approved)
		}
	}
}
