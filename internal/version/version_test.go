package version

import "testing"

func TestMigrationIdentity(t *testing.T) {
	if Version != PythonBaseline {
		t.Fatalf("GO15 version %q must match frozen Python baseline %q", Version, PythonBaseline)
	}
	if MigrationPhase != "GO15" {
		t.Fatalf("migration phase = %q, want GO15", MigrationPhase)
	}
}
