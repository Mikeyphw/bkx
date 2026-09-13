package validation

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"bkx/internal/berror"
	"bkx/internal/models"
)

var windowsDrive = regexp.MustCompile(`^[A-Za-z]:`)

var compressionLevels = map[string][2]int{
	"zstd": {1, 22}, "gzip": {1, 9}, "lz4": {0, 16}, "xz": {0, 9}, "bzip2": {1, 9}, "none": {0, 22},
}

func CompressionProfile(algorithm string, level int) (models.CompressionProfile, error) {
	name := strings.ToLower(strings.TrimSpace(algorithm))
	range_, ok := compressionLevels[name]
	if !ok {
		return models.CompressionProfile{}, berror.Validation("Unsupported compression algorithm", map[string]any{"algorithm": algorithm})
	}
	if level < range_[0] || level > range_[1] {
		return models.CompressionProfile{}, berror.Validation(fmt.Sprintf("Compression level for %s must be between %d and %d", name, range_[0], range_[1]), map[string]any{"algorithm": name, "level": level, "minimum": range_[0], "maximum": range_[1]})
	}
	return models.CompressionProfile{Algorithm: name, Level: level}, nil
}

func CanonicalLocalPath(path string) (string, error) {
	expanded, err := expandHome(path)
	if err != nil {
		return "", err
	}
	abs, err := filepath.Abs(expanded)
	if err != nil {
		return "", berror.Validation("Cannot normalize path: "+path, map[string]any{"path": path, "reason": err.Error()})
	}
	if resolved, err := filepath.EvalSymlinks(abs); err == nil {
		return resolved, nil
	}
	return filepath.Clean(abs), nil
}

func AbsoluteLocalPath(path string) (string, error) {
	expanded, err := expandHome(path)
	if err != nil {
		return "", err
	}
	if filepath.IsAbs(expanded) {
		return filepath.Clean(expanded), nil
	}
	return filepath.Abs(expanded)
}

func NormalizePatterns(patterns []string, fieldName string) ([]string, error) {
	out := make([]string, 0, len(patterns))
	seen := map[string]bool{}
	for _, value := range patterns {
		item := strings.TrimSpace(value)
		if item == "" {
			return nil, berror.Validation(fieldName+" entries must not be empty", map[string]any{"field": fieldName})
		}
		if strings.ContainsRune(item, '\x00') {
			return nil, berror.Validation(fieldName+" entries must not contain NUL", map[string]any{"field": fieldName})
		}
		if !seen[item] {
			out = append(out, item)
			seen[item] = true
		}
	}
	return out, nil
}

func ValidatePatternSets(includes, excludes []string) ([]string, []string, error) {
	inc, err := NormalizePatterns(includes, "includes")
	if err != nil {
		return nil, nil, err
	}
	exc, err := NormalizePatterns(excludes, "excludes")
	if err != nil {
		return nil, nil, err
	}
	set := map[string]bool{}
	for _, v := range inc {
		set[v] = true
	}
	var conflicts []string
	for _, v := range exc {
		if set[v] {
			conflicts = append(conflicts, v)
		}
	}
	if len(conflicts) > 0 {
		return nil, nil, berror.Validation("Include and exclude patterns conflict", map[string]any{"patterns": conflicts})
	}
	return inc, exc, nil
}

func NormalizeArchiveName(name string) (string, error) {
	value := strings.TrimSpace(name)
	const extension = ".bakery"
	if len(value) >= len(extension) && strings.EqualFold(value[len(value)-len(extension):], extension) {
		value = strings.TrimSpace(value[:len(value)-len(extension)])
	}
	if value == "" || value == "." || value == ".." || strings.ContainsRune(value, '\x00') {
		return "", berror.Validation("archive_name must be a non-empty file name", map[string]any{"archive_name": name})
	}
	if strings.ContainsAny(value, "/\\") || windowsDrive.MatchString(value) {
		return "", berror.Validation("archive_name must not contain path components", map[string]any{"archive_name": name})
	}
	return value, nil
}

