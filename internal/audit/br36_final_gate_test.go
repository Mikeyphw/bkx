package audit

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func br36RepoRoot(t *testing.T) string {
	t.Helper()
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("could not resolve BR36 audit test path")
	}
	return filepath.Clean(filepath.Join(filepath.Dir(file), "..", ".."))
}

func br36Read(t *testing.T, root, relative string) string {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(relative)))
	if err != nil {
		t.Fatalf("read %s: %v", relative, err)
	}
	return string(raw)
}

func TestBR36DefectLedgerCoversEveryResidualFinding(t *testing.T) {
	root := br36RepoRoot(t)
	ledger := br36Read(t, root, "docs/BR36_DEFECT_LEDGER.md")
	for _, marker := range []string{"Overlay 16 of 16", "A01-24", "A02-17", "A12-21", "C06-02"} {
		if !strings.Contains(ledger, marker) {
			t.Fatalf("BR36 ledger missing %q", marker)
		}
	}
}

func TestBR36SnapshotOrderingHasNoLexicalTimestampFallback(t *testing.T) {
	root := br36RepoRoot(t)
	source := br36Read(t, root, "internal/resticops/maintenance.go")
	for _, marker := range []string{
		"parseSnapshotTime(item.Time)",
		"time.Parse(time.RFC3339Nano",
		"ti.After(tj)",
		"snapshots[i].ID > snapshots[j].ID",
		"Restic snapshots returned an invalid timestamp",
	} {
		if !strings.Contains(source, marker) {
			t.Fatalf("snapshot ordering source missing %q", marker)
		}
	}
	regression := br36Read(t, root, "internal/resticops/br36_snapshot_ordering_test.go")
	for _, marker := range []string{"+02:00", "+01:00", "malformed timestamp"} {
		if !strings.Contains(regression, marker) {
			t.Fatalf("snapshot regression missing %q", marker)
		}
	}
}

func TestBR36GoGoldenBridgeFixturesArePinned(t *testing.T) {
	root := br36RepoRoot(t)
	for _, relative := range []string{
		"internal/bridge/testdata/br36_request_backup_plan.json",
		"internal/bridge/testdata/br36_result_failure.json",
		"internal/bridge/br36_golden_test.go",
	} {
		if _, err := os.Stat(filepath.Join(root, filepath.FromSlash(relative))); err != nil {
			t.Fatalf("missing BR36 Go bridge fixture/test %s: %v", relative, err)
		}
	}
}
