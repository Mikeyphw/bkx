package bridge

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"
)

func requestJSON(t *testing.T, requestID, operation string, payload any) string {
	t.Helper()
	doc := map[string]any{
		"protocol_version": 1,
		"request_id":       requestID,
		"operation":        operation,
		"payload":          payload,
	}
	data, err := json.Marshal(doc)
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

func TestFrozenBridgeRegistryAndReadiness(t *testing.T) {
	contractPath := filepath.Join("..", "..", "migration", "contracts", "v1", "bridge.json")
	data, err := os.ReadFile(contractPath)
	if err != nil {
		t.Fatal(err)
	}
	var frozen struct {
		Advertised []string `json:"advertisedOperations"`
		Handlers   []string `json:"handlers"`
		Caps       []string `json:"capabilities"`
	}
	if err := json.Unmarshal(data, &frozen); err != nil {
		t.Fatal(err)
	}
	if got := SupportedOperations(); !reflect.DeepEqual(got, frozen.Advertised) {
		t.Fatalf("advertised registry drift: %#v != %#v", got, frozen.Advertised)
	}
	gotHandlers := HandlerOperations()
	sort.Strings(frozen.Handlers)
	if !reflect.DeepEqual(gotHandlers, frozen.Handlers) {
		t.Fatalf("handler registry drift: %#v != %#v", gotHandlers, frozen.Handlers)
	}
	ready := NewAndroid(nil).Readiness()
	if ready.ProtocolVersion != 1 || ready.Runtime != AndroidRuntime || ready.BackendVersion == "" {
		t.Fatalf("unexpected readiness: %#v", ready)
	}
	capset := map[string]bool{}
	for _, cap := range ready.Capabilities {
		capset[cap] = true
	}
	for _, cap := range frozen.Caps {
		if !capset[cap] {
			t.Fatalf("readiness missing frozen capability %q", cap)
		}
	}
	for _, capability := range []string{
		"filesystem.direct",
		"archive.compression.none",
		"archive.compression.gzip",
		"archive.compression.zstd",
		"archive.split",
	} {
		if !capset[capability] {
			t.Fatalf("readiness must expose capability %q", capability)
		}
	}
}

func TestAndroidRuntimeReadinessIdentity(t *testing.T) {
	ready := NewAndroid(nil).Readiness()
	if ready.Runtime != AndroidRuntime {
		t.Fatalf("unexpected Android runtime identity: %#v", ready)
	}
	if ready.ProtocolVersion != 1 || ready.PythonVersion != "none" {
		t.Fatalf("unexpected Android readiness: %#v", ready)
	}
}

func TestExecuteSuccessEnvelopeMetricsAndProgressSequence(t *testing.T) {
	root := t.TempDir()
	path := filepath.Join(root, "hello.txt")
	if err := os.WriteFile(path, []byte("hello"), 0o600); err != nil {
		t.Fatal(err)
	}
	rt := NewAndroid(nil)
	result := rt.Execute(requestJSON(t, "stat-1", "filesystem.stat", map[string]any{"path": path}))
	if result.Status != "success" || result.RequestID != "stat-1" || result.ProtocolVersion != 1 {
		t.Fatalf("unexpected result: %#v", result)
	}
	if result.Metrics == nil || result.Metrics.DurationMS < 0 {
		t.Fatalf("missing metrics: %#v", result.Metrics)
	}
	if result.Errors == nil || result.Artifacts == nil || len(result.Artifacts) != 1 {
		t.Fatalf("envelope arrays are not canonical: %#v", result)
	}
	progress, err := rt.Progress("stat-1")
	if err != nil {
		t.Fatal(err)
	}
	if progress == nil || progress.Seq < 2 || progress.Phase != "success" || progress.Progress == nil || *progress.Progress != 1 {
		t.Fatalf("unexpected final progress: %#v", progress)
	}
}

func TestMalformedRequestsFailClosedWithProtocolEnvelope(t *testing.T) {
	rt := NewAndroid(nil)
	cases := []struct {
		name string
		raw  string
		code string
	}{
		{"empty", "", "validation_error"},
		{"array", "[]", "validation_error"},
		{"bad-json", "{bad", "validation_error"},
		{"wrong-protocol", `{"protocol_version":2,"request_id":"x","operation":"filesystem.stat","payload":{}}`, "validation_error"},
		{"unsafe-id", `{"protocol_version":1,"request_id":"bad id","operation":"filesystem.stat","payload":{}}`, "validation_error"},
		{"bad-payload", `{"protocol_version":1,"request_id":"x","operation":"filesystem.stat","payload":[]}`, "validation_error"},
		{"trailing", `{"protocol_version":1,"request_id":"x","operation":"filesystem.stat","payload":{}} {}`, "validation_error"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			result := rt.Execute(tc.raw)
			if result.Status != "failure" || result.ProtocolVersion != 1 || result.RequestID != "unknown" {
				t.Fatalf("unexpected failure envelope: %#v", result)
			}
			if result.Metrics == nil || len(result.Errors) != 1 || result.Errors[0].Code != tc.code || len(result.Artifacts) != 0 {
				t.Fatalf("unexpected typed failure: %#v", result)
			}
		})
	}

	// Python accepts numeric 1.0 because 1.0 == 1. Keep that protocol edge.
	good := rt.Execute(`{"protocol_version":1.0,"request_id":"numeric-one","operation":"storage.locations","payload":{}}`)
	if good.Status != "success" {
		t.Fatalf("numeric protocol 1.0 must be accepted: %#v", good)
	}
}

