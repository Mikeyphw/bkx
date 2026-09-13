package main

import (
	"bytes"
	"testing"

	"bkx/internal/publiccli"
)

func runPublicSilently(args []string) int {
	var stdout, stderr bytes.Buffer
	return publiccli.Run(args, &stdout, &stderr)
}

func TestProductionVersionPath(t *testing.T) {
	if code := runPublicSilently([]string{"--version"}); code != 0 {
		t.Fatalf("run(--version)=%d", code)
	}
}

func TestPublicGroupsAreOwnedByGoCLI(t *testing.T) {
	for _, args := range [][]string{{"backup"}, {"archive"}, {"restore"}, {"transfer"}, {"tools"}, {"run"}, {"ui"}} {
		if code := runPublicSilently(args); code != 0 {
			t.Fatalf("public group %v returned %d", args, code)
		}
	}
}

func TestGO20MigrationProbeNamespaceIsRetired(t *testing.T) {
	if publiccli.IsInternalInvocation([]string{"migration", "bridge", "readiness"}) {
		t.Fatal("retired migration namespace is still claimed as an internal route")
	}
	for _, args := range [][]string{{"migration", "bridge"}, {"migration", "service"}, {"version"}} {
		if code := runPublicSilently(args); code == 0 {
			t.Fatalf("retired internal command unexpectedly accepted: %v", args)
		}
	}
}

func TestGO20OnlyMaintenanceAndAndroidRootsRemainInternal(t *testing.T) {
	if !publiccli.IsInternalInvocation([]string{"contract", "check"}) {
		t.Fatal("contract check maintenance route is not internal")
	}
	if !publiccli.IsInternalInvocation([]string{"android-runtime", "bridge-session", "-"}) {
		t.Fatal("Android runtime transport route is not internal")
	}
	for _, args := range [][]string{{"service"}, {"bridge"}, {"migration"}, {"version"}} {
		if publiccli.IsInternalInvocation(args) {
			t.Fatalf("unexpected internal root survives GO20: %v", args)
		}
	}
}

func TestBR22BridgeSessionResourceLimitsAreExplicit(t *testing.T) {
	if maxBridgeSessionCommandBytes != 4*1024*1024 {
		t.Fatalf("unexpected command limit: %d", maxBridgeSessionCommandBytes)
	}
	if maxBridgeSessionReplyBytes != 4*1024*1024 {
		t.Fatalf("unexpected reply limit: %d", maxBridgeSessionReplyBytes)
	}
	if maxBridgeSessionConcurrency <= 0 || maxBridgeSessionConcurrency > 32 {
		t.Fatalf("unexpected session concurrency limit: %d", maxBridgeSessionConcurrency)
	}
}
