package archiveio

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"time"
)

const multipartManifestSchemaVersion = 1

type MultipartManifestPart struct {
	Index  int    `json:"index"`
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}

type MultipartManifest struct {
	SchemaVersion int                     `json:"schema_version"`
	ArchiveBase   string                  `json:"archive_base"`
	PartCount     int                     `json:"part_count"`
	TotalBytes    int64                   `json:"total_bytes"`
	CreatedAt     string                  `json:"created_at"`
	Parts         []MultipartManifestPart `json:"parts"`
}

func multipartManifestPath(base string) string { return base + ".bkx-parts.json" }

// BKX split archives keep the logical archive at base and append additional
// byte-range parts as base.part002, base.part003, ... . Concatenating the
// parts yields the exact original archive byte stream. Keeping the first part
// at the normal archive path preserves archive discovery and makes split
// archives transparent to callers that use archiveio.
func multipartPartName(base string, index int) string {
	if index <= 1 {
		return base
	}
	return fmt.Sprintf("%s.part%03d", base, index)
}

func multipartPartPaths(base string) ([]string, error) {
	if manifest, err := readMultipartManifest(base); err == nil {
		return manifestPartPaths(base, manifest)
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if err := validateMultipartPart(base); err != nil {
		return nil, err
	}
	parts := []string{base}
	for index := 2; ; index++ {
		if index > maxArchiveEntries {
			return nil, fmt.Errorf("too many archive split parts")
		}
		path := multipartPartName(base, index)
		if err := validateMultipartPart(path); err != nil {
			if errors.Is(err, os.ErrNotExist) {
				break
			}
			return nil, err
		}
		parts = append(parts, path)
	}
	return parts, nil
}

func readMultipartManifest(base string) (MultipartManifest, error) {
	path := multipartManifestPath(base)
	b, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return MultipartManifest{}, os.ErrNotExist
		}
		return MultipartManifest{}, err
	}
	var manifest MultipartManifest
	if err := json.Unmarshal(b, &manifest); err != nil {
		return MultipartManifest{}, fmt.Errorf("invalid archive part manifest %s: %w", path, err)
	}
	return manifest, nil
}

func manifestPartPaths(base string, manifest MultipartManifest) ([]string, error) {
	if manifest.SchemaVersion != multipartManifestSchemaVersion {
		return nil, fmt.Errorf("unsupported archive part manifest schema %d", manifest.SchemaVersion)
	}
	if manifest.ArchiveBase != filepath.Base(base) {
		return nil, fmt.Errorf("archive part manifest belongs to %s, not %s", manifest.ArchiveBase, filepath.Base(base))
	}
	if manifest.PartCount < 1 || manifest.PartCount != len(manifest.Parts) {
		return nil, fmt.Errorf("archive part manifest part count mismatch")
	}
	parts := make([]string, 0, len(manifest.Parts))
	dir := filepath.Dir(base)
	var total int64
	for expected, part := range manifest.Parts {
		if part.Index != expected+1 || part.Name == "" || filepath.Base(part.Name) != part.Name {
			return nil, fmt.Errorf("archive part manifest contains invalid part %d", expected+1)
		}
		path := filepath.Join(dir, part.Name)
		if err := validateMultipartPart(path); err != nil {
			return nil, err
		}
		if part.Index == 1 && path != base {
			return nil, fmt.Errorf("archive part manifest first part is not the base archive")
		}
		st, err := os.Stat(path)
		if err != nil {
			return nil, err
		}
		if st.Size() != part.Size {
			return nil, fmt.Errorf("archive part manifest size mismatch for %s", path)
		}
		sum, err := sha256File(path)
		if err != nil {
			return nil, err
		}
		if sum != part.SHA256 {
			return nil, fmt.Errorf("archive part manifest checksum mismatch for %s", path)
		}
		total += st.Size()
		parts = append(parts, path)
	}
	if total != manifest.TotalBytes {
		return nil, fmt.Errorf("archive part manifest total size mismatch")
	}
	return parts, nil
}

func validateMultipartPart(path string) error {
	st, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if st.Mode()&os.ModeSymlink != 0 {
		return fmt.Errorf("archive part must not be a symbolic link: %s", path)
	}
	if !st.Mode().IsRegular() {
		return fmt.Errorf("archive part must be a regular file: %s", path)
	}
	return nil
}

type multipartStream struct {
	readers []io.Reader
	files   []*os.File
	reader  io.Reader
}