func TestUnknownOperationAndTypedNotFoundMapping(t *testing.T) {
	rt := NewAndroid(nil)
	unknown := rt.Execute(requestJSON(t, "no-shell", "shell.exec", map[string]any{}))
	if unknown.RequestID != "no-shell" || unknown.Status != "failure" || len(unknown.Errors) != 1 || unknown.Errors[0].Code != "validation_error" {
		t.Fatalf("unexpected unknown-op mapping: %#v", unknown)
	}
	if unknown.Errors[0].Action != "Review the selected files and operation options." {
		t.Fatalf("unexpected validation action: %#v", unknown.Errors[0])
	}

	missing := filepath.Join(t.TempDir(), "does-not-exist")
	notFound := rt.Execute(requestJSON(t, "missing", "filesystem.stat", map[string]any{"path": missing}))
	if notFound.Status != "failure" || len(notFound.Errors) != 1 || notFound.Errors[0].Code != "not_found" {
		t.Fatalf("unexpected not-found mapping: %#v", notFound)
	}
	if notFound.Errors[0].Action != "Choose an existing source, archive, or destination." {
		t.Fatalf("unexpected not-found action: %#v", notFound.Errors[0])
	}
}

func TestCancelEndpointAndQueuedCancellationParity(t *testing.T) {
	rt := NewAndroid(nil)
	ack := rt.Cancel("queued-request")
	if ack.Status != "success" || ack.RequestID != "queued-request" || ack.Metrics != nil || len(ack.Errors) != 0 || len(ack.Artifacts) != 0 {
		t.Fatalf("unexpected cancel acknowledgement: %#v", ack)
	}
	if p, err := rt.Progress("queued-request"); err != nil || p != nil {
		t.Fatalf("cancel endpoint must not create progress: %#v %v", p, err)
	}
	result := rt.Execute(requestJSON(t, "queued-request", "storage.locations", map[string]any{}))
	if result.Status != "cancelled" || result.RequestID != "queued-request" {
		t.Fatalf("queued cancellation did not stop execution: %#v", result)
	}
	p, err := rt.Progress("queued-request")
	if err != nil || p == nil || p.Phase != "cancelled" || p.Progress != nil || p.Seq != 2 {
		t.Fatalf("unexpected cancellation progress: %#v %v", p, err)
	}
}

func TestRunCancelHandlerCancelsAnotherRequestWithoutAdvertisingIt(t *testing.T) {
	rt := NewAndroid(nil)
	result := rt.Execute(requestJSON(t, "cancel-command", "run.cancel", map[string]any{"target_request_id": "target-request"}))
	if result.Status != "success" || result.Summary != "Cancellation recorded for target-request." {
		t.Fatalf("unexpected run.cancel result: %#v", result)
	}
	for _, op := range SupportedOperations() {
		if op == "run.cancel" {
			t.Fatal("run.cancel must not become an advertised operation")
		}
	}
	cancelledResult := rt.Execute(requestJSON(t, "target-request", "storage.locations", map[string]any{}))
	if cancelledResult.Status != "cancelled" {
		t.Fatalf("run.cancel target was not cancelled: %#v", cancelledResult)
	}
}

func TestProgressSequenceContinuesWhenRequestIDIsReused(t *testing.T) {
	rt := NewAndroid(nil)
	raw := requestJSON(t, "repeat", "storage.locations", map[string]any{})
	if first := rt.Execute(raw); first.Status != "success" {
		t.Fatal(first.Summary)
	}
	p1, _ := rt.Progress("repeat")
	if second := rt.Execute(raw); second.Status != "success" {
		t.Fatal(second.Summary)
	}
	p2, _ := rt.Progress("repeat")
	if p1 == nil || p2 == nil || p2.Seq <= p1.Seq {
		t.Fatalf("sequence did not continue: %#v -> %#v", p1, p2)
	}
}

