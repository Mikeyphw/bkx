package archiveio

import (
	"archive/tar"
	"archive/zip"
	"compress/bzip2"
	"compress/gzip"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"

	"bkx/internal/berror"
	"bkx/internal/hashing"

	"github.com/klauspost/compress/zstd"
)

type Entry struct {
	Path      string `json:"path"`
	IsDir     bool   `json:"is_dir"`
	Size      int64  `json:"size"`
	MtimeNS   int64  `json:"mtime_ns"`
	EntryType string `json:"entry_type"`
}

type ListResult struct {
	Success       bool    `json:"success"`
	ArchivePath   string  `json:"archive_path"`
	Entries       []Entry `json:"entries"`
	TotalEntries  int     `json:"total_entries"`
	Page          int     `json:"page"`
	PageSize      int     `json:"page_size"`
	FilterPattern *string `json:"filter_pattern"`
}

type InfoResult struct {
	Success          bool    `json:"success"`
	ArchivePath      string  `json:"archive_path"`
	Format           string  `json:"format"`
	CreatedAt        int64   `json:"created_at"`
	TotalEntries     int     `json:"total_entries"`
	TotalSize        int64   `json:"total_size"`
	UncompressedSize int64   `json:"uncompressed_size"`
	CompressionRatio float64 `json:"compression_ratio"`
}

type VerifyResult struct {
	Success          bool              `json:"success"`
	ArchivePath      string            `json:"archive_path"`
	ChecksumsValid   bool              `json:"checksums_valid"`
	StructureValid   bool              `json:"structure_valid"`
	EntriesChecked   int               `json:"entries_checked"`
	EntriesFailed    int               `json:"entries_failed"`
	FailedEntries    []string          `json:"failed_entries"`
	ArchiveChecksums map[string]string `json:"archive_checksums"`
	Warnings         []string          `json:"warnings"`
	Errors           []string          `json:"errors"`
}

type BrowseOptions struct {
	Query     *string
	FileType  *string
	MinSize   *int64
	MaxSize   *int64
	Limit     int
	Offset    int
	SortBy    string
	SortOrder string
}

type BrowseResult struct {
	Success          bool    `json:"success"`
	ArchivePath      string  `json:"archive_path"`
	Query            *string `json:"query"`
	FileType         *string `json:"file_type"`
	MinSize          *int64  `json:"min_size"`
	MaxSize          *int64  `json:"max_size"`
	Entries          []Entry `json:"entries"`
	TotalMatches     int     `json:"total_matches"`
	Limit            int     `json:"limit"`
	Offset           int     `json:"offset"`
	SortBy           string  `json:"sort_by"`
	SortOrder        string  `json:"sort_order"`
	TotalSize        int64   `json:"total_size"`
	DirectoriesCount int     `json:"directories_count"`
	FilesCount       int     `json:"files_count"`
}

func List(path string, filter *string, page, pageSize int) (ListResult, error) {
	target, err := canonicalArchive(path)
	if err != nil {
		return ListResult{}, err
	}
	if page < 0 {
		return ListResult{}, berror.Validation("page must be a non-negative integer", map[string]any{"page": page})
	}
	if pageSize < 1 || pageSize > 1000 {
		return ListResult{}, berror.Validation("page_size must be between 1 and 1000", map[string]any{"page_size": pageSize})
	}
	entries, _, err := readEntries(target, false)
	if err != nil {
		return ListResult{}, err
	}
	if filter != nil {
		pattern := strings.TrimSpace(*filter)
		if pattern == "" {
			return ListResult{}, berror.Validation("filter_pattern must not be empty", nil)
		}
		kept := entries[:0]
		for _, entry := range entries {
			if pythonFnmatch(entry.Path, pattern) {
				kept = append(kept, entry)
			}
		}
		entries = kept
		filter = &pattern
	}
	total := len(entries)
	start := page * pageSize
	if start > total {
		start = total
	}
	end := start + pageSize
	if end > total {
		end = total
	}
	pageEntries := append([]Entry(nil), entries[start:end]...)
	return ListResult{Success: true, ArchivePath: target, Entries: pageEntries, TotalEntries: total, Page: page, PageSize: pageSize, FilterPattern: filter}, nil
}

