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
	StateSchemaVersion       = 1
)

type Resolution struct {
	Root   string
	Source string
	Shared bool
}

type Marker struct {
	SchemaVersion int    `json:"schema_version"`
	Kind          string `json:"kind"`
	CreatedAt     string `json:"created_at,omitempty"`
	Creator       string `json:"creator,omitempty"`
}

func LegacyRoot(home string) string {
	return filepath.Join(filepath.Clean(home), ".local", "share", "bkx")
}

func Resolve(home string) Resolution {
	if explicit := strings.TrimSpace(os.Getenv(EnvStateDir)); explicit != "" {
		return Resolution{Root: filepath.Clean(explicit), Source: "environment", Shared: !samePath(filepath.Clean(explicit), LegacyRoot(home))}
	}
	// Termux and the Android app cannot see one another's private HOME trees.
	// Once the Android app has initialized the non-secret shared control-plane
	// marker, the real Termux home automatically converges on the same state.
	sharedRoot := AndroidSharedRoot()
	if IsCanonicalTermuxHome(home) && IsSharedAndroidStateInitialized() {
		return Resolution{Root: sharedRoot, Source: "android-shared-marker", Shared: true}
	}
	return Resolution{Root: LegacyRoot(home), Source: "home", Shared: false}
}

func JobStorePath(home string) string {
	return filepath.Join(Resolve(home).Root, "jobs", "jobs.json")
}

func RepositoryStorePath(home string) string {
	return filepath.Join(Resolve(home).Root, "repositories", "repositories.json")
}

func LegacyJobStorePath(home string) string {
	return filepath.Join(LegacyRoot(home), "jobs", "jobs.json")
}

func LegacyRepositoryStorePath(home string) string {
	return filepath.Join(LegacyRoot(home), "repositories", "repositories.json")
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
	return err == nil && marker.SchemaVersion == StateSchemaVersion && marker.Kind == "bkx-shared-control-plane"
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
		if existing.SchemaVersion != StateSchemaVersion || existing.Kind != "bkx-shared-control-plane" {
			return fmt.Errorf("unsupported BKX state marker at %s", markerPath)
		}
		return nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	marker := Marker{
		SchemaVersion: StateSchemaVersion,
		Kind:          "bkx-shared-control-plane",
		CreatedAt:     time.Now().UTC().Format(time.RFC3339Nano),
		Creator:       strings.TrimSpace(creator),
	}
	data, err := json.MarshalIndent(marker, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	return atomicWrite(markerPath, data, 0o600)
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
	return atomicWrite(canonical, data, 0o600)
}

func samePath(a, b string) bool {
	return filepath.Clean(a) == filepath.Clean(b)
}

func atomicWrite(path string, data []byte, mode os.FileMode) error {
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
	return nil
}
