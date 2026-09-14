package statepaths

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	EnvStateDir              = "BKX_STATE_DIR"
	EnvAndroidSharedStateDir = "BKX_ANDROID_SHARED_STATE_DIR"
	MarkerFilename           = ".bkx-state.json"
	SharedAndroidRoot        = "/storage/emulated/0/BKX/.state"
	StateSchemaVersion       = 2
	MinimumMarkerSchema      = 1
)

type Resolution struct {
	Root   string
	Source string
	Shared bool
	Reason string
}

type Marker struct {
	SchemaVersion int      `json:"schema_version"`
	Kind          string   `json:"kind"`
	CreatedAt     string   `json:"created_at,omitempty"`
	Creator       string   `json:"creator,omitempty"`
	RootID        string   `json:"root_id,omitempty"`
	Documents     []string `json:"documents,omitempty"`
}

func LegacyRoot(home string) string {
	return filepath.Join(filepath.Clean(home), ".local", "share", "bkx")
}

func Resolve(home string) Resolution {
	if explicit := strings.TrimSpace(os.Getenv(EnvStateDir)); explicit != "" {
		clean := filepath.Clean(explicit)
		return Resolution{Root: clean, Source: "environment", Shared: !samePath(clean, LegacyRoot(home)), Reason: "explicit-state-dir"}
	}
	// Termux and the Android app cannot see one another's private HOME trees.
	// Once the Android app has initialized the non-secret shared control-plane
	// marker, the real Termux home automatically converges on the same state.
	sharedRoot := AndroidSharedRoot()
	if IsCanonicalTermuxHome(home) && IsSharedAndroidStateInitialized() {
		return Resolution{Root: sharedRoot, Source: "android-shared-marker", Shared: true, Reason: "compatible-shared-marker"}
	}
	return Resolution{Root: LegacyRoot(home), Source: "home", Shared: false, Reason: "legacy-home-root"}
}

func JobStorePath(home string) string {
	return filepath.Join(Resolve(home).Root, "jobs", "jobs.json")
}

func RepositoryStorePath(home string) string {
	return filepath.Join(Resolve(home).Root, "repositories", "repositories.json")
}

func ExecutionControlPath(home string) string {
	return filepath.Join(Resolve(home).Root, "execution", "control.json")
}

func ScheduleStorePath(home string) string {
	return filepath.Join(Resolve(home).Root, "schedules", "schedules.json")
}

func RunHistoryPath(home string) string {
	return filepath.Join(Resolve(home).Root, "runs", "runs.jsonl")
}

func LegacyRunHistoryPath(home string) string {
	return filepath.Join(LegacyRoot(home), "runs", "runs.jsonl")
}

func LegacyJobStorePath(home string) string {
	return filepath.Join(LegacyRoot(home), "jobs", "jobs.json")
}

func LegacyRepositoryStorePath(home string) string {
	return filepath.Join(LegacyRoot(home), "repositories", "repositories.json")
}

func LegacyScheduleStorePath(home string) string {
	return filepath.Join(LegacyRoot(home), "schedules", "schedules.json")
}

func LegacyExecutionControlPath(home string) string {
	return filepath.Join(LegacyRoot(home), "execution", "control.json")
}

func IsCanonicalTermuxHome(home string) bool {
	clean := filepath.Clean(strings.TrimSpace(home))
	return clean == "/data/data/com.termux/files/home" || clean == "/data/user/0/com.termux/files/home"
}

func AndroidSharedRoot() string {
	if override := strings.TrimSpace(os.Getenv(EnvAndroidSharedStateDir)); override != "" {
		return filepath.Clean(override)
	}
	return SharedAndroidRoot
}

func IsSharedAndroidStateInitialized() bool {
	marker, err := ReadMarker(AndroidSharedRoot())
	return err == nil && ValidateMarker(marker) == nil
}