func Info(path string) (InfoResult, error) {
	target, err := canonicalArchive(path)
	if err != nil {
		return InfoResult{}, err
	}
	entries, format, err := readEntries(target, false)
	if err != nil {
		return InfoResult{}, err
	}
	st, err := os.Stat(target)
	if err != nil {
		return InfoResult{}, mapPathError(target, err)
	}
	totalSize, err := LogicalSize(target)
	if err != nil {
		return InfoResult{}, err
	}
	var uncompressed int64
	for _, entry := range entries {
		if !entry.IsDir {
			uncompressed += entry.Size
		}
	}
	ratio := 1.0
	if uncompressed > 0 {
		ratio = float64(totalSize) / float64(uncompressed)
	}
	return InfoResult{Success: true, ArchivePath: target, Format: format, CreatedAt: st.ModTime().Unix(), TotalEntries: len(entries), TotalSize: totalSize, UncompressedSize: uncompressed, CompressionRatio: ratio}, nil
}

func Browse(path string, opts BrowseOptions) (BrowseResult, error) {
	target, err := canonicalArchive(path)
	if err != nil {
		return BrowseResult{}, err
	}
	if opts.Limit < 1 || opts.Limit > 1000 {
		return BrowseResult{}, berror.Validation("limit must be between 1 and 1000", map[string]any{"limit": opts.Limit})
	}
	if opts.Offset < 0 {
		return BrowseResult{}, berror.Validation("offset must be non-negative", map[string]any{"offset": opts.Offset})
	}
	if opts.FileType != nil && *opts.FileType != "file" && *opts.FileType != "dir" {
		return BrowseResult{}, berror.Validation("file_type must be 'file' or 'dir'", map[string]any{"file_type": *opts.FileType})
	}
	if opts.MinSize != nil && *opts.MinSize < 0 {
		return BrowseResult{}, berror.Validation("min_size must be a non-negative integer", nil)
	}
	if opts.MaxSize != nil && *opts.MaxSize < 0 {
		return BrowseResult{}, berror.Validation("max_size must be a non-negative integer", nil)
	}
	if opts.MinSize != nil && opts.MaxSize != nil && *opts.MinSize > *opts.MaxSize {
		return BrowseResult{}, berror.Validation("min_size cannot exceed max_size", nil)
	}
	if opts.SortBy != "path" && opts.SortBy != "size" && opts.SortBy != "mtime" {
		return BrowseResult{}, berror.Validation("sort_by must be path, size, or mtime", nil)
	}
	if opts.SortOrder != "asc" && opts.SortOrder != "desc" {
		return BrowseResult{}, berror.Validation("sort_order must be asc or desc", nil)
	}
	entries, _, err := readEntries(target, false)
	if err != nil {
		return BrowseResult{}, err
	}
	filtered := make([]Entry, 0, len(entries))
	query := ""
	if opts.Query != nil {
		query = strings.ToLower(strings.TrimSpace(*opts.Query))
	}
	for _, entry := range entries {
		if query != "" && !strings.Contains(strings.ToLower(entry.Path), query) {
			continue
		}
		if opts.FileType != nil {
			if *opts.FileType == "file" && entry.IsDir {
				continue
			}
			if *opts.FileType == "dir" && !entry.IsDir {
				continue
			}
		}
		if opts.MinSize != nil && entry.Size < *opts.MinSize {
			continue
		}
		if opts.MaxSize != nil && entry.Size > *opts.MaxSize {
			continue
		}
		filtered = append(filtered, entry)
	}
	totalMatches := len(filtered)
	less := func(i, j int) bool {
		var result bool
		switch opts.SortBy {
		case "size":
			result = filtered[i].Size < filtered[j].Size
		case "mtime":
			result = filtered[i].MtimeNS < filtered[j].MtimeNS
		default:
			result = filtered[i].Path < filtered[j].Path
		}
		if opts.SortOrder == "desc" {
			return !result && !equalSort(filtered[i], filtered[j], opts.SortBy)
		}
		return result
	}
	sort.SliceStable(filtered, less)
	start := opts.Offset
	if start > len(filtered) {
		start = len(filtered)
	}
	end := start + opts.Limit
	if end > len(filtered) {
		end = len(filtered)
	}
	selected := append([]Entry(nil), filtered[start:end]...)
	var totalSize int64
	dirs := 0
	for _, entry := range selected {
		totalSize += entry.Size
		if entry.IsDir {
			dirs++
		}
	}
	return BrowseResult{Success: true, ArchivePath: target, Query: opts.Query, FileType: opts.FileType, MinSize: opts.MinSize, MaxSize: opts.MaxSize, Entries: selected, TotalMatches: totalMatches, Limit: opts.Limit, Offset: opts.Offset, SortBy: opts.SortBy, SortOrder: opts.SortOrder, TotalSize: totalSize, DirectoriesCount: dirs, FilesCount: len(selected) - dirs}, nil
}

