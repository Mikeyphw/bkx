package version

import "testing"

func TestMigrationIdentity(t *testing.T) {
	if Version != PythonBaseline {
		t.Fatalf("GO11 version %q must match frozen Python baseline %q", Version, PythonBaseline)
	}
	if MigrationPhase != "GO11" {
		t.Fatalf("migration phase = %q, want GO11", MigrationPhase)
	}
}
