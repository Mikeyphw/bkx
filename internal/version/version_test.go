package version

import "testing"

func TestMigrationIdentity(t *testing.T) {
	if Version != PythonBaseline {
		t.Fatalf("GO05 version %q must match frozen Python baseline %q", Version, PythonBaseline)
	}
	if MigrationPhase != "GO05" {
		t.Fatalf("migration phase = %q, want GO05", MigrationPhase)
	}
}
