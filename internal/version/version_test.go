package version

import "testing"

func TestMigrationIdentity(t *testing.T) {
	if Version != CompatibilityBaseline {
		t.Fatalf("GO20 version %q must match frozen compatibility baseline %q", Version, CompatibilityBaseline)
	}
	if MigrationPhase != "GO20" {
		t.Fatalf("migration phase = %q, want GO20", MigrationPhase)
	}
}
