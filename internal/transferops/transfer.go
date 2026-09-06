package transferops

import (
	"crypto/md5"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"bkx/internal/berror"
	"bkx/internal/models"
	"bkx/internal/runstore"
	"bkx/internal/validation"
)

var ErrCancelled = errors.New("transfer operation cancelled")

type Mapping struct {
	Source      string `json:"source"`
	Destination string `json:"destination"`
	SizeBytes   int64  `json:"size_bytes"`
}

type PlanStep struct {
	StepType      string         `json:"step_type"`
	Description   string         `json:"description"`
	Source        *string        `json:"source"`
	Destination   *string        `json:"destination"`
	FilesAffected *int64         `json:"files_affected"`
	BytesAffected *int64         `json:"bytes_affected"`
	Details       map[string]any `json:"details"`
	Warnings      []string       `json:"warnings"`
}

type Plan struct {
	Command        string     `json:"command"`
	DryRun         bool       `json:"dry_run"`
	Steps          []PlanStep `json:"steps"`
	TotalFiles     int64      `json:"total_files"`
	TotalBytes     int64      `json:"total_bytes"`
	TimingEstimate string     `json:"timing_estimate"`
	Warnings       []string   `json:"warnings"`
}

type ProgressEvent struct {
	CurrentFile      *string `json:"current_file"`
	FilesTransferred int64   `json:"files_transferred"`
	BytesTransferred int64   `json:"bytes_transferred"`
	TotalFiles       int64   `json:"total_files"`
	TotalBytes       int64   `json:"total_bytes"`
}

type Result struct {
	Success            bool     `json:"success"`
	Direction          string   `json:"direction"`
	DryRun             bool     `json:"dry_run"`
	FilesTransferred   int64    `json:"files_transferred"`
	BytesTransferred   int64    `json:"bytes_transferred"`
	Source             *string  `json:"source"`
	Destination        *string  `json:"destination"`
	VerificationPassed *bool    `json:"verification_passed"`
	ChecksumVerified   *bool    `json:"checksum_verified"`
	Warnings           []string `json:"warnings"`
	Errors             []string `json:"errors"`
	DurationMS         int64    `json:"duration_ms"`
}

type Options struct {
	RunStore  *runstore.Store
	RunID     string
	Cancelled func() bool
	Progress  func(ProgressEvent)
}

func ParseSpecJSON(raw string) (models.TransferSpec, error) {
	required := []string{"sources", "destination", "direction", "verify", "checksum", "dry_run", "overwrite"}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal([]byte(raw), &fields); err != nil {
		return models.TransferSpec{}, berror.Validation("Invalid TransferSpec JSON", map[string]any{"reason": err.Error()})
	}
	for _, key := range required {
		if _, ok := fields[key]; !ok {
			return models.TransferSpec{}, berror.Validation("Invalid TransferSpec JSON", map[string]any{"reason": "missing required field", "field": key})
		}
	}
	dec := json.NewDecoder(strings.NewReader(raw))
	dec.DisallowUnknownFields()
	var spec models.TransferSpec
	if err := dec.Decode(&spec); err != nil {
		return models.TransferSpec{}, berror.Validation("Invalid TransferSpec JSON", map[string]any{"reason": err.Error()})
	}
	var trailing any
	if err := dec.Decode(&trailing); !errors.Is(err, io.EOF) {
		return models.TransferSpec{}, berror.Validation("Invalid TransferSpec JSON", map[string]any{"reason": "trailing JSON data"})
	}
	return validation.ValidateTransferSpec(spec)
}