func EnsureMarker(root, creator string) error {
	root = filepath.Clean(strings.TrimSpace(root))
	if root == "." || root == string(filepath.Separator) || root == "" {
		return fmt.Errorf("invalid BKX state root")
	}
	if err := os.MkdirAll(root, 0o700); err != nil {
		return err
	}
	markerPath := filepath.Join(root, MarkerFilename)
	if existing, err := ReadMarker(root); err == nil {
		if err := ValidateMarker(existing); err != nil {
			if existing.SchemaVersion > StateSchemaVersion {
				return fmt.Errorf("unsupported future BKX state marker at %s", markerPath)
			}
			if err := quarantineInvalidMarker(markerPath); err != nil {
				return err
			}
		} else {
			return nil
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		if err := quarantineInvalidMarker(markerPath); err != nil {
			return err
		}
	}
	marker := Marker{
		SchemaVersion: StateSchemaVersion,
		Kind:          "bkx-shared-control-plane",
		CreatedAt:     time.Now().UTC().Format(time.RFC3339Nano),
		Creator:       strings.TrimSpace(creator),
		RootID:        StableRootID(root),
		Documents:     []string{"jobs/jobs.json", "repositories/repositories.json", "schedules/schedules.json", "execution/control.json", "runs/runs.jsonl"},
	}
	data, err := json.MarshalIndent(marker, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	return AtomicWriteFile(markerPath, data, 0o600)
}

func ReadMarker(root string) (Marker, error) {
	path := filepath.Join(filepath.Clean(root), MarkerFilename)
	data, err := os.ReadFile(path)
	if err != nil {
		return Marker{}, err
	}
	var marker Marker
	if err := json.Unmarshal(data, &marker); err != nil {
		return Marker{}, fmt.Errorf("invalid BKX state marker: %w", err)
	}
	return marker, nil
}

func ValidateMarker(marker Marker) error {
	if marker.Kind != "bkx-shared-control-plane" {
		return fmt.Errorf("unsupported BKX state marker kind %q", marker.Kind)
	}
	if marker.SchemaVersion < MinimumMarkerSchema || marker.SchemaVersion > StateSchemaVersion {
		return fmt.Errorf("unsupported BKX state marker schema_version %d", marker.SchemaVersion)
	}
	return nil
}

func StableRootID(root string) string {
	clean := filepath.Clean(strings.TrimSpace(root))
	return strings.NewReplacer(string(filepath.Separator), "-", ":", "-", " ", "-").Replace(clean)
}

func quarantineInvalidMarker(markerPath string) error {
	if _, err := os.Stat(markerPath); errors.Is(err, os.ErrNotExist) {
		return nil
	} else if err != nil {
		return err
	}
	name := markerPath + ".invalid-" + time.Now().UTC().Format("20060102T150405.000000000Z")
	return os.Rename(markerPath, name)
}

// SeedLegacyFile moves no data destructively. When the canonical shared store
// does not exist yet, it copies the prior HOME-scoped document atomically. If
// both documents exist, canonical shared state wins and the legacy file is left
// untouched for explicit import/export reconciliation rather than silently
// overwriting either side.
func SeedLegacyFile(canonical, legacy string) error {
	canonical = filepath.Clean(canonical)
	legacy = filepath.Clean(legacy)
	if samePath(canonical, legacy) {
		return nil
	}
	if _, err := os.Stat(canonical); err == nil {
		return nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	data, err := os.ReadFile(legacy)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	return AtomicWriteFile(canonical, data, 0o600)
}

func samePath(a, b string) bool {
	return filepath.Clean(a) == filepath.Clean(b)
}

func AtomicWriteFile(path string, data []byte, mode os.FileMode) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	f, err := os.CreateTemp(dir, "."+filepath.Base(path)+".*.tmp")
	if err != nil {
		return err
	}
	tmp := f.Name()
	cleanup := func() { _ = os.Remove(tmp) }
	if err := f.Chmod(mode); err != nil {
		_ = f.Close()
		cleanup()
		return err
	}
	if _, err := f.Write(data); err != nil {
		_ = f.Close()
		cleanup()
		return err
	}
	if err := f.Sync(); err != nil {
		_ = f.Close()
		cleanup()
		return err
	}
	if err := f.Close(); err != nil {
		cleanup()
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		cleanup()
		return err
	}
	if d, err := os.Open(dir); err == nil {
		_ = d.Sync()
		_ = d.Close()
	}
	return nil
}
