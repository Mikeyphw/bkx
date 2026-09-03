package fsutil

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"syscall"

	"bkx/internal/berror"
)

type Entry struct {
	Path                  string `json:"path"`
	Name                  string `json:"name"`
	Kind                  string `json:"kind"`
	SizeBytes             int64  `json:"size_bytes"`
	ModifiedAtEpochMillis int64  `json:"modified_at_epoch_millis"`
	Mode                  uint32 `json:"mode"`
	UID                   uint32 `json:"uid"`
	GID                   uint32 `json:"gid"`
}

type ListResult struct {
	Path       string  `json:"path"`
	TotalCount int     `json:"total_count"`
	Offset     int     `json:"offset"`
	Limit      int     `json:"limit"`
	More       bool    `json:"more"`
	Entries    []Entry `json:"entries"`
}

func ExpandUser(path string) (string, error) {
	if path == "~" || strings.HasPrefix(path, "~/") {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", berror.Validation("Cannot expand home directory", map[string]any{"path": path, "reason": err.Error()})
		}
		if path == "~" {
			return home, nil
		}
		return filepath.Join(home, strings.TrimPrefix(path, "~/")), nil
	}
	return path, nil
}

func Kind(mode os.FileMode) string {
	switch {
	case mode.IsDir():
		return "directory"
	case mode&os.ModeSymlink != 0:
		return "symlink"
	case mode.IsRegular():
		return "file"
	default:
		return "special"
	}
}

func Stat(path string) (Entry, error) {
	expanded, err := ExpandUser(path)
	if err != nil {
		return Entry{}, err
	}
	info, err := os.Lstat(expanded)
	if err != nil {
		if os.IsNotExist(err) {
			return Entry{}, berror.NotFound("Path not found: "+expanded, map[string]any{"path": expanded})
		}
		if os.IsPermission(err) {
			return Entry{}, berror.Permission("Permission denied while inspecting "+expanded, map[string]any{"path": expanded})
		}
		return Entry{}, berror.WrapExternal("inspecting filesystem path", expanded, "go-os", err)
	}
	return entryFromInfo(expanded, info), nil
}

func List(path string, showHidden bool, limit, offset int) (ListResult, error) {
	expanded, err := ExpandUser(path)
	if err != nil {
		return ListResult{}, err
	}
	if limit < 1 {
		limit = 1
	}
	if limit > 1000 {
		limit = 1000
	}
	if offset < 0 {
		offset = 0
	}
	info, err := os.Stat(expanded)
	if err != nil {
		if os.IsNotExist(err) {
			return ListResult{}, berror.NotFound("Path not found: "+expanded, map[string]any{"path": expanded})
		}
		if os.IsPermission(err) {
			return ListResult{}, berror.Permission("Permission denied while listing "+expanded, map[string]any{"path": expanded})
		}
		return ListResult{}, berror.WrapExternal("listing filesystem path", expanded, "go-os", err)
	}
	if !info.IsDir() {
		return ListResult{}, berror.Validation("Path is not a directory: "+expanded, map[string]any{"path": expanded})
	}
	dirEntries, err := os.ReadDir(expanded)
	if err != nil {
		if os.IsPermission(err) {
			return ListResult{}, berror.Permission("Permission denied while listing "+expanded, map[string]any{"path": expanded})
		}
		return ListResult{}, berror.WrapExternal("listing filesystem path", expanded, "go-os", err)
	}
	type candidate struct {
		name  string
		isDir bool
		entry Entry
	}
	candidates := make([]candidate, 0, len(dirEntries))
	for _, de := range dirEntries {
		if !showHidden && strings.HasPrefix(de.Name(), ".") {
			continue
		}
		child := filepath.Join(expanded, de.Name())
		// Python's Path.is_dir() follows links for sorting, while lstat is used
		// for the actual kind/metadata payload.
		isDir := false
		if followed, statErr := os.Stat(child); statErr == nil {
			isDir = followed.IsDir()
		}
		linfo, lerr := os.Lstat(child)
		if lerr != nil {
			candidates = append(candidates, candidate{name: de.Name(), isDir: isDir, entry: Entry{Path: child, Name: de.Name(), Kind: "unreadable"}})
			continue
		}
		candidates = append(candidates, candidate{name: de.Name(), isDir: isDir, entry: entryFromInfo(child, linfo)})
	}
	sort.SliceStable(candidates, func(i, j int) bool {
		if candidates[i].isDir != candidates[j].isDir {
			return candidates[i].isDir
		}
		li, lj := strings.ToLower(candidates[i].name), strings.ToLower(candidates[j].name)
		if li != lj {
			return li < lj
		}
		return candidates[i].name < candidates[j].name
	})
	total := len(candidates)
	start := offset
	if start > total {
		start = total
	}
	end := start + limit
	if end > total {
		end = total
	}
	entries := make([]Entry, 0, end-start)
	for _, item := range candidates[start:end] {
		entries = append(entries, item.entry)
	}
	return ListResult{Path: expanded, TotalCount: total, Offset: offset, Limit: limit, More: offset+len(entries) < total, Entries: entries}, nil
}

func entryFromInfo(path string, info os.FileInfo) Entry {
	uid, gid := uint32(0), uint32(0)
	if st, ok := info.Sys().(*syscall.Stat_t); ok {
		uid = st.Uid
		gid = st.Gid
	}
	mode := uint32(info.Mode().Perm())
	if info.Mode()&os.ModeSetuid != 0 {
		mode |= 0o4000
	}
	if info.Mode()&os.ModeSetgid != 0 {
		mode |= 0o2000
	}
	if info.Mode()&os.ModeSticky != 0 {
		mode |= 0o1000
	}
	return Entry{
		Path:                  path,
		Name:                  filepath.Base(path),
		Kind:                  Kind(info.Mode()),
		SizeBytes:             max64(0, info.Size()),
		ModifiedAtEpochMillis: max64(0, info.ModTime().UnixNano()/1_000_000),
		Mode:                  mode,
		UID:                   uid,
		GID:                   gid,
	}
}

func RequireRegularNonSymlink(path string) (string, os.FileInfo, error) {
	expanded, err := ExpandUser(path)
	if err != nil {
		return "", nil, err
	}
	info, err := os.Lstat(expanded)
	if err != nil {
		if os.IsNotExist(err) {
			return "", nil, berror.NotFound(fmt.Sprintf("File not found for hashing: %s", expanded), map[string]any{"path": expanded})
		}
		return "", nil, berror.WrapExternal("inspecting hash source", expanded, "go-os", err)
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return "", nil, berror.Validation("Hash source must be a regular non-symlink file", map[string]any{"path": expanded})
	}
	abs, err := filepath.Abs(expanded)
	if err != nil {
		return "", nil, berror.Validation("Cannot normalize path: "+expanded, map[string]any{"path": expanded, "reason": err.Error()})
	}
	return filepath.Clean(abs), info, nil
}

func max64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}
