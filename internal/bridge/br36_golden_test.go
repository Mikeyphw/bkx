package bridge

import (
	"encoding/json"
	"os"
	"testing"
)

func TestBR36GoldenRequestFixtureMatchesBridgeContract(t *testing.T) {
	raw, err := os.ReadFile("testdata/br36_request_backup_plan.json")
	if err != nil {
		t.Fatal(err)
	}
	request, err := parseRequest(string(raw))
	if err != nil {
		t.Fatal(err)
	}
	if request.ProtocolVersion != ProtocolVersion {
		t.Fatalf("protocol=%d", request.ProtocolVersion)
	}
	if request.RequestID != "br36-golden-backup-plan" || request.Operation != "backup.plan" {
		t.Fatalf("request=%+v", request)
	}
	var payload map[string]any
	if err := json.Unmarshal(request.Payload, &payload); err != nil {
		t.Fatal(err)
	}
	if payload["compression"] != "zstd" || payload["dry_run"] != true {
		t.Fatalf("payload=%v", payload)
	}
}

func TestBR36GoldenFailureFixtureMatchesAndroidResultEnvelope(t *testing.T) {
	raw, err := os.ReadFile("testdata/br36_result_failure.json")
	if err != nil {
		t.Fatal(err)
	}
	var result Result
	if err := json.Unmarshal(raw, &result); err != nil {
		t.Fatal(err)
	}
	if result.ProtocolVersion != ProtocolVersion || result.Status != "failure" {
		t.Fatalf("result=%+v", result)
	}
	if result.RequestID != "br36-golden-failure" || len(result.Errors) != 1 {
		t.Fatalf("result=%+v", result)
	}
	if got := result.Errors[0]; got.Code != "validation_error" || got.Message != "invalid input" || got.Action == "" {
		t.Fatalf("error=%+v", got)
	}
	if result.Metrics == nil || result.Metrics.DurationMS != 42 {
		t.Fatalf("metrics=%+v", result.Metrics)
	}
}
