package rcloneops

import (
    "os"
    "path/filepath"
    "strings"
    "testing"
)

func TestLiteExplicitMissingPackagedRuntimeNeverFallsBackToPATH(t *testing.T) {
    fake := t.TempDir()
    binary := filepath.Join(fake, "rclone")
    if err := os.WriteFile(binary, []byte("#!/bin/sh\nexit 0\n"), 0o755); err != nil { t.Fatal(err) }
    t.Setenv("PATH", fake)
    t.Setenv("BKX_RCLONE_PATH", filepath.Join(t.TempDir(), "libbkxrclone_exec.so"))
    t.Setenv("BKX_RCLONE_CONFIG", filepath.Join(t.TempDir(), "rclone.conf"))
    runtime, err := RuntimeFromEnvironment()
    if err == nil || runtime.Executable != "" || !strings.Contains(err.Error(), "not executable") {
        t.Fatalf("expected fail-closed missing packaged runtime, got runtime=%+v err=%v", runtime, err)
    }
    if Available() || Configured() { t.Fatal("Lite must not advertise rclone from ambient PATH") }
}
