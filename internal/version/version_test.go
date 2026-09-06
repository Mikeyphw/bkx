package version

import "testing"

func TestMigrationIdentity(t *testing.T) {
	if Version != PythonBaseline {
		t.Fatalf("GO09 version %q must match frozen Python baseline %q", Version, PythonBaseline)
	}
	if MigrationPhase != "GO09" {
		t.Fatalf("migration phase = %q, want GO09", MigrationPhase)
	}
}
