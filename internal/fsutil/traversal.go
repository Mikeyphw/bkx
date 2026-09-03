package fsutil

import (
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"bkx/internal/berror"
	"bkx/internal/validation"
)

type SymlinkPolicy string

const (
	SymlinkReject   SymlinkPolicy = "reject"
	SymlinkSkip     SymlinkPolicy = "skip"
	SymlinkPreserve SymlinkPolicy = "preserve"
)

type TraversalEntry struct {
	SourceRoot  string `json:"source_root"`
	Path        string `json:"path"`
	ArchiveName string `json:"archive_name"`
	IsDir       bool   `json:"is_dir"`
	Size        int64  `json:"size"`
	MtimeNS     int64  `json:"mtime_ns"`
	IsSymlink   bool   `json:"is_symlink"`
}

type SourceManifest struct {
	Roots   []string         `json:"roots"`
	Entries []TraversalEntry `json:"entries"`
}

func (m SourceManifest) Files() []TraversalEntry {
	out := make([]TraversalEntry, 0)
	for _, entry := range m.Entries {
		if !entry.IsDir {
			out = append(out, entry)
		}
	}
	return out
}

func (m SourceManifest) Dirs() []TraversalEntry {
	out := make([]TraversalEntry, 0)
	for _, entry := range m.Entries {
		if entry.IsDir {
			out = append(out, entry)
		}
	}
	return out
}

func (m SourceManifest) TotalSize() int64 {
	var total int64
	for _, entry := range m.Entries {
		if !entry.IsDir {
			total += entry.Size
		}
	}
	return total
}

func BuildSourceManifest(sources, includes, excludes []string, outputPath string, policy SymlinkPolicy) (SourceManifest, error) {
	if policy != SymlinkReject && policy != SymlinkSkip && policy != SymlinkPreserve {
		return SourceManifest{}, berror.Validation("Unsupported symlink policy", map[string]any{"policy": string(policy)})
	}
	if len(sources) == 0 {
		return SourceManifest{}, berror.Validation("At least one source path is required", nil)
	}
	inc, exc, err := validation.ValidatePatternSets(includes, excludes)
	if err != nil {
		return SourceManifest{}, err
	}
	roots := make([]string, 0, len(sources))
	seenRoots := map[string]bool{}
	for _, source := range sources {
		root, err := validation.CanonicalLocalPath(source)
		if err != nil {
			return SourceManifest{}, err
		}
		if seenRoots[root] {
			return SourceManifest{}, berror.Validation("Duplicate source paths are not allowed", map[string]any{"sources": append([]string(nil), roots...)})
		}
		seenRoots[root] = true
		info, err := os.Lstat(root)
		if err != nil {
			if os.IsNotExist(err) {
				return SourceManifest{}, berror.NotFound("Source path does not exist: "+root, map[string]any{"path": root})
			}
			return SourceManifest{}, berror.WrapExternal("inspecting source path", root, "go-os", err)
		}
		effective := policy
		if policy == SymlinkSkip {
			effective = SymlinkReject
		}
		if err := validateSupported(root, info, effective); err != nil {
			return SourceManifest{}, err
		}
		roots = append(roots, root)
	}
	if outputPath != "" {
		target, err := validation.CanonicalLocalPath(outputPath)
		if err != nil {
			return SourceManifest{}, err
		}
		for _, root := range roots {
			info, statErr := os.Stat(root)
			sourceIsDir := statErr == nil && info.IsDir()
			if target == root || (sourceIsDir && isWithin(root, target)) {
				return SourceManifest{}, berror.Validation("Output/destination must not overlap a source tree", map[string]any{"source": root, "output": target})
			}
		}
	}

	entries := make([]TraversalEntry, 0)
	seenNames := map[string]string{}
	for _, root := range roots {
		info, _ := os.Lstat(root)
		if info.Mode().IsRegular() {
			member, err := validation.NormalizeArchiveMember(filepath.Base(root))
			if err != nil {
				return SourceManifest{}, err
			}
			member = strings.TrimSuffix(member, "/")
			if patternSelected(member, false, inc, exc) {
				ent, err := traversalEntry(root, root, member, policy, nil)
				if err != nil {
					return SourceManifest{}, err
				}
				if err := appendTraversal(&entries, seenNames, ent); err != nil {
					return SourceManifest{}, err
				}
			}
			continue
		}
		baseParent := filepath.Dir(root)
		err := filepath.WalkDir(root, func(path string, de os.DirEntry, walkErr error) error {
			if walkErr != nil {
				if os.IsPermission(walkErr) {
					return berror.Permission("Permission denied while traversing "+path, map[string]any{"path": path})
				}
				return berror.WrapExternal("traversing source tree", path, "go-os", walkErr)
			}
			info, err := os.Lstat(path)
			if err != nil {
				if os.IsNotExist(err) {
					return berror.NotFound("Source path disappeared during traversal: "+path, map[string]any{"path": path})
				}
				return berror.WrapExternal("inspecting source tree entry", path, "go-os", err)
			}
			isLink := info.Mode()&os.ModeSymlink != 0
			if isLink {
				if policy == SymlinkReject {
					return berror.Validation("Symlink sources are not supported by this operation", map[string]any{"path": path})
				}
				if policy == SymlinkSkip {
					if de.IsDir() {
						return filepath.SkipDir
					}
					return nil
				}
				// Preserve file symlinks as link metadata. Python os.walk with
				// followlinks=False does not emit a directory-symlink entry.
				if de.IsDir() {
					return filepath.SkipDir
				}
			} else if err := validateSupported(path, info, policy); err != nil {
				return err
			}
			rel, err := filepath.Rel(baseParent, path)
			if err != nil {
				return err
			}
			rel = filepath.ToSlash(rel)
			if rel == "." || rel == "" {
				return nil
			}
			member, err := validation.NormalizeArchiveMember(rel)
			if err != nil {
				return err
			}
			member = strings.TrimSuffix(member, "/")
			isDir := info.IsDir()
			if !patternSelected(member, isDir, inc, exc) {
				return nil
			}
			ent, err := traversalEntry(root, path, member, policy, &isDir)
			if err != nil {
				return err
			}
			return appendTraversal(&entries, seenNames, ent)
		})
		if err != nil {
			return SourceManifest{}, err
		}
	}
	return SourceManifest{Roots: roots, Entries: entries}, nil
}