func Normalize(spec models.TransferSpec) (models.TransferSpec, error) {
	valid, err := validation.ValidateTransferSpec(spec)
	if err != nil {
		return models.TransferSpec{}, err
	}
	if validation.IsRemoteLocation(valid.Destination) {
		return models.TransferSpec{}, berror.Validation("GO09 local transfer supports only local Path sources and destinations", map[string]any{"destination": valid.Destination})
	}
	destination, err := validation.AbsoluteLocalPath(valid.Destination)
	if err != nil {
		return models.TransferSpec{}, err
	}
	sources := make([]string, 0, len(valid.Sources))
	for _, source := range valid.Sources {
		if validation.IsRemoteLocation(source) {
			return models.TransferSpec{}, berror.Validation("GO09 local transfer supports only local Path sources and destinations", map[string]any{"source": source})
		}
		canonical, err := validation.CanonicalLocalPath(source)
		if err != nil {
			return models.TransferSpec{}, err
		}
		if err := validateSource(canonical); err != nil {
			return models.TransferSpec{}, err
		}
		sources = append(sources, canonical)
	}
	if len(sources) > 1 {
		st, err := os.Stat(destination)
		if err != nil || !st.IsDir() {
			return models.TransferSpec{}, berror.Validation("Multiple local transfer sources require an existing directory destination", map[string]any{"destination": destination, "source_count": len(sources)})
		}
	}
	for _, source := range sources {
		st, _ := os.Stat(source)
		if source == destination || (st != nil && st.IsDir() && pathsOverlap(source, destination)) {
			return models.TransferSpec{}, berror.Validation("Transfer destination overlaps a source tree", map[string]any{"source": source, "destination": destination})
		}
	}
	valid.Sources = sources
	valid.Destination = destination
	return valid, nil
}

func PlanTransfer(spec models.TransferSpec) (Plan, error) {
	valid, err := Normalize(spec)
	if err != nil {
		return Plan{}, err
	}
	mappings, err := PlanMappings(valid)
	if err != nil {
		return Plan{}, err
	}
	files, bytes := totals(mappings)
	var source *string
	if len(valid.Sources) == 1 {
		value := valid.Sources[0]
		source = &value
	}
	dest := valid.Destination
	steps := []PlanStep{{
		StepType: "transfer", Description: fmt.Sprintf("%s to %s", valid.Direction, valid.Destination), Source: source, Destination: &dest,
		FilesAffected: int64ptr(files), BytesAffected: int64ptr(bytes), Details: map[string]any{"direction": valid.Direction, "source_count": len(valid.Sources)}, Warnings: []string{},
	}}
	if valid.Verify {
		steps = append(steps, PlanStep{StepType: "verify", Description: "Verify transfer integrity", Source: nil, Destination: &dest, FilesAffected: int64ptr(files), BytesAffected: int64ptr(bytes), Details: map[string]any{}, Warnings: []string{}})
	}
	return Plan{Command: "transfer " + valid.Direction, DryRun: true, Steps: steps, TotalFiles: files, TotalBytes: bytes, TimingEstimate: "unknown", Warnings: []string{}}, nil
}

func PlanMappings(spec models.TransferSpec) ([]Mapping, error) {
	valid, err := Normalize(spec)
	if err != nil {
		return nil, err
	}
	destination := valid.Destination
	multi := len(valid.Sources) > 1
	mappings := make([]Mapping, 0)
	for _, source := range valid.Sources {
		st, err := os.Stat(source)
		if err != nil {
			return nil, berror.WrapExternal("checking transfer source", source, "go-os", err)
		}
		if st.Mode().IsRegular() {
			target := destination
			if multi || isExistingDir(destination) {
				target = filepath.Join(destination, filepath.Base(source))
			}
			mappings = append(mappings, Mapping{Source: source, Destination: target, SizeBytes: st.Size()})
			continue
		}
		targetRoot := destination
		if multi || pathExists(destination) {
			targetRoot = filepath.Join(destination, filepath.Base(source))
		}
		err = filepath.WalkDir(source, func(path string, d os.DirEntry, walkErr error) error {
			if walkErr != nil {
				return walkErr
			}
			if err := validateSource(path); err != nil {
				if d.IsDir() && path != source {
					return err
				}
				return err
			}
			if d.IsDir() {
				return nil
			}
			info, err := d.Info()
			if err != nil {
				return err
			}
			rel, err := filepath.Rel(source, path)
			if err != nil {
				return err
			}
			mappings = append(mappings, Mapping{Source: path, Destination: filepath.Join(targetRoot, rel), SizeBytes: info.Size()})
			return nil
		})
		if err != nil {
			return nil, err
		}
	}
	sort.Slice(mappings, func(i, j int) bool {
		if mappings[i].Source == mappings[j].Source {
			return mappings[i].Destination < mappings[j].Destination
		}
		return mappings[i].Source < mappings[j].Source
	})
	if err := validateMappingSet(mappings, valid.Overwrite); err != nil {
		return nil, err
	}
	return mappings, nil
}

