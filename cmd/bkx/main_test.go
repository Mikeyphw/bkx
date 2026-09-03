package main

import "testing"

func TestScaffoldVersionPath(t *testing.T) {
	if code := run([]string{"--version"}); code != 0 {
		t.Fatalf("run(--version)=%d", code)
	}
}
func TestScaffoldRefusesProductionCommands(t *testing.T) {
	for _, args := range [][]string{{"backup"}, {"archive", "list"}, {"restore"}, {"transfer"}, {"tools"}, {"run"}, {"ui"}} {
		if code := run(args); code == 0 {
			t.Fatalf("migration scaffold accepted production command %v", args)
		}
	}
}
func TestMigrationProbeUsageDoesNotBecomeProductCommand(t *testing.T) {
	if code := run([]string{"migration", "run-history"}); code == 0 {
		t.Fatal("incomplete migration probe accepted")
	}
}
