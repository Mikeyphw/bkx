package rcloneops

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestMkdirUsesConfiguredRemoteAndCanonicalChild(t *testing.T) {
	root := t.TempDir()
	exe := filepath.Join(root, "rclone")
	log := filepath.Join(root, "args")
	script := "#!/bin/sh\nprintf '%s\\n' \"$@\" > " + log + "\n"
	if err := os.WriteFile(exe, []byte(script), 0755); err != nil {
		t.Fatal(err)
	}
	cfg := filepath.Join(root, "rclone.conf")
	if err := os.WriteFile(cfg, []byte("[cloud]\\ntype = drive\\n"), 0600); err != nil {
		t.Fatal(err)
	}
	r := Runtime{Executable: exe, Config: cfg}
	child, err := r.Mkdir(context.Background(), "rclone://cloud/Backups", "BKX")
	if err != nil {
		t.Fatal(err)
	}
	if child != "rclone://cloud/Backups/BKX" {
		t.Fatalf("child=%q", child)
	}
	got, _ := os.ReadFile(log)
	if !strings.Contains(string(got), "mkdir\ncloud:Backups/BKX") {
		t.Fatalf("args=%q", got)
	}
}

func TestMkdirRejectsNestedName(t *testing.T) {
	r := Runtime{}
	if _, err := r.Mkdir(context.Background(), "rclone://cloud/", "bad/name"); err == nil {
		t.Fatal("expected validation error")
	}
}