func Verify(path string, algorithms []string) (VerifyResult, error) {
	target, err := canonicalArchive(path)
	if err != nil {
		return VerifyResult{}, err
	}
	normalized, err := normalizeAlgorithms(algorithms)
	if err != nil {
		return VerifyResult{}, err
	}
	parts, err := multipartPartPaths(target)
	if err != nil {
		return VerifyResult{}, mapPathError(target, err)
	}
	checksums := make(map[string]string, len(normalized))
	for _, algorithm := range normalized {
		result, hashErr := hashing.Files(parts, algorithm)
		if hashErr != nil {
			return VerifyResult{}, hashErr
		}
		checksums[algorithm] = result.Checksum
	}
	format, detectErr := detectFormat(target)
	if detectErr != nil {
		return VerifyResult{}, detectErr
	}
	if format == "zip" {
		return verifyZip(target, checksums), nil
	}
	return verifyTar(target, checksums), nil
}

func verifyZip(path string, checksums map[string]string) VerifyResult {
	result := VerifyResult{ArchivePath: path, ChecksumsValid: true, StructureValid: true, ArchiveChecksums: checksums, FailedEntries: []string{}, Warnings: []string{}, Errors: []string{}}
	zr, err := openZipArchive(path)
	if err != nil {
		result.StructureValid = false
		result.Errors = append(result.Errors, err.Error())
		result.Success = false
		return result
	}
	defer zr.Close()
	failed := map[string]bool{}
	for _, file := range zr.File {
		result.EntriesChecked++
		if _, err := normalizeMember(file.Name); err != nil {
			result.ChecksumsValid = false
			if !failed[file.Name] {
				failed[file.Name] = true
				result.FailedEntries = append(result.FailedEntries, file.Name)
			}
			result.Errors = append(result.Errors, fmt.Sprintf("%s: %v", file.Name, err))
			continue
		}
		if file.FileInfo().IsDir() {
			continue
		}
		rc, err := openZipPayload(file)
		if err == nil {
			_, err = io.Copy(io.Discard, rc)
			closeErr := rc.Close()
			if err == nil {
				err = closeErr
			}
		}
		if err != nil {
			result.ChecksumsValid = false
			if !failed[file.Name] {
				failed[file.Name] = true
				result.FailedEntries = append(result.FailedEntries, file.Name)
			}
			result.Errors = append(result.Errors, fmt.Sprintf("%s: %v", file.Name, err))
		}
	}
	result.EntriesFailed = len(result.FailedEntries)
	result.Success = result.ChecksumsValid && result.StructureValid
	return result
}