func TestServiceProgressMetricsCrossAndroidBridge(t *testing.T) {
	rt := NewAndroid(nil)
	progress := 0.25
	filesDone, filesTotal := int64(2), int64(8)
	bytesDone, bytesTotal := int64(256), int64(2048)
	rt.emitDetailed("metrics-1", "archiving", "Writing", &progress, &filesDone, &filesTotal, &bytesDone, &bytesTotal)

	got, err := rt.Progress("metrics-1")
	if err != nil {
		t.Fatal(err)
	}
	if got == nil || got.FilesDone == nil || *got.FilesDone != 2 || got.FilesTotal == nil || *got.FilesTotal != 8 {
		t.Fatalf("bridge lost file progress metrics: %#v", got)
	}
	if got.BytesDone == nil || *got.BytesDone != 256 || got.BytesTotal == nil || *got.BytesTotal != 2048 {
		t.Fatalf("bridge lost byte progress metrics: %#v", got)
	}
}

func TestBackupPlanPreflightMetricsSurviveBridgeEnvelope(t *testing.T) {
	root := t.TempDir()
	src := filepath.Join(root, "src")
	dst := filepath.Join(root, "backups")
	if err := os.MkdirAll(src, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(dst, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(src, "data.bin"), []byte("123456789"), 0o600); err != nil {
		t.Fatal(err)
	}
	rt := NewAndroid(nil)
	payload := map[string]any{
		"source":      map[string]any{"path": src},
		"destination": map[string]any{"path": dst},
		"filters":     map[string]any{"include_patterns": []string{}, "exclude_patterns": []string{}, "follow_symlinks": false},
		"archive": map[string]any{
			"archive_name":      "preflight",
			"compression":       "gzip",
			"compression_level": 3,
			"split":             map[string]any{"enabled": false},
			"verification":      map[string]any{"verify_after_create": true},
		},
	}
	result := rt.Execute(requestJSON(t, "backup-preflight", "backup.plan", payload))
	if result.Status != "success" {
		t.Fatalf("result=%#v", result)
	}
	if result.Metrics == nil || result.Metrics.FilesTotal == nil || *result.Metrics.FilesTotal != 1 {
		t.Fatalf("missing files_total preflight metric: %#v", result.Metrics)
	}
	if result.Metrics.BytesTotal == nil || *result.Metrics.BytesTotal != 9 {
		t.Fatalf("missing bytes_total preflight metric: %#v", result.Metrics)
	}
	if result.Metrics.DurationMS < 0 {
		t.Fatalf("invalid duration: %#v", result.Metrics)
	}
}

func TestReadinessAdvertisesRcloneOnlyWhenRuntimeIsAvailable(t *testing.T) {
	root := t.TempDir()
	rclone := filepath.Join(root, "rclone")
	if err := os.WriteFile(rclone, []byte("#!/bin/sh\nexit 0\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	config := filepath.Join(root, "rclone.conf")
	if err := os.WriteFile(config, []byte("[drive]\ntype = drive\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("BKX_RCLONE_PATH", rclone)
	t.Setenv("BKX_RCLONE_CONFIG", config)
	readiness := NewAndroid(nil).Readiness()
	joined := strings.Join(readiness.Capabilities, "\n")
	for _, capability := range []string{"remote.rclone", "remote.rclone.configured", "transfer.remote", "transfer.sync", "backup.remote", "archive.remote", "restore.remote", "cloud.google_drive"} {
		if !strings.Contains(joined, capability) {
			t.Fatalf("missing capability %q in %v", capability, readiness.Capabilities)
		}
	}
}

func TestPostV1RepositoryExtensionsAreExecutableWithoutAdvertisingRegistryDrift(t *testing.T) {
	advertised := map[string]bool{}
	for _, op := range SupportedOperations() {
		advertised[op] = true
	}
	extensions := map[string]bool{}
	for _, op := range ExtensionOperations() {
		extensions[op] = true
	}
	executable := map[string]bool{}
	for _, op := range ExecutableOperations() {
		executable[op] = true
	}
	for _, op := range []string{"backup.snapshot", "repository.init", "repositories.add", "repositories.list", "repositories.show", "repositories.remove"} {
		if advertised[op] {
			t.Fatalf("post-v1 extension must not drift frozen advertised operations: %s", op)
		}
		if !extensions[op] || !executable[op] {
			t.Fatalf("post-v1 extension must remain executable: %s", op)
		}
	}
}
