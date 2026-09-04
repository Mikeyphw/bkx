package version

import "testing"

func TestMigrationIdentity(t *testing.T) {
	if Version != PythonBaseline {
		t.Fatalf("GO07 version %q must match frozen Python baseline %q", Version, PythonBaseline)
	}
	if MigrationPhase != "GO07" {
		t.Fatalf("migration phase = %q, want GO07", MigrationPhase)
	}
}