func verifyTar(path string, checksums map[string]string) VerifyResult {
	result := VerifyResult{ArchivePath: path, ChecksumsValid: true, StructureValid: true, ArchiveChecksums: checksums, FailedEntries: []string{}, Warnings: []string{}, Errors: []string{}}
	rc, _, err := openTarStream(path)
	if err != nil {
		result.StructureValid = false
		result.Errors = append(result.Errors, err.Error())
		result.Success = false
		return result
	}
	defer rc.Close()
	tr := tar.NewReader(rc)
	for {
		hdr, nextErr := tr.Next()
		if errors.Is(nextErr, io.EOF) {
			// TAR ends before its enclosing compressed frame. Drain that frame
			// to validate its footer/checksum and detect trailing truncation.
			if _, err := io.Copy(io.Discard, rc); err != nil {
				result.ChecksumsValid = false
				result.StructureValid = false
				result.Errors = append(result.Errors, err.Error())
			}
			break
		}
		if nextErr != nil {
			result.StructureValid = false
			result.Errors = append(result.Errors, nextErr.Error())
			break
		}
		result.EntriesChecked++
		entryErr := error(nil)
		if _, err := normalizeMember(hdr.Name); err != nil {
			entryErr = err
		}
		if entryErr == nil && (hdr.Typeflag == tar.TypeSymlink || hdr.Typeflag == tar.TypeLink) {
			entryErr = berror.Validation("Archive link members are unsafe in this verification policy", map[string]any{"member": hdr.Name})
		}
		if entryErr == nil && (hdr.Typeflag == tar.TypeReg || hdr.Typeflag == tar.TypeRegA) {
			_, entryErr = io.Copy(io.Discard, tr)
		}
		if entryErr != nil {
			result.ChecksumsValid = false
			result.FailedEntries = append(result.FailedEntries, hdr.Name)
			result.Errors = append(result.Errors, fmt.Sprintf("%s: %v", hdr.Name, entryErr))
		}
	}
	result.EntriesFailed = len(result.FailedEntries)
	result.Success = result.ChecksumsValid && result.StructureValid
	return result
}

func openZipPayload(file *zip.File) (io.ReadCloser, error) {
	switch file.Method {
	case zip.Store, zip.Deflate, zipMethodZstd, zipMethodZstdLegacy:
		return file.Open()
	case zipMethodBzip2:
		raw, err := file.OpenRaw()
		if err != nil {
			return nil, err
		}
		return io.NopCloser(bzip2.NewReader(raw)), nil
	case zipMethodLZMA:
		raw, err := file.OpenRaw()
		if err != nil {
			return nil, err
		}
		header := make([]byte, 9)
		if _, err := io.ReadFull(raw, header); err != nil {
			return nil, fmt.Errorf("zip LZMA header: %w", err)
		}
		if header[0] != 9 || header[1] != 4 || binary.LittleEndian.Uint16(header[2:4]) != 5 {
			return nil, fmt.Errorf("unsupported zip LZMA properties header")
		}
		prop := int(header[4])
		lc := prop % 9
		rest := prop / 9
		lp := rest % 5
		pb := rest / 5
		if lc > 8 || lp > 4 || pb > 4 {
			return nil, fmt.Errorf("invalid zip LZMA properties")
		}
		dictSize := binary.LittleEndian.Uint32(header[5:9])
		xz, err := exec.LookPath("xz")
		if err != nil {
			return nil, berror.External("LZMA-compressed ZIP requires the optional xz tool during GO06 migration", map[string]any{"archive_path": file.Name, "tool": "xz"})
		}
		filter := fmt.Sprintf("--lzma1=lc=%d,lp=%d,pb=%d,dict=%d", lc, lp, pb, dictSize)
		cmd := exec.Command(xz, "--format=raw", "--decompress", filter, "-c")
		cmd.Stdin = raw
		stdout, err := cmd.StdoutPipe()
		if err != nil {
			return nil, err
		}
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		return &commandReadCloser{ReadCloser: stdout, cmd: cmd}, nil
	default:
		return nil, zip.ErrAlgorithm
	}
}