func openMultipartStream(base string) (io.ReadCloser, error) {
	paths, err := multipartPartPaths(base)
	if err != nil {
		return nil, err
	}
	stream := &multipartStream{}
	for _, path := range paths {
		f, err := os.Open(path)
		if err != nil {
			_ = stream.Close()
			return nil, err
		}
		stream.files = append(stream.files, f)
		stream.readers = append(stream.readers, f)
	}
	stream.reader = io.MultiReader(stream.readers...)
	return stream, nil
}

func (m *multipartStream) Read(p []byte) (int, error) {
	return m.reader.Read(p)
}

func (m *multipartStream) Close() error {
	var first error
	for _, f := range m.files {
		if err := f.Close(); err != nil && first == nil {
			first = err
		}
	}
	return first
}

type multipartReaderAtPart struct {
	file  *os.File
	start int64
	size  int64
}

type multipartReaderAt struct {
	parts []multipartReaderAtPart
	size  int64
}

func openMultipartReaderAt(base string) (*multipartReaderAt, error) {
	paths, err := multipartPartPaths(base)
	if err != nil {
		return nil, err
	}
	reader := &multipartReaderAt{}
	var offset int64
	for _, path := range paths {
		f, err := os.Open(path)
		if err != nil {
			_ = reader.Close()
			return nil, err
		}
		st, err := f.Stat()
		if err != nil {
			_ = f.Close()
			_ = reader.Close()
			return nil, err
		}
		reader.parts = append(reader.parts, multipartReaderAtPart{file: f, start: offset, size: st.Size()})
		offset += st.Size()
	}
	reader.size = offset
	return reader, nil
}

func (m *multipartReaderAt) ReadAt(p []byte, off int64) (int, error) {
	if off < 0 {
		return 0, fmt.Errorf("negative read offset")
	}
	if len(p) == 0 {
		return 0, nil
	}
	if off >= m.size {
		return 0, io.EOF
	}
	total := 0
	for _, part := range m.parts {
		if off >= part.start+part.size {
			continue
		}
		if off < part.start {
			continue
		}
		local := off - part.start
		available := part.size - local
		want := int64(len(p) - total)
		if want > available {
			want = available
		}
		n, err := part.file.ReadAt(p[total:total+int(want)], local)
		total += n
		off += int64(n)
		if err != nil && !errors.Is(err, io.EOF) {
			return total, err
		}
		if total == len(p) {
			return total, nil
		}
	}
	return total, io.EOF
}

func (m *multipartReaderAt) Close() error {
	var first error
	for i := range m.parts {
		if err := m.parts[i].file.Close(); err != nil && first == nil {
			first = err
		}
	}
	return first
}

type splitStagingWriter struct {
	basePath string
	maxSize  int64
	current  *os.File
	size     int64
	index    int
	paths    []string
}

func newSplitStagingWriter(basePath string, first *os.File, maxSize int64) (*splitStagingWriter, error) {
	if maxSize <= 0 {
		return nil, fmt.Errorf("split size must be positive")
	}
	if first == nil {
		return nil, fmt.Errorf("split staging writer requires a first part")
	}
	return &splitStagingWriter{
		basePath: basePath,
		maxSize:  maxSize,
		current:  first,
		index:    1,
		paths:    []string{basePath},
	}, nil
}

func (s *splitStagingWriter) Write(p []byte) (int, error) {
	written := 0
	for len(p) > 0 {
		if s.current == nil {
			if err := s.openNext(); err != nil {
				return written, err
			}
		}
		remaining := s.maxSize - s.size
		if remaining == 0 {
			if err := s.finishCurrent(); err != nil {
				return written, err
			}
			continue
		}
		nmax := int64(len(p))
		if nmax > remaining {
			nmax = remaining
		}
		n, err := s.current.Write(p[:int(nmax)])
		written += n
		s.size += int64(n)
		p = p[n:]
		if err != nil {
			return written, err
		}
		if n == 0 {
			return written, io.ErrShortWrite
		}
	}
	return written, nil
}

func (s *splitStagingWriter) openNext() error {
	s.index++
	path := multipartPartName(s.basePath, s.index)
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return err
	}
	s.current = f
	s.size = 0
	s.paths = append(s.paths, path)
	return nil
}

func (s *splitStagingWriter) finishCurrent() error {
	if s.current == nil {
		return nil
	}
	if err := s.current.Sync(); err != nil {
		_ = s.current.Close()
		s.current = nil
		return err
	}
	err := s.current.Close()
	s.current = nil
	s.size = 0
	return err
}

func (s *splitStagingWriter) Close() error {
	return s.finishCurrent()
}

