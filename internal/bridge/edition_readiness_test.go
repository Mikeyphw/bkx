package bridge

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLiteReadinessWithMissingPackagedRcloneKeepsNativeCapabilities(t *testing.T) {
	fake := t.TempDir()
	if err := os.WriteFile(filepath.Join(fake, "rclone"), []byte("#!/bin/sh\nexit 0\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", fake)
	t.Setenv("BKX_RCLONE_PATH", filepath.Join(t.TempDir(), "libbkxrclone_exec.so"))
	t.Setenv("BKX_RCLONE_CONFIG", filepath.Join(t.TempDir(), "rclone.conf"))
	readiness := NewAndroid(nil).Readiness()
	caps := map[string]bool{}
	for _, capability := range readiness.Capabilities {
		caps[capability] = true
	}
	for _, forbidden := range []string{"remote.rclone", "remote.rclone.configured", "transfer.remote", "cloud.google_drive", "backup.snapshot.remote.rclone", "backup.remote", "archive.remote", "restore.remote"} {
		if caps[forbidden] {
			t.Fatalf("Lite advertised absent rclone capability %q", forbidden)
		}
	}
	if len(readiness.Capabilities) == 0 {
		t.Fatal("Lite lost all Go readiness capabilities")
	}
}
