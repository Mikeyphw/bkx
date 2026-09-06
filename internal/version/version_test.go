package version

import "testing"

func TestMigrationIdentity(t *testing.T) {
	if Version != CompatibilityBaseline {
		t.Fatalf("GO18 version %q must match frozen compatibility baseline %q", Version, CompatibilityBaseline)
	}
	if MigrationPhase != "GO18" {
		t.Fatalf("migration phase = %q, want GO18", MigrationPhase)
	}
}