func NormalizeArchiveMember(path string) (string, error) {
	raw := strings.ReplaceAll(strings.TrimSpace(path), "\\", "/")
	directory := strings.HasSuffix(raw, "/")
	if raw == "" || strings.ContainsRune(raw, '\x00') || strings.HasPrefix(raw, "/") || windowsDrive.MatchString(raw) {
		return "", berror.Validation("Archive member path must be relative and non-empty", map[string]any{"path": path})
	}
	for strings.HasPrefix(raw, "./") {
		raw = strings.TrimPrefix(raw, "./")
	}
	parts := strings.Split(raw, "/")
	cleaned := make([]string, 0, len(parts))
	for i, part := range parts {
		if part == "" && directory && i == len(parts)-1 {
			continue
		}
		if part == "" || part == "." || part == ".." {
			return "", berror.Validation("Archive member path contains traversal or empty components", map[string]any{"path": path})
		}
		cleaned = append(cleaned, part)
	}
	if len(cleaned) == 0 {
		return "", berror.Validation("Archive member path must be relative and non-empty", map[string]any{"path": path})
	}
	normalized := strings.Join(cleaned, "/")
	if directory {
		normalized += "/"
	}
	return normalized, nil
}

func ContainedPath(root, member string) (string, error) {
	base, err := CanonicalLocalPath(root)
	if err != nil {
		return "", err
	}
	normalized, err := NormalizeArchiveMember(member)
	if err != nil {
		return "", err
	}
	child, err := CanonicalLocalPath(filepath.Join(base, filepath.FromSlash(strings.TrimSuffix(normalized, "/"))))
	if err != nil {
		return "", err
	}
	rel, err := filepath.Rel(base, child)
	if err != nil {
		return "", err
	}
	if rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) {
		return "", berror.Validation("Path escapes destination root", map[string]any{"root": base, "path": member})
	}
	return child, nil
}

func PathsOverlap(first, second string) (bool, error) {
	a, err := CanonicalLocalPath(first)
	if err != nil {
		return false, err
	}
	b, err := CanonicalLocalPath(second)
	if err != nil {
		return false, err
	}
	ar, err := filepath.Rel(a, b)
	if err == nil && (ar == "." || (ar != ".." && !strings.HasPrefix(ar, ".."+string(os.PathSeparator)))) {
		return true, nil
	}
	br, err := filepath.Rel(b, a)
	return err == nil && (br == "." || (br != ".." && !strings.HasPrefix(br, ".."+string(os.PathSeparator)))), nil
}

func ValidateSplitSize(value *int64) (*int64, error) {
	if value == nil {
		return nil, nil
	}
	if *value <= 0 {
		return nil, berror.Validation("split_size must be a positive integer", map[string]any{"split_size": *value})
	}
	v := *value
	return &v, nil
}

func IsRemoteLocation(value string) bool {
	if !strings.Contains(value, "://") || IsContentURI(value) {
		return false
	}
	parsed, err := url.Parse(value)
	return err == nil && parsed.Scheme != "" && (parsed.Host != "" || parsed.Path != "")
}

func NormalizeTransferLocation(value, fieldName string) (string, error) {
	raw := strings.TrimSpace(value)
	if raw == "" || strings.ContainsRune(raw, '\x00') {
		return "", berror.Validation(fieldName+" must not be empty", map[string]any{"field": fieldName})
	}
	if IsContentURI(raw) {
		return "", berror.Validation(fieldName+" must be a staged filesystem path, not a content URI", map[string]any{"field": fieldName, "value": value})
	}
	if IsRemoteLocation(raw) {
		parsed, err := url.Parse(raw)
		if err != nil || parsed.Scheme == "" {
			return "", berror.Validation(fieldName+" has an invalid URI", map[string]any{"field": fieldName, "value": value})
		}
		return raw, nil
	}
	return AbsoluteLocalPath(raw)
}