func readEntries(path string, payload bool) ([]Entry, string, error) {
	format, err := detectFormat(path)
	if err != nil {
		return nil, "", err
	}
	if format == "zip" {
		zr, err := openZipArchive(path)
		if err != nil {
			return nil, "", berror.WrapExternal("reading zip archive", path, "go-stdlib", err)
		}
		defer zr.Close()
		entries := make([]Entry, 0, len(zr.File))
		for _, file := range zr.File {
			info := file.FileInfo()
			entryType := "file"
			if info.IsDir() {
				entryType = "directory"
			} else if info.Mode()&os.ModeSymlink != 0 {
				entryType = "symlink"
			} else if !info.Mode().IsRegular() {
				entryType = "special"
			}
			entries = append(entries, Entry{Path: file.Name, IsDir: info.IsDir(), Size: int64(file.UncompressedSize64), MtimeNS: zipMtimeNS(file), EntryType: entryType})
			if payload && !info.IsDir() {
				rc, err := openZipPayload(file)
				if err != nil {
					return nil, "", err
				}
				_, err = io.Copy(io.Discard, rc)
				_ = rc.Close()
				if err != nil {
					return nil, "", err
				}
			}
		}
		return entries, "zip", nil
	}
	rc, _, err := openTarStream(path)
	if err != nil {
		return nil, "", err
	}
	defer rc.Close()
	tr := tar.NewReader(rc)
	entries := []Entry{}
	for {
		hdr, err := tr.Next()
		if errors.Is(err, io.EOF) {
			if _, err := io.Copy(io.Discard, rc); err != nil {
				return nil, "", berror.WrapExternal("finishing compressed tar archive", path, "go-codec", err)
			}
			break
		}
		if err != nil {
			return nil, "", berror.WrapExternal("reading tar archive", path, "go-stdlib", err)
		}
		entryType := "special"
		isDir := false
		switch hdr.Typeflag {
		case tar.TypeDir:
			entryType, isDir = "directory", true
		case tar.TypeReg, tar.TypeRegA:
			entryType = "file"
		case tar.TypeSymlink, tar.TypeLink:
			entryType = "symlink"
		}
		entries = append(entries, Entry{Path: hdr.Name, IsDir: isDir, Size: hdr.Size, MtimeNS: hdr.ModTime.UnixNano(), EntryType: entryType})
		if payload && entryType == "file" {
			if _, err := io.Copy(io.Discard, tr); err != nil {
				return nil, "", err
			}
		}
	}
	return entries, "tar", nil
}

func canonicalArchive(path string) (string, error) {
	expanded := path
	if strings.HasPrefix(path, "~") {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		if path == "~" {
			expanded = home
		} else if strings.HasPrefix(path, "~/") {
			expanded = filepath.Join(home, path[2:])
		}
	}
	abs, err := filepath.Abs(expanded)
	if err != nil {
		return "", berror.Validation("archive_path must resolve to a local path", map[string]any{"path": path})
	}
	abs = filepath.Clean(abs)
	st, err := os.Stat(abs)
	if err != nil {
		return "", mapPathError(abs, err)
	}
	if st.IsDir() {
		return "", berror.Validation("Archive path must be a file", map[string]any{"path": abs})
	}
	return abs, nil
}

func detectFormat(path string) (string, error) {
	if zr, err := openZipArchive(path); err == nil {
		_ = zr.Close()
		return "zip", nil
	}
	if ok, _ := probeTar(path); ok {
		return "tar", nil
	}
	suffix := strings.ToLower(path)
	if strings.HasSuffix(suffix, ".zip") {
		return "zip", nil
	}
	return "", berror.Validation("Unsupported or unrecognized archive format", map[string]any{"archive_path": path})
}

