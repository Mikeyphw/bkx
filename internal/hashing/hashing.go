package hashing

import (
	"crypto/md5"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/hex"
	"hash"
	"io"
	"os"
	"sort"
	"strings"

	"bkx/internal/berror"
	"bkx/internal/fsutil"
)

type Result struct {
	Algorithm   string `json:"algorithm"`
	Checksum    string `json:"checksum"`
	BytesHashed int64  `json:"bytes_hashed"`
}

type DirectoryResult struct {
	Success           bool              `json:"success"`
	Algorithm         string            `json:"algorithm"`
	RootPath          string            `json:"root_path"`
	FileCount         int               `json:"file_count"`
	TotalBytes        int64             `json:"total_bytes"`
	FileHashes        map[string]Result `json:"file_hashes"`
	DirectoryChecksum string            `json:"directory_checksum"`
}

func newHash(algorithm string) (hash.Hash, error) {
	switch strings.ToLower(strings.TrimSpace(algorithm)) {
	case "md5":
		return md5.New(), nil
	case "sha256":
		return sha256.New(), nil
	case "sha512":
		return sha512.New(), nil
	default:
		return nil, berror.Validation("Unsupported hash algorithm for pure Go hasher", map[string]any{"algorithm": algorithm, "supported": []string{"md5", "sha256", "sha512"}})
	}
}

func Bytes(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

func File(path, algorithm string) (Result, error) {
	target, _, err := fsutil.RequireRegularNonSymlink(path)
	if err != nil {
		return Result{}, err
	}
	f, err := os.Open(target)
	if err != nil {
		if os.IsPermission(err) {
			return Result{}, berror.Permission("Permission denied while hashing "+target, map[string]any{"path": target})
		}
		return Result{}, berror.WrapExternal("hashing file", target, "go-stdlib", err)
	}
	defer f.Close()
	h, err := newHash(algorithm)
	if err != nil {
		return Result{}, err
	}
	n, err := io.CopyBuffer(h, f, make([]byte, 64*1024))
	if err != nil {
		return Result{}, berror.WrapExternal("hashing file", target, "go-stdlib", err)
	}
	return Result{Algorithm: strings.ToLower(strings.TrimSpace(algorithm)), Checksum: hex.EncodeToString(h.Sum(nil)), BytesHashed: n}, nil
}

func Verify(path, expected string) (bool, error) {
	got, err := File(path, "sha256")
	if err != nil {
		return false, err
	}
	return strings.EqualFold(got.Checksum, expected), nil
}

func Directory(path, algorithm string, recursive bool) (DirectoryResult, error) {
	var entries []fsutil.TraversalEntry
	var root string
	if recursive {
		manifest, err := fsutil.BuildSourceManifest([]string{path}, nil, nil, "", fsutil.SymlinkReject)
		if err != nil {
			return DirectoryResult{}, err
		}
		entries = manifest.Files()
		root = manifest.Roots[0]
	} else {
		expanded, err := fsutil.ExpandUser(path)
		if err != nil {
			return DirectoryResult{}, err
		}
		abs, err := os.Stat(expanded)
		if err != nil {
			return DirectoryResult{}, err
		}
		if !abs.IsDir() {
			manifest, err := fsutil.BuildSourceManifest([]string{path}, nil, nil, "", fsutil.SymlinkReject)
			if err != nil {
				return DirectoryResult{}, err
			}
			entries = manifest.Files()
			root = manifest.Roots[0]
		} else {
			manifest, err := fsutil.BuildSourceManifest([]string{path}, nil, nil, "", fsutil.SymlinkReject)
			if err != nil {
				return DirectoryResult{}, err
			}
			root = manifest.Roots[0]
			for _, entry := range manifest.Files() {
				if filepathDir(entry.Path) == root {
					entries = append(entries, entry)
				}
			}
		}
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].ArchiveName < entries[j].ArchiveName })
	dirHasher, err := newHash(algorithm)
	if err != nil {
		return DirectoryResult{}, err
	}
	hashes := make(map[string]Result, len(entries))
	var total int64
	for _, entry := range entries {
		result, err := File(entry.Path, algorithm)
		if err != nil {
			return DirectoryResult{}, err
		}
		hashes[entry.Path] = result
		total += result.BytesHashed
		_, _ = dirHasher.Write([]byte(entry.ArchiveName))
		_, _ = dirHasher.Write([]byte{0})
		_, _ = dirHasher.Write([]byte(result.Checksum))
		_, _ = dirHasher.Write([]byte{0})
	}
	return DirectoryResult{Success: true, Algorithm: strings.ToLower(strings.TrimSpace(algorithm)), RootPath: root, FileCount: len(entries), TotalBytes: total, FileHashes: hashes, DirectoryChecksum: hex.EncodeToString(dirHasher.Sum(nil))}, nil
}

func filepathDir(path string) string {
	idx := strings.LastIndexAny(path, "/\\")
	if idx < 0 {
		return "."
	}
	if idx == 0 {
		return path[:1]
	}
	return path[:idx]
}