func Run(spec models.TransferSpec, options Options) (Result, error) {
	started := time.Now()
	valid, err := Normalize(spec)
	if err != nil {
		return Result{}, err
	}
	mappings, err := PlanMappings(valid)
	if err != nil {
		return Result{}, err
	}
	totalFiles, totalBytes := totals(mappings)
	first := valid.Sources[0]
	destination := valid.Destination
	if valid.DryRun {
		return Result{Success: true, Direction: valid.Direction, DryRun: true, FilesTransferred: totalFiles, BytesTransferred: totalBytes, Source: &first, Destination: &destination, Warnings: []string{"Dry run - transfer not executed"}, Errors: []string{}, DurationMS: time.Since(started).Milliseconds()}, nil
	}

	runID := options.RunID
	if runID == "" {
		runID = randomRunID()
	}
	if options.RunStore != nil {
		snapshot := snapshotSpec(valid)
		if _, err := options.RunStore.RecordStart(runID, "transfer", valid.Sources, &destination, nil, snapshot); err != nil {
			return Result{}, err
		}
	}
	complete := func(status string, files, bytes int64, opErr error) {
		if options.RunStore == nil {
			return
		}
		var msg *string
		if opErr != nil {
			text := opErr.Error()
			msg = &text
		}
		_, _ = options.RunStore.RecordCompletion(runID, status, int64ptr(files), int64ptr(bytes), msg)
	}

	var doneFiles, doneBytes int64
	for _, mapping := range mappings {
		if isCancelled(options) {
			complete("interrupted", doneFiles, doneBytes, ErrCancelled)
			return Result{}, ErrCancelled
		}
		if err := copyOne(mapping, valid, func(copied int64) {
			if options.Progress != nil {
				current := mapping.Source
				options.Progress(ProgressEvent{CurrentFile: &current, FilesTransferred: doneFiles, BytesTransferred: doneBytes + copied, TotalFiles: totalFiles, TotalBytes: totalBytes})
			}
		}, options); err != nil {
			status := "failed"
			if errors.Is(err, ErrCancelled) {
				status = "interrupted"
			}
			complete(status, doneFiles, doneBytes, err)
			return Result{}, err
		}
		doneFiles++
		doneBytes += mapping.SizeBytes
		if options.Progress != nil {
			current := mapping.Source
			options.Progress(ProgressEvent{CurrentFile: &current, FilesTransferred: doneFiles, BytesTransferred: doneBytes, TotalFiles: totalFiles, TotalBytes: totalBytes})
		}
	}
	verification := boolptrIf(valid.Verify, true)
	checksum := boolptrIf(valid.Verify || valid.Checksum != nil, true)
	result := Result{Success: true, Direction: valid.Direction, DryRun: false, FilesTransferred: doneFiles, BytesTransferred: doneBytes, Source: &first, Destination: &destination, VerificationPassed: verification, ChecksumVerified: checksum, Warnings: []string{}, Errors: []string{}, DurationMS: time.Since(started).Milliseconds()}
	complete("success", doneFiles, doneBytes, nil)
	return result, nil
}