func probeTar(path string) (bool, error) {
	rc, _, err := openTarStream(path)
	if err != nil {
		return false, nil
	}
	defer rc.Close()
	tr := tar.NewReader(rc)
	_, err = tr.Next()
	if err == nil || errors.Is(err, io.EOF) {
		return true, nil
	}
	return false, nil
}

type commandReadCloser struct {
	io.ReadCloser
	cmd     *exec.Cmd
	closers []io.Closer
}

func (c *commandReadCloser) Close() error {
	_ = c.ReadCloser.Close()
	var first error
	if err := c.cmd.Wait(); err != nil {
		first = err
	}
	for _, closer := range c.closers {
		if err := closer.Close(); err != nil && first == nil {
			first = err
		}
	}
	return first
}

type joinedReadCloser struct {
	io.Reader
	closers []io.Closer
}

func (j *joinedReadCloser) Close() error {
	var first error
	for _, c := range j.closers {
		if err := c.Close(); err != nil && first == nil {
			first = err
		}
	}
	return first
}

type zstdReadCloser struct {
	decoder *zstd.Decoder
	source  io.Closer
}

func (z *zstdReadCloser) Read(p []byte) (int, error) { return z.decoder.Read(p) }

func (z *zstdReadCloser) Close() error {
	z.decoder.Close()
	if z.source != nil {
		return z.source.Close()
	}
	return nil
}

type openedZip struct {
	*zip.Reader
	parts *multipartReaderAt
}

func openZipArchive(path string) (*openedZip, error) {
	parts, err := openMultipartReaderAt(path)
	if err != nil {
		return nil, err
	}
	zr, err := zip.NewReader(parts, parts.size)
	if err != nil {
		_ = parts.Close()
		return nil, err
	}
	// Use archive/zip's checked reader so the ZIP CRC and uncompressed size
	// are verified in addition to the zstd frame checksum. Read the historical
	// method 20 for compatibility; new archives always use method 93.
	decompressor := zstd.ZipDecompressor(zstd.WithDecoderConcurrency(1))
	zr.RegisterDecompressor(zipMethodZstd, decompressor)
	zr.RegisterDecompressor(zipMethodZstdLegacy, decompressor)
	return &openedZip{Reader: zr, parts: parts}, nil
}

func (z *openedZip) Close() error { return z.parts.Close() }

func openTarStream(path string) (io.ReadCloser, string, error) {
	stream, err := openMultipartStream(path)
	if err != nil {
		return nil, "", mapPathError(path, err)
	}
	magic := make([]byte, 6)
	n, _ := io.ReadFull(stream, magic)
	_ = stream.Close()
	magic = magic[:n]

	stream, err = openMultipartStream(path)
	if err != nil {
		return nil, "", mapPathError(path, err)
	}
	if len(magic) >= 2 && magic[0] == 0x1f && magic[1] == 0x8b {
		gz, err := gzip.NewReader(stream)
		if err != nil {
			_ = stream.Close()
			return nil, "", err
		}
		return &joinedReadCloser{Reader: gz, closers: []io.Closer{gz, stream}}, "gzip", nil
	}
	if len(magic) >= 4 && magic[0] == 0x28 && magic[1] == 0xb5 && magic[2] == 0x2f && magic[3] == 0xfd {
		decoder, err := zstd.NewReader(stream, zstd.WithDecoderConcurrency(1))
		if err != nil {
			_ = stream.Close()
			return nil, "", err
		}
		return &zstdReadCloser{decoder: decoder, source: stream}, "zstd", nil
	}
	if len(magic) >= 3 && string(magic[:3]) == "BZh" {
		return &joinedReadCloser{Reader: bzip2.NewReader(stream), closers: []io.Closer{stream}}, "bzip2", nil
	}
	if len(magic) >= 6 && string(magic[:6]) == "\xfd7zXZ\x00" {
		xz, lookErr := exec.LookPath("xz")
		if lookErr != nil {
			_ = stream.Close()
			return nil, "", berror.External("XZ-compressed tar requires the optional xz tool during GO05 migration", map[string]any{"archive_path": path, "tool": "xz"})
		}
		cmd := exec.Command(xz, "-dc")
		cmd.Stdin = stream
		stdout, err := cmd.StdoutPipe()
		if err != nil {
			_ = stream.Close()
			return nil, "", err
		}
		if err := cmd.Start(); err != nil {
			_ = stream.Close()
			return nil, "", err
		}
		return &commandReadCloser{ReadCloser: stdout, cmd: cmd, closers: []io.Closer{stream}}, "xz", nil
	}
	return stream, "none", nil
}

