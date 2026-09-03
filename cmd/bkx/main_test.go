package main

import "testing"

func TestScaffoldVersion(t *testing.T) {
	if code := run([]string{"--version"}); code != 0 {
		t.Fatalf("version returned %d", code)
	}
}

func TestScaffoldRefusesProductionCommands(t *testing.T) {
	for _, command := range []string{"backup", "archive", "restore", "transfer", "tools", "run", "ui"} {
		if code := run([]string{command}); code == 0 {
			t.Fatalf("%s unexpectedly accepted by GO01 scaffold", command)
		}
	}
}
