package contracts

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
)

const (
	ContractSchemaVersion      = 1
	ContractVersion            = 1
	ExpectedCommandNodeCount   = 23
	ExpectedLeafCommandCount   = 16
	ExpectedHandlerCount       = 16
	ExpectedAdvertisedOps      = 15
	ExpectedCapabilityCount    = 18
	ExpectedBridgeProtocol     = 1
	ExpectedPythonVersion      = "1.0.0"
	ExpectedProductionRuntime  = "embedded-cpython"
	ExpectedProductionLanguage = "python"
)

var criticalOperations = []string{
	"backup.plan", "backup.create", "archive.list", "archive.info", "archive.browse", "archive.verify",
	"restore.preview", "restore.extract", "transfer.plan", "transfer.run", "runs.list", "runs.show",
	"run.cancel", "storage.locations", "filesystem.list", "filesystem.stat",
}

type ManifestEntry struct {
	Path   string `json:"path"`
	SHA256 string `json:"sha256"`
	Size   int    `json:"size"`
}

type Manifest struct {
	ContractSchemaVersion int             `json:"contractSchemaVersion"`
	ContractVersion       int             `json:"contractVersion"`
	Product               string          `json:"product"`
	PythonBackendVersion  string          `json:"pythonBackendVersion"`
	Files                 []ManifestEntry `json:"files"`
}

type CLIContract struct {
	ContractSchemaVersion int    `json:"contractSchemaVersion"`
	ContractVersion       int    `json:"contractVersion"`
	Program               string `json:"program"`
	PythonBackendVersion  string `json:"pythonBackendVersion"`
	CommandNodeCount      int    `json:"commandNodeCount"`
	LeafCommandCount      int    `json:"leafCommandCount"`
	CommandNodes          []struct {
		Path    []string `json:"path"`
		Command string   `json:"command"`
		IsGroup bool     `json:"isGroup"`
	} `json:"commandNodes"`
}

type BridgeContract struct {
	ContractSchemaVersion    int      `json:"contractSchemaVersion"`
	ContractVersion          int      `json:"contractVersion"`
	ProtocolVersion          int      `json:"protocolVersion"`
	Runtime                  string   `json:"runtime"`
	HandlerCount             int      `json:"handlerCount"`
	Handlers                 []string `json:"handlers"`
	AdvertisedOperationCount int      `json:"advertisedOperationCount"`
	AdvertisedOperations     []string `json:"advertisedOperations"`
	CapabilityCount          int      `json:"capabilityCount"`
	Capabilities             []string `json:"capabilities"`
	ArbitraryShell           bool     `json:"arbitraryShell"`
}

type CoreContract struct {
	ContractSchemaVersion int `json:"contractSchemaVersion"`
	ContractVersion       int `json:"contractVersion"`
	Errors                struct {
		StableCodes map[string]int `json:"stableCodes"`
	} `json:"errors"`
	RunHistory struct {
		ValidStatuses   []string `json:"validStatuses"`
		ValidOperations []string `json:"validOperations"`
	} `json:"runHistory"`
	SpecSchemas []string `json:"specSchemas"`
}

type ReleaseContract struct {
	ContractSchemaVersion int    `json:"contractSchemaVersion"`
	ContractVersion       int    `json:"contractVersion"`
	ProductVersion        string `json:"productVersion"`
	ProductionBackend     struct {
		Language string `json:"language"`
		Runtime  string `json:"runtime"`
	} `json:"productionBackend"`
	Android struct {
		ApplicationID         string `json:"applicationId"`
		BridgeProtocolVersion int    `json:"bridgeProtocolVersion"`
	} `json:"android"`
	CutoverRules map[string]bool `json:"cutoverRules"`
}

type Bundle struct {
	Manifest Manifest
	CLI      CLIContract
	Bridge   BridgeContract
	Core     CoreContract
	Release  ReleaseContract
}

func readJSON(path string, dst any) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("read %s: %w", path, err)
	}
	if err := json.Unmarshal(data, dst); err != nil {
		return fmt.Errorf("decode %s: %w", path, err)
	}
	return nil
}

func verifyManifest(dir string, manifest Manifest) error {
	for _, entry := range manifest.Files {
		path := filepath.Join(dir, filepath.FromSlash(entry.Path))
		data, err := os.ReadFile(path)
		if err != nil {
			return fmt.Errorf("manifest file %s: %w", entry.Path, err)
		}
		if len(data) != entry.Size {
			return fmt.Errorf("manifest size drift for %s: expected %d, got %d", entry.Path, entry.Size, len(data))
		}
		digest := sha256.Sum256(data)
		if hex.EncodeToString(digest[:]) != entry.SHA256 {
			return fmt.Errorf("manifest hash drift for %s", entry.Path)
		}
	}
	return nil
}