func copyOne(mapping Mapping, spec models.TransferSpec, progress func(int64), options Options) error {
	if err := ensureNoDestinationSymlink(mapping.Destination); err != nil {
		return err
	}
	if !spec.Overwrite {
		if _, err := os.Lstat(mapping.Destination); err == nil {
			return berror.Validation("Transfer destination already exists and overwrite is disabled", map[string]any{"destination": mapping.Destination})
		} else if !os.IsNotExist(err) {
			return berror.WrapExternal("checking transfer destination", mapping.Destination, "go-os", err)
		}
	}
	if err := os.MkdirAll(filepath.Dir(mapping.Destination), 0o755); err != nil {
		return berror.WrapExternal("creating transfer destination directory", filepath.Dir(mapping.Destination), "go-os", err)
	}
	in, err := os.Open(mapping.Source)
	if err != nil {
		return berror.WrapExternal("opening transfer source", mapping.Source, "go-os", err)
	}
	defer in.Close()
	out, err := os.CreateTemp(filepath.Dir(mapping.Destination), "."+filepath.Base(mapping.Destination)+".*.bkx-copy")
	if err != nil {
		return berror.WrapExternal("creating transfer staging file", mapping.Destination, "go-os", err)
	}
	tmp := out.Name()
	published := false
	defer func() {
		_ = out.Close()
		if !published {
			_ = os.Remove(tmp)
		}
	}()
	info, err := os.Stat(mapping.Source)
	if err != nil {
		return berror.WrapExternal("stating transfer source", mapping.Source, "go-os", err)
	}
	_ = out.Chmod(info.Mode().Perm())
	buf := make([]byte, 1024*1024)
	var copied int64
	for {
		if isCancelled(options) {
			return ErrCancelled
		}
		n, readErr := in.Read(buf)
		if n > 0 {
			if _, err := out.Write(buf[:n]); err != nil {
				return berror.WrapExternal("writing transfer staging file", tmp, "go-os", err)
			}
			copied += int64(n)
			if progress != nil {
				progress(copied)
			}
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
		if readErr != nil {
			return berror.WrapExternal("reading transfer source", mapping.Source, "go-os", readErr)
		}
	}
	if err := out.Sync(); err != nil {
		return berror.WrapExternal("syncing transfer staging file", tmp, "go-os", err)
	}
	if err := out.Close(); err != nil {
		return berror.WrapExternal("closing transfer staging file", tmp, "go-os", err)
	}
	if err := os.Chtimes(tmp, info.ModTime(), info.ModTime()); err != nil {
		return berror.WrapExternal("preserving transfer timestamp", tmp, "go-os", err)
	}
	if spec.Verify || spec.Checksum != nil {
		if err := verifyCopy(mapping.Source, tmp, spec.Checksum); err != nil {
			return err
		}
	}
	if isCancelled(options) {
		return ErrCancelled
	}
	if spec.Overwrite {
		if err := os.Rename(tmp, mapping.Destination); err != nil {
			return berror.WrapExternal("publishing transfer", mapping.Destination, "go-os", err)
		}
	} else if err := publishNoReplace(tmp, mapping.Destination); err != nil {
		return err
	}
	published = true
	_ = syncDir(filepath.Dir(mapping.Destination))
	return nil
}

func verifyCopy(source, destination string, checksum *string) error {
	algorithm := "sha256"
	var expected string
	if checksum != nil {
		candidate := strings.ToLower(strings.TrimSpace(*checksum))
		switch candidate {
		case "md5", "sha1", "sha224", "sha256", "sha384", "sha512":
			algorithm = candidate
		default:
			expected = candidate
		}
	}
	src, err := fileDigest(source, algorithm)
	if err != nil {
		return err
	}
	dst, err := fileDigest(destination, algorithm)
	if err != nil {
		return err
	}
	if src != dst {
		return berror.Validation("Transfer verification failed", map[string]any{"source": source, "destination": destination, "algorithm": algorithm})
	}
	if expected != "" && strings.ToLower(dst) != expected {
		return berror.Validation("Transfer checksum did not match expected value", map[string]any{"destination": destination, "algorithm": algorithm})
	}
	return nil
}

func fileDigest(path, algorithm string) (string, error) {
	var h interface {
		Write([]byte) (int, error)
		Sum([]byte) []byte
	}
	switch algorithm {
	case "md5":
		h = md5.New()
	case "sha1":
		h = sha1.New()
	case "sha224":
		h = sha256.New224()
	case "sha256":
		h = sha256.New()
	case "sha384":
		h = sha512.New384()
	case "sha512":
		h = sha512.New()
	default:
		return "", berror.Validation("Unsupported transfer hash algorithm", map[string]any{"algorithm": algorithm})
	}
	f, err := os.Open(path)
	if err != nil {
		return "", berror.WrapExternal("hashing transfer file", path, "go-stdlib", err)
	}
	defer f.Close()
	if _, err := io.Copy(h.(io.Writer), f); err != nil {
		return "", berror.WrapExternal("hashing transfer file", path, "go-stdlib", err)
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func validateSource(path string) error {
	st, err := os.Lstat(path)
	if err != nil {
		if os.IsNotExist(err) {
			return berror.NotFound("Transfer source does not exist: "+path, map[string]any{"path": path})
		}
		return berror.WrapExternal("checking transfer source", path, "go-os", err)
	}
	if st.Mode()&os.ModeSymlink != 0 {
		return berror.Validation("Transfer refuses symlink sources", map[string]any{"path": path})
	}
	if st.Mode().IsRegular() || st.IsDir() {
		return nil
	}
	kind := "special"
	if st.Mode()&os.ModeNamedPipe != 0 {
		kind = "fifo"
	}
	if st.Mode()&os.ModeSocket != 0 {
		kind = "socket"
	}
	if st.Mode()&os.ModeDevice != 0 {
		kind = "device"
	}
	return berror.Validation("Transfer refuses unsupported special files", map[string]any{"path": path, "kind": kind})
}

func validateMappingSet(mappings []Mapping, overwrite bool) error {
	seen := map[string]string{}
	for _, mapping := range mappings {
		target, err := validation.AbsoluteLocalPath(mapping.Destination)
		if err != nil {
			return err
		}
		for _, other := range mappings {
			if mapping.Source != other.Source && pathsOverlap(mapping.Source, other.Destination) {
				return berror.Validation("Transfer source overlaps a destination", map[string]any{"source": mapping.Source, "destination": other.Destination})
			}
		}
		if previous, ok := seen[target]; ok && previous != mapping.Source {
			return berror.Validation("Multiple transfer sources map to the same destination", map[string]any{"destination": target, "first": previous, "second": mapping.Source})
		}
		seen[target] = mapping.Source
		if err := ensureNoDestinationSymlink(target); err != nil {
			return err
		}
		if _, err := os.Lstat(target); err == nil && !overwrite {
			return berror.Validation("Transfer destination already exists and overwrite is disabled", map[string]any{"destination": target})
		} else if err != nil && !os.IsNotExist(err) {
			return berror.WrapExternal("checking transfer destination", target, "go-os", err)
		}
	}
	return nil
}

func ensureNoDestinationSymlink(path string) error {
	abs, err := validation.AbsoluteLocalPath(path)
	if err != nil {
		return err
	}
	current := string(filepath.Separator)
	parts := strings.Split(filepath.Clean(abs), string(filepath.Separator))
	for _, part := range parts {
		if part == "" {
			continue
		}
		current = filepath.Join(current, part)
		st, err := os.Lstat(current)
		if err != nil {
			if os.IsNotExist(err) {
				continue
			}
			return berror.WrapExternal("checking transfer destination", current, "go-os", err)
		}
		if st.Mode()&os.ModeSymlink != 0 {
			return berror.Validation("Transfer refuses to write through a destination symlink", map[string]any{"path": current})
		}
	}
	return nil
}

func publishNoReplace(tmp, dst string) error { return publishNoReplaceWith(tmp, dst, renameNoReplace) }

func publishNoReplaceWith(tmp, dst string, renameFn func(string, string) error) error {
	err := renameFn(tmp, dst)
	if err == nil {
		return nil
	}
	if errors.Is(err, syscall.EEXIST) {
		return berror.Validation("Transfer destination already exists and overwrite is disabled", map[string]any{"destination": dst})
	}
	if errors.Is(err, syscall.ENOSYS) || errors.Is(err, syscall.EINVAL) || errors.Is(err, syscall.EOPNOTSUPP) || errors.Is(err, syscall.EPERM) {
		return copyNoReplace(tmp, dst)
	}
	return berror.WrapExternal("publishing transfer", dst, "go-os", err)
}

func copyNoReplace(tmp, dst string) error {
	in, err := os.Open(tmp)
	if err != nil {
		return berror.WrapExternal("opening transfer staging file", tmp, "go-os", err)
	}
	defer in.Close()
	info, statErr := os.Stat(tmp)
	if statErr != nil {
		return berror.WrapExternal("stating transfer staging file", tmp, "go-os", statErr)
	}
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_EXCL, info.Mode().Perm())
	if err != nil {
		if os.IsExist(err) {
			return berror.Validation("Transfer destination already exists and overwrite is disabled", map[string]any{"destination": dst})
		}
		return berror.WrapExternal("creating transfer destination", dst, "go-os", err)
	}
	keep := false
	defer func() {
		_ = out.Close()
		if !keep {
			_ = os.Remove(dst)
		}
	}()
	if _, err := io.Copy(out, in); err != nil {
		return berror.WrapExternal("copying transfer staging file", dst, "go-os", err)
	}
	if err := os.Chtimes(dst, info.ModTime(), info.ModTime()); err != nil {
		return berror.WrapExternal("preserving transfer timestamp", dst, "go-os", err)
	}
	if err := out.Sync(); err != nil {
		return berror.WrapExternal("syncing transfer destination", dst, "go-os", err)
	}
	if err := out.Close(); err != nil {
		return berror.WrapExternal("closing transfer destination", dst, "go-os", err)
	}
	keep = true
	_ = os.Remove(tmp)
	return nil
}

func renameNoReplace(oldPath, newPath string) error {
	oldPtr, err := syscall.BytePtrFromString(oldPath)
	if err != nil {
		return err
	}
	newPtr, err := syscall.BytePtrFromString(newPath)
	if err != nil {
		return err
	}
	var syscallNumber uintptr
	switch runtime.GOARCH {
	case "amd64":
		syscallNumber = 316
	case "arm64", "riscv64":
		syscallNumber = 276
	case "386":
		syscallNumber = 353
	case "arm":
		syscallNumber = 382
	default:
		return syscall.ENOSYS
	}
	const renameNoReplaceFlag = 1
	atFDCWD := ^uintptr(99)
	_, _, errno := syscall.Syscall6(syscallNumber, atFDCWD, uintptr(unsafe.Pointer(oldPtr)), atFDCWD, uintptr(unsafe.Pointer(newPtr)), renameNoReplaceFlag, 0)
	if errno != 0 {
		return errno
	}
	return nil
}

func pathsOverlap(a, b string) bool {
	aa, _ := filepath.Abs(a)
	bb, _ := filepath.Abs(b)
	aa = filepath.Clean(aa)
	bb = filepath.Clean(bb)
	if aa == bb {
		return true
	}
	rel, err := filepath.Rel(aa, bb)
	if err == nil && rel != "." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && rel != ".." {
		return true
	}
	rel, err = filepath.Rel(bb, aa)
	return err == nil && rel != "." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && rel != ".."
}

func totals(mappings []Mapping) (int64, int64) {
	var bytes int64
	for _, m := range mappings {
		bytes += m.SizeBytes
	}
	return int64(len(mappings)), bytes
}
func pathExists(path string) bool      { _, err := os.Stat(path); return err == nil }
func isExistingDir(path string) bool   { st, err := os.Stat(path); return err == nil && st.IsDir() }
func isCancelled(options Options) bool { return options.Cancelled != nil && options.Cancelled() }
func int64ptr(v int64) *int64          { return &v }
func boolptrIf(include bool, value bool) *bool {
	if !include {
		return nil
	}
	v := value
	return &v
}
func randomRunID() string {
	b := make([]byte, 8)
	if _, err := rand.Read(b); err == nil {
		return "go09-" + hex.EncodeToString(b)
	}
	return fmt.Sprintf("go09-%d", time.Now().UnixNano())
}
func syncDir(path string) error {
	d, err := os.Open(path)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}
func snapshotSpec(spec models.TransferSpec) map[string]any {
	return map[string]any{"type": "TransferSpec", "values": map[string]any{"sources": append([]string(nil), spec.Sources...), "destination": spec.Destination, "direction": spec.Direction, "verify": spec.Verify, "checksum": spec.Checksum, "dry_run": spec.DryRun, "overwrite": spec.Overwrite}}
}