func (s *splitStagingWriter) Paths() []string {
	return append([]string(nil), s.paths...)
}

func multipartFinalPaths(base string, count int) []string {
	out := make([]string, 0, count)
	for index := 1; index <= count; index++ {
		out = append(out, multipartPartName(base, index))
	}
	return out
}

func writeMultipartManifest(base string, parts []string, totalBytes int64) (string, error) {
	if len(parts) < 2 {
		return "", nil
	}
	manifest := MultipartManifest{
		SchemaVersion: multipartManifestSchemaVersion,
		ArchiveBase:   filepath.Base(base),
		PartCount:     len(parts),
		TotalBytes:    totalBytes,
		CreatedAt:     time.Now().UTC().Format(time.RFC3339Nano),
		Parts:         make([]MultipartManifestPart, 0, len(parts)),
	}
	for index, path := range parts {
		st, err := os.Stat(path)
		if err != nil {
			return "", err
		}
		sum, err := sha256File(path)
		if err != nil {
			return "", err
		}
		manifest.Parts = append(manifest.Parts, MultipartManifestPart{Index: index + 1, Name: filepath.Base(path), Size: st.Size(), SHA256: sum})
	}
	payload, err := json.MarshalIndent(manifest, "", "  ")
	if err != nil {
		return "", err
	}
	payload = append(payload, '\n')
	manifestPath := multipartManifestPath(base)
	f, err := os.OpenFile(manifestPath, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		if os.IsExist(err) {
			return "", fmt.Errorf("archive part manifest already exists: %s", manifestPath)
		}
		return "", err
	}
	keep := false
	defer func() {
		_ = f.Close()
		if !keep {
			_ = os.Remove(manifestPath)
		}
	}()
	if _, err := f.Write(payload); err != nil {
		return "", err
	}
	if err := f.Sync(); err != nil {
		return "", err
	}
	if err := f.Close(); err != nil {
		return "", err
	}
	keep = true
	_ = syncDirLocal(filepath.Dir(base))
	return manifestPath, nil
}

func sha256File(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// publishMultipart makes sidecars visible before the logical base path and
// publishes the base last. This means archive discovery cannot observe a base
// archive until every required sidecar has already been placed.
func publishMultipart(tempPaths, finalPaths []string) error {
	if len(tempPaths) == 0 || len(tempPaths) != len(finalPaths) {
		return fmt.Errorf("invalid multipart publication mapping")
	}
	for _, final := range finalPaths {
		if _, err := os.Lstat(final); err == nil {
			return fmt.Errorf("archive output already exists: %s", final)
		} else if !os.IsNotExist(err) {
			return err
		}
	}

	order := make([]int, 0, len(tempPaths))
	for i := 1; i < len(tempPaths); i++ {
		order = append(order, i)
	}
	order = append(order, 0)

	published := make([]string, 0, len(order))
	for _, i := range order {
		if err := publishArchivePartNoReplace(tempPaths[i], finalPaths[i]); err != nil {
			for _, path := range published {
				_ = os.Remove(path)
			}
			return err
		}
		published = append(published, finalPaths[i])
	}
	_ = syncDirLocal(filepath.Dir(finalPaths[0]))
	return nil
}

func syncDirLocal(path string) error {
	d, err := os.Open(path)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}

// LogicalSize returns the byte size of an archive including contiguous split
// sidecars. Unsplit archives simply return the size of the base file.
func LogicalSize(path string) (int64, error) {
	target, err := canonicalArchive(path)
	if err != nil {
		return 0, err
	}
	parts, err := multipartPartPaths(target)
	if err != nil {
		return 0, mapPathError(target, err)
	}
	return multipartTotalSize(parts)
}

func multipartTotalSize(paths []string) (int64, error) {
	var total int64
	for _, path := range paths {
		st, err := os.Stat(path)
		if err != nil {
			return 0, err
		}
		total += st.Size()
	}
	return total, nil
}

func removeAll(paths []string) {
	for _, path := range paths {
		_ = os.Remove(path)
	}
}

func publishArchivePartNoReplace(tempPath, finalPath string) error {
	in, err := os.Open(tempPath)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(finalPath, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		if os.IsExist(err) {
			return fmt.Errorf("archive output already exists: %s", finalPath)
		}
		return err
	}
	keep := false
	defer func() {
		_ = out.Close()
		if !keep {
			_ = os.Remove(finalPath)
		}
	}()
	if _, err := io.Copy(out, in); err != nil {
		return err
	}
	if err := out.Sync(); err != nil {
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	keep = true
	return os.Remove(tempPath)
}