func traversalEntry(root, path, archiveName string, policy SymlinkPolicy, isDirOverride *bool) (TraversalEntry, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return TraversalEntry{}, err
	}
	isDir := info.IsDir()
	if isDirOverride != nil {
		isDir = *isDirOverride
	}
	return TraversalEntry{SourceRoot: root, Path: path, ArchiveName: archiveName, IsDir: isDir, Size: func() int64 {
		if isDir {
			return 0
		}
		return info.Size()
	}(), MtimeNS: info.ModTime().UnixNano(), IsSymlink: info.Mode()&os.ModeSymlink != 0}, nil
}

func validateSupported(path string, info os.FileInfo, policy SymlinkPolicy) error {
	mode := info.Mode()
	if mode&os.ModeSymlink != 0 {
		if policy == SymlinkReject {
			return berror.Validation("Symlink sources are not supported by this operation", map[string]any{"path": path})
		}
		return nil
	}
	if mode.IsRegular() || mode.IsDir() {
		return nil
	}
	kind := "special"
	switch {
	case mode&os.ModeNamedPipe != 0:
		kind = "fifo"
	case mode&os.ModeSocket != 0:
		kind = "socket"
	case mode&os.ModeDevice != 0 || mode&os.ModeCharDevice != 0:
		kind = "device"
	}
	return berror.Validation("Unsupported special file in source tree", map[string]any{"path": path, "kind": kind})
}

func appendTraversal(entries *[]TraversalEntry, seen map[string]string, entry TraversalEntry) error {
	key := strings.TrimSuffix(entry.ArchiveName, "/")
	if entry.IsDir {
		key += "/"
	}
	if previous, ok := seen[key]; ok && previous != entry.Path {
		return berror.Validation("Archive member name collision", map[string]any{"archive_name": key, "first": previous, "second": entry.Path})
	}
	seen[key] = entry.Path
	*entries = append(*entries, entry)
	return nil
}

func isWithin(root, child string) bool {
	rel, err := filepath.Rel(root, child)
	if err != nil {
		return false
	}
	return rel != "." && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel)
}

func patternSelected(member string, isDir bool, includes, excludes []string) bool {
	target := strings.TrimSuffix(member, "/")
	if isDir {
		target += "/"
	}
	fileTarget := strings.TrimSuffix(target, "/")
	if len(includes) > 0 {
		matched := false
		for _, p := range includes {
			if fnmatch(fileTarget, p) || fnmatch(target, p) {
				matched = true
				break
			}
		}
		if !matched {
			return false
		}
	}
	for _, p := range excludes {
		if fnmatch(fileTarget, p) || fnmatch(target, p) {
			return false
		}
	}
	return true
}

// fnmatch intentionally treats '/' as an ordinary character, matching Python's
// fnmatch.fnmatch string semantics rather than filepath.Match path semantics.
func fnmatch(value, pattern string) bool {
	var b strings.Builder
	b.WriteString("^")
	for i := 0; i < len(pattern); i++ {
		c := pattern[i]
		switch c {
		case '*':
			b.WriteString(".*")
		case '?':
			b.WriteString(".")
		case '[':
			j := i + 1
			if j < len(pattern) && (pattern[j] == '!' || pattern[j] == '^') {
				j++
			}
			if j < len(pattern) && pattern[j] == ']' {
				j++
			}
			for j < len(pattern) && pattern[j] != ']' {
				j++
			}
			if j >= len(pattern) {
				b.WriteString("\\[")
				continue
			}
			content := pattern[i+1 : j]
			if strings.HasPrefix(content, "!") {
				content = "^" + content[1:]
			} else if strings.HasPrefix(content, "^") {
				content = "\\" + content
			}
			b.WriteString("[")
			b.WriteString(content)
			b.WriteString("]")
			i = j
		default:
			b.WriteString(regexp.QuoteMeta(string(c)))
		}
	}
	b.WriteString("$")
	re, err := regexp.Compile(b.String())
	return err == nil && re.MatchString(value)
}

func SortedFiles(m SourceManifest) []TraversalEntry {
	files := m.Files()
	sort.Slice(files, func(i, j int) bool { return files[i].ArchiveName < files[j].ArchiveName })
	return files
}
