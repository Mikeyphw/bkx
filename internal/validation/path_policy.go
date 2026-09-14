package validation

import (
	"os"
	"path/filepath"
	"strings"
	"syscall"

	"bkx/internal/berror"
)

const writeHeadroomBytes int64 = 32 * 1024 * 1024

type LocalPathPolicy struct {
	CleanPath        string
	RestrictedReason string
	RootSensitive    bool
	SharedRestricted bool
}

var protectedLocalWriteRoots = []string{
	"/",
	"/proc",
	"/sys",
	"/dev",
	"/system",
	"/vendor",
	"/product",
	"/apex",
	"/metadata",
	"/data/system",
	"/data/misc",
	"/data/vendor",
	"/data/property",
	"/data/adb",
	"/data/data",
	"/data/user",
	"/data/user_de",
}

var pseudoLocalReadRoots = []string{"/proc", "/sys", "/dev"}
var sharedStorageAliases = []string{"/sdcard", "/storage/self/primary", "/storage/emulated/0"}

func IsContentURI(value string) bool {
	return strings.HasPrefix(strings.ToLower(strings.TrimSpace(value)), "content://")
}

func InspectLocalPathPolicy(path string) (LocalPathPolicy, error) {
	clean, err := policyCleanPath(path)
	if err != nil {
		return LocalPathPolicy{}, err
	}
	policy := LocalPathPolicy{CleanPath: clean}
	ownedPrivateData := isOwnedAndroidPrivateDataPath(clean)
	if reason := restrictedSharedAndroidPath(clean); reason != "" {
		policy.RestrictedReason = reason
		policy.SharedRestricted = true
	}
	if policy.RestrictedReason == "" {
		for _, root := range protectedLocalWriteRoots {
			if ownedPrivateData && (root == "/data/data" || root == "/data/user" || root == "/data/user_de") {
				continue
			}
			if pathEqualOrUnder(clean, root) {
				policy.RestrictedReason = root + " is a protected Android or pseudo-filesystem destination"
				break
			}
		}
	}
	policy.RootSensitive = isRootSensitive(clean) && !ownedPrivateData
	return policy, nil
}

func RequireSafeLocalReadPath(path string) (string, error) {
	if IsContentURI(path) {
		return "", berror.Validation("content:// URIs must be staged before BKX receives filesystem paths", map[string]any{"path": path})
	}
	clean, err := CanonicalLocalPath(path)
	if err != nil {
		return "", err
	}
	for _, root := range pseudoLocalReadRoots {
		if pathEqualOrUnder(clean, root) {
			return "", berror.Validation("Refusing to read protected pseudo-filesystem path", map[string]any{"path": clean, "root": root})
		}
	}
	return clean, nil
}

func RequireSafeLocalWriteDestination(path string) (string, error) {
	policy, err := InspectLocalPathPolicy(path)
	if err != nil {
		return "", err
	}
	if policy.RestrictedReason != "" {
		return "", berror.Validation("Refusing protected local filesystem destination", map[string]any{"path": policy.CleanPath, "reason": policy.RestrictedReason})
	}
	return policy.CleanPath, nil
}

func EnsureCapacityForWriteDestination(path string, estimatedBytes int64) error {
	if estimatedBytes <= 0 || IsRemoteLocation(path) || IsContentURI(path) {
		return nil
	}
	clean, err := RequireSafeLocalWriteDestination(path)
	if err != nil {
		return err
	}
	dir := clean
	if filepath.Ext(clean) != "" {
		dir = filepath.Dir(clean)
	}
	dir = existingParent(dir)
	var stat syscall.Statfs_t
	if err := syscall.Statfs(dir, &stat); err != nil {
		return berror.WrapExternal("checking available storage", dir, "go-os", err)
	}
	available := int64(stat.Bavail) * int64(stat.Bsize)
	required := estimatedBytes + writeHeadroomBytes
	if available < required {
		return berror.Validation("Insufficient free space for local BKX output", map[string]any{"path": clean, "required_bytes": required, "available_bytes": available})
	}
	return nil
}

