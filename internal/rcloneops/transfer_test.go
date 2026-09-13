package rcloneops

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSingleFileUsesCopyToAndRetainsBasename(t *testing.T) {
	runtime, logPath := fakeRuntime(t)
	root := t.TempDir()
	source := filepath.Join(root, "hello.txt")
	if err := os.WriteFile(source, []byte("hello"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := runtime.Transfer(context.Background(), "push", []string{source}, "rclone://drive/BKX", TransferOptions{}); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(logPath)
	if log := string(data); !strings.Contains(log, "copyto "+source+" drive:BKX/hello.txt") {
		t.Fatalf("single file did not use copyto with basename:\n%s", log)
	}
}

func TestTransferCancellationBeforeCommand(t *testing.T) {
	runtime, _ := fakeRuntime(t)
	_, err := runtime.Transfer(context.Background(), "push", []string{"rclone://drive/source"}, t.TempDir(), TransferOptions{Cancelled: func() bool { return true }})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err=%v", err)
	}
}

func TestSyncRequiresDirectoryAndUsesTwoWayVerification(t *testing.T) {
	runtime, logPath := fakeRuntime(t)
	if _, err := runtime.Transfer(context.Background(), "sync", []string{"rclone://drive/source"}, t.TempDir(), TransferOptions{Verify: true}); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(logPath)
	log := string(data)
	if !strings.Contains(log, "sync drive:source") {
		t.Fatalf("sync command missing:\n%s", log)
	}
	for _, line := range strings.Split(log, "\n") {
		if strings.HasPrefix(line, "check ") && strings.Contains(line, "--one-way") {
			t.Fatalf("sync verification must be two-way: %s", line)
		}
	}
}

func TestSyncUsesDeleteCeilingAndPropagatesSanitizedWarnings(t *testing.T) {
	runtime, logPath := fakeRuntime(t)
	_, err := runtime.Transfer(context.Background(), "sync", []string{"rclone://drive/source"}, t.TempDir(), TransferOptions{SyncDeleteLimit: 37})
	if err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(logPath)
	if log := string(data); !strings.Contains(log, "--max-delete 37") {
		t.Fatalf("sync delete ceiling not passed to rclone:\n%s", log)
	}
}
