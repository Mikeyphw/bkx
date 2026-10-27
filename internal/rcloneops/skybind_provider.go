package rcloneops

// API-03 deliberately does not change the Android app's bundled runtime,
// app-private rclone.conf or the R1 unprivileged read-only socket. This opt-in
// provider is for an independently authorized root BKX CLI process only.
import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

const (
	skybindModuleRoot = "/data/adb/modules/skybind"
	skybindConfigRoot = "/data/adb/skybind/config"
)

// Production always enforces uid 0. The owner-injected variant is internal to
// this package and is used by unprivileged unit tests with synthetic files.
func skybindFile(path string, executable bool, private bool) error {
	return skybindFileOwnedBy(path, executable, private, 0)
}

func skybindFileOwnedBy(path string, executable bool, private bool, trustedOwner uint32) error {
	st, err := os.Lstat(path)
	if err != nil || !st.Mode().IsRegular() || st.Mode()&os.ModeSymlink != 0 {
		return errors.New("missing or non-regular Skybind component")
	}
	raw, ok := st.Sys().(*syscall.Stat_t)
	if !ok || raw.Uid != trustedOwner || st.Mode().Perm()&0o022 != 0 {
		return errors.New("untrusted Skybind component owner/mode")
	}
	if executable && st.Mode().Perm()&0o100 == 0 {
		return errors.New("Skybind executable is not executable")
	}
	if private && (st.Mode().Perm()&0o077 != 0 || st.Size() == 0) {
		return errors.New("Skybind config is not private")
	}
	return nil
}

func skybindDir(path string, private bool) error {
	return skybindDirOwnedBy(path, private, 0)
}

func skybindDirOwnedBy(path string, private bool, trustedOwner uint32) error {
	st, err := os.Lstat(path)
	if err != nil || !st.IsDir() || st.Mode()&os.ModeSymlink != 0 || st.Mode().Perm()&0o022 != 0 {
		return errors.New("untrusted Skybind directory")
	}
	raw, ok := st.Sys().(*syscall.Stat_t)
	if !ok || raw.Uid != trustedOwner || (private && st.Mode().Perm()&0o077 != 0) {
		return errors.New("untrusted Skybind directory owner/mode")
	}
	return nil
}

// Module filenames alone do not establish the installed module identity.
// Parse only the bounded, literal module.prop id field; no shell evaluation.
func skybindModuleIdentity(path string) error {
	return skybindModuleIdentityOwnedBy(path, 0)
}

func skybindModuleIdentityOwnedBy(path string, trustedOwner uint32) error {
	if err := skybindFileOwnedBy(path, false, false, trustedOwner); err != nil {
		return errors.New("Skybind module identity unavailable")
	}
	data, err := os.ReadFile(path)
	if err != nil || len(data) == 0 || len(data) > 65536 {
		return errors.New("Skybind module identity unreadable")
	}
	seen := false
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if ok && strings.TrimSpace(key) == "id" {
			if seen || strings.TrimSpace(value) != "skybind" {
				return errors.New("Skybind module identity mismatch")
			}
			seen = true
		}
	}
	if !seen {
		return errors.New("Skybind module identity missing")
	}
	return nil
}

// The injected roots are only for package tests. Production always uses the
// literal root-owned module/config paths; none is selected through PATH.
func resolveSkybindProvider(uid int, moduleRoot, configRoot string) (Runtime, error) {
	return resolveSkybindProviderWithOwner(uid, moduleRoot, configRoot, 0)
}

// resolveSkybindProviderWithOwner is intentionally unexported. Only synthetic
// package fixtures use a nonzero trustedOwner; RuntimeFromEnviron always calls
// the uid-0 production resolver with fixed Skybind paths.
func resolveSkybindProviderWithOwner(uid int, moduleRoot, configRoot string, trustedOwner uint32) (Runtime, error) {
	if uid != 0 {
		return Runtime{}, errors.New("Skybind provider requires root; BKX app remains isolated")
	}
	for _, dir := range []string{moduleRoot, filepath.Join(moduleRoot, "system"), filepath.Join(moduleRoot, "system", "bin")} {
		if err := skybindDirOwnedBy(dir, false, trustedOwner); err != nil {
			return Runtime{}, err
		}
	}
	if err := skybindDirOwnedBy(filepath.Dir(configRoot), true, trustedOwner); err != nil {
		return Runtime{}, err
	}
	if err := skybindDirOwnedBy(configRoot, true, trustedOwner); err != nil {
		return Runtime{}, err
	}
	// Module managers retain files for disabled/pending-removal modules. A
	// direct module path can still execute in that state, but it must not be
	// mistaken for the active Skybind provider in a trusted BKX session.
	if err := skybindModuleIdentityOwnedBy(filepath.Join(moduleRoot, "module.prop"), trustedOwner); err != nil {
		return Runtime{}, errors.New("Skybind module identity unavailable")
	}
	for _, marker := range []string{"disable", "remove"} {
		path := filepath.Join(moduleRoot, marker)
		_, err := os.Lstat(path)
		if err == nil {
			return Runtime{}, errors.New("Skybind module is disabled or pending removal")
		}
		if !errors.Is(err, os.ErrNotExist) {
			return Runtime{}, errors.New("Skybind module activation cannot be verified")
		}
	}
	launcher := filepath.Join(moduleRoot, "system", "bin", "rclone")
	native := filepath.Join(moduleRoot, "system", "bin", "skybind-rclone-engine")
	alias := filepath.Join(moduleRoot, "system", "bin", "skybind-rclone")
	for _, path := range []string{launcher, native, alias} {
		if err := skybindFileOwnedBy(path, true, false, trustedOwner); err != nil {
			return Runtime{}, err
		}
	}
	cfg := filepath.Join(configRoot, "rclone.conf")
	if err := skybindFileOwnedBy(cfg, false, true, trustedOwner); err != nil {
		return Runtime{}, err
	}
	return Runtime{Executable: launcher, Config: cfg}, nil
}

func skybindRuntimeFromEnviron(environ []string) (Runtime, bool, error) {
	lookup := func(key string) string {
		prefix := key + "="
		for i := len(environ) - 1; i >= 0; i-- {
			if strings.HasPrefix(environ[i], prefix) {
				return strings.TrimSpace(strings.TrimPrefix(environ[i], prefix))
			}
		}
		return ""
	}
	provider := lookup("BKX_RCLONE_PROVIDER")
	if provider == "" || provider == "bundled" {
		return Runtime{}, false, nil
	}
	if provider != "skybind" {
		return Runtime{}, true, fmt.Errorf("unknown BKX rclone provider")
	}
	if lookup("BKX_RCLONE_PATH") != "" || lookup("BKX_RCLONE_CONFIG") != "" {
		return Runtime{}, true, errors.New("Skybind provider cannot be mixed with BKX private rclone overrides")
	}
	runtime, err := resolveSkybindProvider(os.Geteuid(), skybindModuleRoot, skybindConfigRoot)
	return runtime, true, err
}