func normalizeAlgorithms(input []string) ([]string, error) {
	if len(input) == 0 {
		input = []string{"sha256"}
	}
	seen := map[string]bool{}
	out := []string{}
	for _, algorithm := range input {
		algorithm = strings.ToLower(strings.TrimSpace(algorithm))
		if algorithm != "md5" && algorithm != "sha256" && algorithm != "sha512" {
			return nil, berror.Validation("Unsupported archive verification hash algorithm", map[string]any{"algorithm": algorithm, "supported": []string{"md5", "sha256", "sha512"}})
		}
		if !seen[algorithm] {
			seen[algorithm] = true
			out = append(out, algorithm)
		}
	}
	return out, nil
}

var windowsDrive = regexp.MustCompile(`^[A-Za-z]:`)

func normalizeMember(path string) (string, error) {
	raw := strings.ReplaceAll(strings.TrimSpace(path), "\\", "/")
	directory := strings.HasSuffix(raw, "/")
	if raw == "" || strings.ContainsRune(raw, '\x00') || strings.HasPrefix(raw, "/") || windowsDrive.MatchString(raw) {
		return "", berror.Validation("Archive member path must be relative and non-empty", map[string]any{"path": path})
	}
	for strings.HasPrefix(raw, "./") {
		raw = raw[2:]
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
	result := strings.Join(cleaned, "/")
	if directory {
		result += "/"
	}
	return result, nil
}

func pythonFnmatch(value, pattern string) bool {
	var b strings.Builder
	b.WriteString("(?s)^")
	for i := 0; i < len(pattern); i++ {
		c := pattern[i]
		switch c {
		case '*':
			b.WriteString(".*")
		case '?':
			b.WriteByte('.')
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
				b.WriteString(`\[`)
				continue
			}
			stuff := pattern[i+1 : j]
			if strings.HasPrefix(stuff, "!") {
				stuff = "^" + stuff[1:]
			}
			b.WriteByte('[')
			b.WriteString(stuff)
			b.WriteByte(']')
			i = j
		default:
			b.WriteString(regexp.QuoteMeta(string(c)))
		}
	}
	b.WriteString("$")
	re, err := regexp.Compile(b.String())
	return err == nil && re.MatchString(value)
}

func zipMtimeNS(file *zip.File) int64 {
	d, t := file.ModifiedDate, file.ModifiedTime
	if d == 0 {
		return file.Modified.In(time.Local).UnixNano()
	}
	year := int(d>>9) + 1980
	month := time.Month((d >> 5) & 0x0f)
	day := int(d & 0x1f)
	hour := int(t >> 11)
	minute := int((t >> 5) & 0x3f)
	second := int(t&0x1f) * 2
	return time.Date(year, month, day, hour, minute, second, 0, time.Local).UnixNano()
}

func mapPathError(path string, err error) error {
	if os.IsNotExist(err) {
		return berror.NotFound("Archive not found: "+path, map[string]any{"path": path})
	}
	if os.IsPermission(err) {
		return berror.Permission("Permission denied while reading archive "+path, map[string]any{"path": path})
	}
	return berror.WrapExternal("reading archive", path, "go-stdlib", err)
}

func equalSort(a, b Entry, field string) bool {
	switch field {
	case "size":
		return a.Size == b.Size
	case "mtime":
		return a.MtimeNS == b.MtimeNS
	default:
		return a.Path == b.Path
	}
}
