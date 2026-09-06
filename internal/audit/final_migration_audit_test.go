package audit

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"bkx/internal/bridge"
	"bkx/internal/contracts"
	"bkx/internal/services"
)

type finalAudit struct {
	SchemaVersion            int    `json:"schema_version"`
	Product                  string `json:"product"`
	ProductVersion           string `json:"product_version"`
	MigrationFinalAuditPhase string `json:"migration_final_audit_phase"`
	Roadmap                  []struct {
		Phase  string `json:"phase"`
		Status string `json:"status"`
	} `json:"roadmap"`
	Compatibility struct {
		CLI struct {
			CommandNodes          int `json:"command_nodes"`
			LeafCommands          int `json:"leaf_commands"`
			RootParameterFamilies int `json:"root_parameter_families"`
		} `json:"cli"`
		Bridge struct {
			ProtocolVersion      int    `json:"protocol_version"`
			AdvertisedOperations int    `json:"advertised_operations"`
			Handlers             int    `json:"handlers"`
			FrozenCapabilities   int    `json:"frozen_capabilities"`
			Runtime              string `json:"runtime"`
			PythonVersion        string `json:"python_version"`
		} `json:"bridge"`
	} `json:"compatibility"`
	Android struct {
		Runtime               string   `json:"runtime"`
		ABI                   []string `json:"abi"`
		NDK                   string   `json:"ndk"`
		NDKMajorFloor         int      `json:"ndk_major_floor"`
		LocalStateNamespace   string   `json:"local_state_namespace"`
		RootStateNamespace    string   `json:"root_state_namespace"`
		InstrumentationPolicy string   `json:"instrumentation_policy"`
	} `json:"android"`
	OpenPromises             []string `json:"open_promises"`
	GO20RetirementCandidates []string `json:"go20_retirement_candidates"`
}

type cliContract struct {
	CommandNodeCount int               `json:"commandNodeCount"`
	LeafCommandCount int               `json:"leafCommandCount"`
	RootParameters   []json.RawMessage `json:"rootParameters"`
}

func repoRoot(t *testing.T) string {
	t.Helper()
	p, err := filepath.Abs(filepath.Join("..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	return p
}
func read(t *testing.T, root, rel string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(rel)))
	if err != nil {
		t.Fatalf("read %s: %v", rel, err)
	}
	return b
}
func mustContain(t *testing.T, text string, needles ...string) {
	t.Helper()
	for _, n := range needles {
		if !strings.Contains(text, n) {
			t.Fatalf("missing required audit token %q", n)
		}
	}
}

func TestGO19RoadmapAndFrozenContractAudit(t *testing.T) {
	root := repoRoot(t)
	data := read(t, root, "migration/audit/v1/final_audit.json")
	var audit finalAudit
	if err := json.Unmarshal(data, &audit); err != nil {
		t.Fatal(err)
	}
	if audit.SchemaVersion != 1 || audit.Product != "BKX" || audit.ProductVersion != "1.0.0" || audit.MigrationFinalAuditPhase != "GO19" {
		t.Fatalf("unexpected audit header: %#v", audit)
	}
	if len(audit.Roadmap) != 20 {
		t.Fatalf("roadmap entries=%d want=20 (HG01-HG03 + GO00..GO18)", len(audit.Roadmap))
	}
	for _, phase := range audit.Roadmap {
		if phase.Status != "complete" {
			t.Fatalf("roadmap phase not complete: %#v", phase)
		}
	}
	if len(audit.OpenPromises) != 0 {
		t.Fatalf("open promises remain: %#v", audit.OpenPromises)
	}
	if len(audit.GO20RetirementCandidates) != 3 {
		t.Fatalf("GO20 retirement set drifted: %#v", audit.GO20RetirementCandidates)
	}

	bundle, err := contracts.LoadDir(filepath.Join(root, "migration", "contracts", "v1"))
	if err != nil {
		t.Fatal(err)
	}
	if err := bundle.Validate(); err != nil {
		t.Fatal(err)
	}
	var cli cliContract
	if err := json.Unmarshal(read(t, root, "migration/contracts/v1/cli.json"), &cli); err != nil {
		t.Fatal(err)
	}
	if cli.CommandNodeCount != audit.Compatibility.CLI.CommandNodes || cli.LeafCommandCount != audit.Compatibility.CLI.LeafCommands || len(cli.RootParameters) != audit.Compatibility.CLI.RootParameterFamilies {
		t.Fatalf("CLI audit mismatch: %#v / %#v", cli, audit.Compatibility.CLI)
	}
	if !reflect.DeepEqual(services.SupportedOperations(), bridge.SupportedOperations()) {
		t.Fatal("service and bridge advertised operations differ")
	}
	if len(bridge.SupportedOperations()) != audit.Compatibility.Bridge.AdvertisedOperations {
		t.Fatalf("advertised operations=%d", len(bridge.SupportedOperations()))
	}
	if len(bridge.HandlerOperations()) != audit.Compatibility.Bridge.Handlers {
		t.Fatalf("handlers=%d", len(bridge.HandlerOperations()))
	}
	ready := bridge.NewAndroid(nil).Readiness()
	if ready.ProtocolVersion != audit.Compatibility.Bridge.ProtocolVersion || ready.Runtime != audit.Compatibility.Bridge.Runtime || ready.PythonVersion != audit.Compatibility.Bridge.PythonVersion {
		t.Fatalf("readiness drift: %#v", ready)
	}
	if len(ready.Capabilities) < audit.Compatibility.Bridge.FrozenCapabilities {
		t.Fatalf("capabilities=%d want >=%d", len(ready.Capabilities), audit.Compatibility.Bridge.FrozenCapabilities)
	}
}

func TestGO19AndroidRootLocalAndNDKAudit(t *testing.T) {
	root := repoRoot(t)
	var audit finalAudit
	if err := json.Unmarshal(read(t, root, "migration/audit/v1/final_audit.json"), &audit); err != nil {
		t.Fatal(err)
	}
	appBuild := string(read(t, root, "android/BKX.Android/app/build.gradle.kts"))
	gateway := string(read(t, root, "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt"))
	rootService := string(read(t, root, "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/root/BkxRootEngineService.kt"))
	graph := string(read(t, root, "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt"))
	if !reflect.DeepEqual(audit.Android.ABI, []string{"arm64-v8a"}) || audit.Android.NDKMajorFloor < 29 {
		t.Fatalf("invalid Android audit policy: %#v", audit.Android)
	}
	mustContain(t, appBuild, "ndkVersion = \""+audit.Android.NDK+"\"", "abiFilters += listOf(\"arm64-v8a\")")
	if strings.Contains(appBuild, "x86_64") {
		t.Fatal("Android production ABI unexpectedly includes x86_64")
	}
	mustContain(t, gateway, "const val ABI = \"arm64-v8a\"", "LOCAL_STATE_NAMESPACE = \""+audit.Android.LocalStateNamespace+"\"")
	mustContain(t, rootService, "GoNativeGateway(baseContext, stateNamespace = ROOT_STATE_NAMESPACE)", "ROOT_STATE_NAMESPACE = \""+audit.Android.RootStateNamespace+"\"")
	mustContain(t, graph, "GoNativeGateway(appContext)", "RootAwareGateway")
}

func TestGO19RunHistoryCompatibilityEvidenceIsStillActive(t *testing.T) {
	root := repoRoot(t)
	testText := string(read(t, root, "internal/runstore/store_test.go"))
	mustContain(t, testText, "TestPythonStyleFixtureRoundTrip", `"run_id":"py-1"`, "RecordStart(\"go-1\"")
}