func policyCleanPath(path string) (string, error) {
	raw := strings.TrimSpace(path)
	if raw == "" || strings.ContainsRune(raw, '\x00') {
		return "", berror.Validation("Local path must be non-empty and must not contain NUL", map[string]any{"path": path})
	}
	if IsContentURI(raw) {
		return "", berror.Validation("content:// URIs must be staged before BKX receives filesystem paths", map[string]any{"path": path})
	}
	clean, err := AbsoluteLocalPath(raw)
	if err != nil {
		return "", err
	}
	return filepath.Clean(clean), nil
}

func androidPrivatePackageRoot(path string) string {
	clean := filepath.ToSlash(filepath.Clean(path))
	lower := strings.ToLower(clean)
	if strings.HasPrefix(lower, "/data/data/") {
		rest := strings.TrimPrefix(clean, "/data/data/")
		if pkg, _, ok := strings.Cut(rest, "/"); ok && pkg != "" {
			return "/data/data/" + pkg
		} else if rest != "" {
			return "/data/data/" + rest
		}
	}
	for _, base := range []string{"/data/user/", "/data/user_de/"} {
		if !strings.HasPrefix(lower, base) {
			continue
		}
		rest := strings.TrimPrefix(clean, base)
		parts := strings.SplitN(rest, "/", 3)
		if len(parts) >= 2 && parts[0] != "" && parts[1] != "" {
			return strings.TrimSuffix(base, "/") + "/" + parts[0] + "/" + parts[1]
		}
	}
	return ""
}

func isOwnedAndroidPrivateDataPath(path string) bool {
	root := androidPrivatePackageRoot(path)
	if root == "" {
		return false
	}
	info, err := os.Stat(root)
	if err != nil {
		return false
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	return ok && int(stat.Uid) == os.Geteuid()
}

func restrictedSharedAndroidPath(path string) string {
	lower := strings.ToLower(filepath.ToSlash(path))
	for _, alias := range sharedStorageAliases {
		if lower == alias {
			continue
		}
		prefix := alias + "/"
		if strings.HasPrefix(lower, prefix) {
			rest := strings.Trim(strings.TrimPrefix(lower, prefix), "/")
			if rest == "android/data" || strings.HasPrefix(rest, "android/data/") {
				return alias + "/Android/data is restricted by modern Android storage rules"
			}
			if rest == "android/obb" || strings.HasPrefix(rest, "android/obb/") {
				return alias + "/Android/obb is restricted by modern Android storage rules"
			}
		}
	}
	return ""
}

func isRootSensitive(path string) bool {
	lower := strings.ToLower(filepath.ToSlash(path))
	for _, root := range []string{"/data", "/system", "/vendor", "/product", "/metadata", "/mnt/media_rw"} {
		if pathEqualOrUnder(lower, root) {
			return true
		}
	}
	return false
}

func pathEqualOrUnder(path, root string) bool {
	cleanPath := strings.TrimRight(strings.ToLower(filepath.ToSlash(filepath.Clean(path))), "/")
	cleanRoot := strings.TrimRight(strings.ToLower(filepath.ToSlash(filepath.Clean(root))), "/")
	if cleanPath == "" {
		cleanPath = "/"
	}
	if cleanRoot == "" {
		cleanRoot = "/"
	}
	if cleanRoot == "/" {
		return cleanPath == "/"
	}
	return cleanPath == cleanRoot || strings.HasPrefix(cleanPath, cleanRoot+"/")
}

func existingParent(path string) string {
	candidate := filepath.Clean(path)
	for {
		if candidate == "." || candidate == string(os.PathSeparator) || candidate == "" {
			return string(os.PathSeparator)
		}
		if info, err := os.Stat(candidate); err == nil && info.IsDir() {
			return candidate
		}
		next := filepath.Dir(candidate)
		if next == candidate {
			return candidate
		}
		candidate = next
	}
}