func LoadDir(dir string) (Bundle, error) {
	var bundle Bundle
	loads := []struct {
		name string
		dst  any
	}{
		{"manifest.json", &bundle.Manifest},
		{"cli.json", &bundle.CLI},
		{"bridge.json", &bundle.Bridge},
		{"core.json", &bundle.Core},
		{"release.json", &bundle.Release},
	}
	for _, item := range loads {
		if err := readJSON(filepath.Join(dir, item.name), item.dst); err != nil {
			return Bundle{}, err
		}
	}
	if err := verifyManifest(dir, bundle.Manifest); err != nil {
		return Bundle{}, err
	}
	return bundle, nil
}

func exactSet(got, want []string) bool {
	got = append([]string(nil), got...)
	want = append([]string(nil), want...)
	sort.Strings(got)
	sort.Strings(want)
	if len(got) != len(want) {
		return false
	}
	for i := range got {
		if got[i] != want[i] {
			return false
		}
	}
	return true
}

func (b Bundle) Validate() error {
	versions := []int{b.Manifest.ContractSchemaVersion, b.CLI.ContractSchemaVersion, b.Bridge.ContractSchemaVersion, b.Core.ContractSchemaVersion, b.Release.ContractSchemaVersion}
	for _, version := range versions {
		if version != ContractSchemaVersion {
			return fmt.Errorf("unsupported contract schema version %d", version)
		}
	}
	contractVersions := []int{b.Manifest.ContractVersion, b.CLI.ContractVersion, b.Bridge.ContractVersion, b.Core.ContractVersion, b.Release.ContractVersion}
	for _, version := range contractVersions {
		if version != ContractVersion {
			return fmt.Errorf("unsupported contract version %d", version)
		}
	}
	if b.Manifest.Product != "BKX" || b.Manifest.PythonBackendVersion != ExpectedPythonVersion {
		return fmt.Errorf("unexpected GO00 identity: product=%q python=%q", b.Manifest.Product, b.Manifest.PythonBackendVersion)
	}
	if b.CLI.Program != "bkx" || b.CLI.PythonBackendVersion != ExpectedPythonVersion {
		return fmt.Errorf("unexpected CLI identity")
	}
	if b.CLI.CommandNodeCount != ExpectedCommandNodeCount || len(b.CLI.CommandNodes) != ExpectedCommandNodeCount {
		return fmt.Errorf("CLI command-node drift: declared=%d decoded=%d expected=%d", b.CLI.CommandNodeCount, len(b.CLI.CommandNodes), ExpectedCommandNodeCount)
	}
	if b.CLI.LeafCommandCount != ExpectedLeafCommandCount {
		return fmt.Errorf("CLI leaf-command drift: got=%d expected=%d", b.CLI.LeafCommandCount, ExpectedLeafCommandCount)
	}
	if b.Bridge.ProtocolVersion != ExpectedBridgeProtocol || b.Bridge.Runtime != ExpectedProductionRuntime {
		return fmt.Errorf("bridge identity drift: protocol=%d runtime=%q", b.Bridge.ProtocolVersion, b.Bridge.Runtime)
	}
	if b.Bridge.HandlerCount != ExpectedHandlerCount || len(b.Bridge.Handlers) != ExpectedHandlerCount {
		return fmt.Errorf("bridge handler drift")
	}
	if !exactSet(b.Bridge.Handlers, criticalOperations) {
		return fmt.Errorf("bridge critical-operation set drift")
	}
	if b.Bridge.AdvertisedOperationCount != ExpectedAdvertisedOps || len(b.Bridge.AdvertisedOperations) != ExpectedAdvertisedOps {
		return fmt.Errorf("advertised operation drift")
	}
	if b.Bridge.CapabilityCount != ExpectedCapabilityCount || len(b.Bridge.Capabilities) != ExpectedCapabilityCount {
		return fmt.Errorf("capability drift")
	}
	if b.Bridge.ArbitraryShell {
		return fmt.Errorf("arbitrary shell execution must remain outside protocol-v1")
	}
	if b.Core.Errors.StableCodes["success"] != 0 || b.Core.Errors.StableCodes["external"] != 5 {
		return fmt.Errorf("public exit-code contract drift")
	}
	if len(b.Core.SpecSchemas) != 4 {
		return fmt.Errorf("expected 4 canonical spec schemas, got %d", len(b.Core.SpecSchemas))
	}
	if b.Release.ProductVersion != ExpectedPythonVersion || b.Release.ProductionBackend.Language != ExpectedProductionLanguage || b.Release.ProductionBackend.Runtime != ExpectedProductionRuntime {
		return fmt.Errorf("production backend identity drift")
	}
	if b.Release.Android.BridgeProtocolVersion != ExpectedBridgeProtocol || b.Release.Android.ApplicationID != "com.mikeyphw.bkx.android" {
		return fmt.Errorf("Android release identity drift")
	}
	for _, key := range []string{"pythonRemainsProductionUntilParity", "androidUiRemainsKotlinCompose", "protocolV1RemainsStable", "noFeatureRedesignDuringBackendMigration"} {
		if !b.Release.CutoverRules[key] {
			return fmt.Errorf("cutover rule %q is not sealed", key)
		}
	}
	return nil
}
