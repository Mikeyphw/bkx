package version

import "testing"

func TestMigrationIdentity(t *testing.T) {
	if Version != CompatibilityBaseline {
		t.Fatalf("GO19 version %q must match frozen compatibility baseline %q", Version, CompatibilityBaseline)
	}
	if MigrationPhase != "GO19" {
		t.Fatalf("migration phase = %q, want GO19", MigrationPhase)
	}
}
