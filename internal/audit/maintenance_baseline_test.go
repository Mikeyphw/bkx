package audit

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"bkx/internal/bridge"
	"bkx/internal/publiccli"
	"bkx/internal/version"
)

type maintenanceBaseline struct {
	SchemaVersion      int    `json:"schema_version"`
	Product            string `json:"product"`
	ProductVersion     string `json:"product_version"`
	PostMigrationPhase string `json:"post_migration_phase"`
	CanonicalValidator string `json:"canonical_validator"`
	RetiredScaffolding []struct {
		Name   string `json:"name"`
		Status string `json:"status"`
	} `json:"retired_scaffolding"`
	RetainedEvidence       []string `json:"retained_evidence"`
	RetainedInternalRoutes []string `json:"retained_internal_routes"`
}

func TestGO20MaintenanceBaseline(t *testing.T) {
	root := repoRoot(t)
	var baseline maintenanceBaseline
	if err := json.Unmarshal(read(t, root, "migration/audit/v1/maintenance_baseline.json"), &baseline); err != nil {
		t.Fatal(err)
	}
	if baseline.SchemaVersion != 1 || baseline.Product != "BKX" || baseline.ProductVersion != version.Version || baseline.PostMigrationPhase != "GO20" || version.MigrationPhase != "GO20" {
		t.Fatalf("maintenance identity drift: %#v phase=%s", baseline, version.MigrationPhase)
	}
	if baseline.CanonicalValidator != "scripts/validate.sh" {
		t.Fatalf("unexpected validator: %s", baseline.CanonicalValidator)
	}
	if len(baseline.RetiredScaffolding) != 3 {
		t.Fatalf("retirement set=%#v", baseline.RetiredScaffolding)
	}
	for _, item := range baseline.RetiredScaffolding {
		if item.Status != "retired" {
			t.Fatalf("scaffolding not retired: %#v", item)
		}
	}
	for _, rel := range baseline.RetainedEvidence {
		if _, err := os.Stat(filepath.Join(root, filepath.FromSlash(rel))); err != nil {
			t.Fatalf("retained evidence missing %s: %v", rel, err)
		}
	}
	for _, rel := range []string{"scripts/validate-go17.sh", "scripts/validate-go18.sh", "scripts/validate-go19.sh"} {
		if _, err := os.Stat(filepath.Join(root, rel)); !os.IsNotExist(err) {
			t.Fatalf("retired validator still present: %s", rel)
		}
	}
	if publiccli.IsInternalInvocation([]string{"migration", "bridge", "readiness"}) || publiccli.IsInternalInvocation([]string{"version"}) {
		t.Fatal("retired migration/version route is still internally owned")
	}
	if !publiccli.IsInternalInvocation([]string{"contract", "check"}) || !publiccli.IsInternalInvocation([]string{"android-runtime", "bridge-session", "-"}) {
		t.Fatal("required maintenance/runtime internal route missing")
	}
	if bridge.NewAndroid(nil).Readiness().Runtime != bridge.AndroidRuntime {
		t.Fatal("Android bridge runtime identity drift")
	}

	mainText := string(read(t, root, "cmd/bkx/main.go"))
	bridgeText := string(read(t, root, "internal/bridge/bridge.go"))
	if strings.Contains(mainText, `args[0] == "migration"`) || strings.Contains(mainText, "Go migration scaffold") {
		t.Fatal("migration CLI scaffold remains in cmd/bkx")
	}
	if strings.Contains(bridgeText, "go-native-migration") || strings.Contains(bridgeText, "func NewWithRuntime") || strings.Contains(bridgeText, "func New(store") {
		t.Fatal("migration bridge constructor/identity remains")
	}
}
