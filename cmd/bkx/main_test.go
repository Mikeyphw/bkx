package main

import "testing"

func TestGO15PublicVersionPath(t *testing.T) {
	if code := run([]string{"--version"}); code != 0 {
		t.Fatalf("run(--version)=%d", code)
	}
}

func TestGO15PublicGroupsAreOwnedByGoCLI(t *testing.T) {
	for _, args := range [][]string{{"backup"}, {"archive"}, {"restore"}, {"transfer"}, {"tools"}, {"run"}, {"ui"}} {
		if code := run(args); code != 0 {
			t.Fatalf("GO15 public group %v returned %d", args, code)
		}
	}
}

func TestMigrationProbeUsageDoesNotBecomeProductCommand(t *testing.T) {
	if code := run([]string{"migration", "run-history"}); code == 0 {
		t.Fatal("incomplete migration probe accepted")
	}
}

func TestGO10ServiceProbeDoesNotBecomeProductCommand(t *testing.T) {
	if code := run([]string{"migration", "service", "operations"}); code != 0 {
		t.Fatalf("GO10 service operations probe failed: %d", code)
	}
	for _, args := range [][]string{{"service"}, {"service", "dispatch"}} {
		if code := run(args); code == 0 {
			t.Fatalf("GO10 service probe leaked into product command %v", args)
		}
	}
}

func TestGO11BridgeProbeDoesNotBecomeProductCommand(t *testing.T) {
	if code := run([]string{"migration", "bridge", "readiness"}); code != 0 {
		t.Fatalf("GO11 bridge readiness probe failed: %d", code)
	}
	for _, args := range [][]string{{"bridge"}, {"bridge", "execute"}, {"bridge", "readiness"}} {
		if code := run(args); code == 0 {
			t.Fatalf("GO11 bridge probe leaked into product command %v", args)
		}
	}
}