func DeterministicName(prefix string, semanticParts ...string) string {
	var b strings.Builder
	for _, r := range prefix {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '-' || r == '_' {
			b.WriteRune(r)
		} else {
			b.WriteRune('_')
		}
	}
	safe := strings.Trim(b.String(), "_")
	if safe == "" {
		safe = "archive"
	}
	sum := sha256.Sum256([]byte(strings.Join(semanticParts, "\x00")))
	return safe + "_" + hex.EncodeToString(sum[:])[:12]
}

func expandHome(path string) (string, error) {
	if path == "~" || strings.HasPrefix(path, "~/") {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		if path == "~" {
			return home, nil
		}
		return filepath.Join(home, path[2:]), nil
	}
	return path, nil
}

func ValidateBackupSpec(spec models.BackupSpec) (models.BackupSpec, error) {
	if len(spec.Sources) == 0 {
		return models.BackupSpec{}, berror.Validation("At least one source path is required", nil)
	}
	for _, source := range spec.Sources {
		trimmed := strings.TrimSpace(source)
		if trimmed == "" {
			return models.BackupSpec{}, berror.Validation("Backup sources must be non-empty paths", nil)
		}
		if _, err := RequireSafeLocalReadPath(trimmed); err != nil {
			return models.BackupSpec{}, err
		}
	}
	if spec.ArchiveName != nil {
		name, err := NormalizeArchiveName(*spec.ArchiveName)
		if err != nil {
			return models.BackupSpec{}, err
		}
		spec.ArchiveName = &name
	}
	includes, excludes, err := ValidatePatternSets(spec.Includes, spec.Excludes)
	if err != nil {
		return models.BackupSpec{}, err
	}
	spec.Includes, spec.Excludes = includes, excludes
	if _, err := ValidateSplitSize(spec.SplitSize); err != nil {
		return models.BackupSpec{}, err
	}
	profile, err := CompressionProfile(spec.Compression.Algorithm, spec.Compression.Level)
	if err != nil {
		return models.BackupSpec{}, err
	}
	spec.Compression = profile
	return spec, nil
}

func ValidateArchiveCreateSpec(spec models.ArchiveCreateSpec) (models.ArchiveCreateSpec, error) {
	if len(spec.Sources) == 0 {
		return models.ArchiveCreateSpec{}, berror.Validation("Archive sources must be a non-empty tuple of Path objects", nil)
	}
	if strings.TrimSpace(spec.OutputPath) == "" {
		return models.ArchiveCreateSpec{}, berror.Validation("Archive output_path must be a Path or None", nil)
	}
	includes, excludes, err := ValidatePatternSets(spec.Includes, spec.Excludes)
	if err != nil {
		return models.ArchiveCreateSpec{}, err
	}
	spec.Includes, spec.Excludes = includes, excludes
	if _, err := ValidateSplitSize(spec.SplitSize); err != nil {
		return models.ArchiveCreateSpec{}, err
	}
	profile, err := CompressionProfile(spec.Compression.Algorithm, spec.Compression.Level)
	if err != nil {
		return models.ArchiveCreateSpec{}, err
	}
	spec.Compression = profile
	return spec, nil
}

func ValidateRestoreSpec(spec models.RestoreSpec) (models.RestoreSpec, error) {
	archive := strings.TrimSpace(spec.Archive)
	if archive == "" {
		return models.RestoreSpec{}, berror.Validation("Restore archive must be a Path", nil)
	}
	if IsContentURI(archive) {
		return models.RestoreSpec{}, berror.Validation("Restore archive must be a staged filesystem path, not a content URI", map[string]any{"archive": archive})
	}
	if !IsRemoteLocation(archive) {
		clean, err := RequireSafeLocalReadPath(archive)
		if err != nil {
			return models.RestoreSpec{}, err
		}
		spec.Archive = clean
	}
	if spec.Destination != nil {
		destination := strings.TrimSpace(*spec.Destination)
		if destination == "" {
			return models.RestoreSpec{}, berror.Validation("Restore destination must not be blank when provided", nil)
		}
		if IsContentURI(destination) {
			return models.RestoreSpec{}, berror.Validation("Restore destination must be a filesystem path, not a content URI", map[string]any{"destination": destination})
		}
		if !IsRemoteLocation(destination) {
			clean, err := RequireSafeLocalWriteDestination(destination)
			if err != nil {
				return models.RestoreSpec{}, err
			}
			spec.Destination = &clean
		}
	}
	seen := map[string]bool{}
	normalized := make([]string, 0, len(spec.Paths))
	for _, path := range spec.Paths {
		item, err := NormalizeArchiveMember(path)
		if err != nil {
			return models.RestoreSpec{}, err
		}
		if seen[item] {
			return models.RestoreSpec{}, berror.Validation("Duplicate restore path selectors are not allowed", nil)
		}
		seen[item] = true
		normalized = append(normalized, item)
	}
	spec.Paths = normalized
	if spec.Overwrite && spec.ConfirmOverwrite {
		return models.RestoreSpec{}, berror.Validation("Cannot use overwrite while confirmation is required", map[string]any{"overwrite": true, "confirm_overwrite": true})
	}
	return spec, nil
}

func ValidateTransferSpec(spec models.TransferSpec) (models.TransferSpec, error) {
	if len(spec.Sources) == 0 {
		return models.TransferSpec{}, berror.Validation("Transfer sources must be a non-empty tuple", nil)
	}
	if strings.TrimSpace(spec.Destination) == "" {
		return models.TransferSpec{}, berror.Validation("Transfer destination must be a Path or URI string", nil)
	}
	if spec.Direction != "push" && spec.Direction != "pull" && spec.Direction != "sync" {
		return models.TransferSpec{}, berror.Validation("Invalid transfer direction", map[string]any{"direction": spec.Direction, "valid": []string{"push", "pull", "sync"}})
	}
	if spec.Direction == "sync" && len(spec.Sources) != 1 {
		return models.TransferSpec{}, berror.Validation("Sync requires exactly one source", map[string]any{"source_count": len(spec.Sources)})
	}
	if spec.Direction == "sync" && !spec.DeleteExtraneousOnSync {
		return models.TransferSpec{}, berror.Validation("Sync is a mirror operation and requires explicit approval to delete destination-only files", map[string]any{"delete_extraneous_on_sync": false})
	}
	for _, source := range spec.Sources {
		trimmed := strings.TrimSpace(source)
		if trimmed == "" {
			return models.TransferSpec{}, berror.Validation("Transfer source entries must be Path or URI strings", nil)
		}
		if IsContentURI(trimmed) {
			return models.TransferSpec{}, berror.Validation("Transfer sources must be staged filesystem paths, not content URIs", map[string]any{"source": trimmed})
		}
		if spec.Direction == "push" && IsRemoteLocation(trimmed) {
			return models.TransferSpec{}, berror.Validation("Push sources must be local paths", nil)
		}
		if !IsRemoteLocation(trimmed) {
			clean, err := RequireSafeLocalReadPath(trimmed)
			if err != nil {
				return models.TransferSpec{}, err
			}
			_ = clean
		}
	}
	destination := strings.TrimSpace(spec.Destination)
	if IsContentURI(destination) {
		return models.TransferSpec{}, berror.Validation("Transfer destination must be a staged filesystem path or supported remote URI, not a content URI", map[string]any{"destination": destination})
	}
	if spec.Direction == "pull" && IsRemoteLocation(destination) {
		return models.TransferSpec{}, berror.Validation("Pull destination must be local", nil)
	}
	if !IsRemoteLocation(destination) {
		if _, err := RequireSafeLocalWriteDestination(destination); err != nil {
			return models.TransferSpec{}, err
		}
	}
	if spec.Checksum != nil {
		checksum := strings.TrimSpace(*spec.Checksum)
		if checksum == "" {
			return models.TransferSpec{}, berror.Validation("checksum must be a non-empty string when provided", nil)
		}
		spec.Checksum = &checksum
	}
	return spec, nil
}
