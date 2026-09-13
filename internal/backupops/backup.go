package backupops

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"bkx/internal/archiveio"
	"bkx/internal/berror"
	"bkx/internal/hashing"
	"bkx/internal/models"
	"bkx/internal/rcloneops"
	"bkx/internal/remotearchive"
	"bkx/internal/runstore"
	"bkx/internal/validation"
)

var ErrCancelled = errors.New("backup operation cancelled")

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
	Stage string `json:"stage"`
	Files int64  `json:"files"`
	Bytes int64  `json:"bytes"`
}

type Result struct {
	Success            bool              `json:"success"`
	DryRun             bool              `json:"dry_run"`
	FilesProcessed     int64             `json:"files_processed"`
	BytesWritten       int64             `json:"bytes_written"`
	ArchivesCreated    int64             `json:"archives_created"`
	ArchivePaths       []string          `json:"archive_paths"`
	VerificationPassed *bool             `json:"verification_passed"`
	Warnings           []string          `json:"warnings"`
	Errors             []string          `json:"errors"`
	StepResults        map[string]bool   `json:"step_results"`
	Checksums          map[string]string `json:"checksums"`
	DurationMS         int64             `json:"duration_ms"`
}

type Options struct {
	RunStore  *runstore.Store
	RunID     string
	RunOrigin *runstore.SavedJobOrigin
	Cancelled func() bool
	Progress  func(ProgressEvent)
}

type normalizedBackup struct {
	Spec        models.BackupSpec
	Destination *string
	ArchiveName string
	ArchivePath *string
}

func ParseSpecJSON(raw string) (models.BackupSpec, error) {
	required := []string{"sources", "destination", "archive_name", "excludes", "includes", "split_size", "compression", "skip_symlinks", "verify", "dry_run"}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal([]byte(raw), &fields); err != nil {
		return models.BackupSpec{}, berror.Validation("Invalid BackupSpec JSON", map[string]any{"reason": err.Error()})
	}
	for _, key := range required {
		if _, ok := fields[key]; !ok {
			return models.BackupSpec{}, berror.Validation("Invalid BackupSpec JSON", map[string]any{"reason": "missing required field", "field": key})
		}
	}
	dec := json.NewDecoder(strings.NewReader(raw))
	dec.DisallowUnknownFields()
	var spec models.BackupSpec
	if err := dec.Decode(&spec); err != nil {
		return models.BackupSpec{}, berror.Validation("Invalid BackupSpec JSON", map[string]any{"reason": err.Error()})
	}
	var trailing any
	if err := dec.Decode(&trailing); !errors.Is(err, io.EOF) {
		return models.BackupSpec{}, berror.Validation("Invalid BackupSpec JSON", map[string]any{"reason": "trailing JSON data"})
	}
	return validation.ValidateBackupSpec(spec)
}

func Normalize(spec models.BackupSpec) (models.BackupSpec, *string, string, *string, error) {
	valid, err := validation.ValidateBackupSpec(spec)
	if err != nil {
		return models.BackupSpec{}, nil, "", nil, err
	}
	sources := make([]string, 0, len(valid.Sources))
	seen := map[string]bool{}
	for _, source := range valid.Sources {
		canonical, err := validation.RequireSafeLocalReadPath(source)
		if err != nil {
			return models.BackupSpec{}, nil, "", nil, err
		}
		st, err := os.Stat(canonical)
		if err != nil {
			if os.IsNotExist(err) {
				return models.BackupSpec{}, nil, "", nil, berror.NotFound("Source path does not exist: "+canonical, map[string]any{"path": canonical})
			}
			return models.BackupSpec{}, nil, "", nil, berror.WrapExternal("checking backup source", canonical, "go-os", err)
		}
		if !st.Mode().IsRegular() && !st.IsDir() {
			return models.BackupSpec{}, nil, "", nil, berror.Validation("Source path must be a regular file or directory: "+canonical, map[string]any{"path": canonical})
		}
		if seen[canonical] {
			return models.BackupSpec{}, nil, "", nil, berror.Validation("Duplicate source paths are not allowed", map[string]any{"sources": valid.Sources})
		}
		seen[canonical] = true
		sources = append(sources, canonical)
	}
	valid.Sources = sources

	var destination *string
	if valid.Destination != nil {
		raw := strings.TrimSpace(*valid.Destination)
		var canonical string
		if rcloneops.IsLocation(raw) {
			canonical, err = rcloneops.CanonicalURI(raw)
		} else {
			if validation.IsRemoteLocation(raw) {
				return models.BackupSpec{}, nil, "", nil, berror.Validation("Unsupported backup remote location; use rclone://remote/path", map[string]any{"destination": raw})
			}
			canonical, err = validation.RequireSafeLocalWriteDestination(raw)
		}
		if err != nil {
			return models.BackupSpec{}, nil, "", nil, err
		}
		destination = &canonical
		valid.Destination = &canonical
	}

	name := ""
	if valid.ArchiveName != nil {
		name = *valid.ArchiveName
	} else {
		prefix := filepath.Base(sources[0])
		if prefix == "." || prefix == string(filepath.Separator) || prefix == "" {
			prefix = "backup"
		}
		name = validation.DeterministicName(prefix, sources...)
		valid.ArchiveName = &name
	}

	var archivePath *string
	if destination != nil {
		var canonical string
		if rcloneops.IsLocation(*destination) {
			canonical, err = rcloneops.JoinURI(*destination, name+".bakery")
		} else {
			path := filepath.Join(*destination, name+".bakery")
			canonical, err = validation.RequireSafeLocalWriteDestination(path)
			if err == nil {
				for _, source := range sources {
					if overlapsBackupOutput(source, canonical) {
						return models.BackupSpec{}, nil, "", nil, berror.Validation("Output/destination must not overlap a source tree", map[string]any{"source": source, "output": canonical})
					}
				}
			}
		}
		if err != nil {
			return models.BackupSpec{}, nil, "", nil, err
		}
		archivePath = &canonical
	}
	return valid, destination, name, archivePath, nil
}

func PlanBackup(spec models.BackupSpec) (Plan, error) {
	valid, destination, name, archivePath, err := Normalize(spec)
	if err != nil {
		return Plan{}, err
	}
	files, bytes, err := measureSources(valid.Sources)
	if err != nil {
		return Plan{}, err
	}
	if archivePath != nil && !rcloneops.IsLocation(*archivePath) && bytes > 0 {
		if err := validation.EnsureCapacityForWriteDestination(*archivePath, bytes); err != nil {
			return Plan{}, err
		}
	}
	outputName := "(resolved by backend)"
	if archivePath != nil {
		outputName = rcloneops.BaseName(*archivePath)
	}
	var source *string
	if len(valid.Sources) == 1 {
		s := valid.Sources[0]
		source = &s
	}
	archiveStep := PlanStep{
		StepType: "archive", Description: "Create archive " + outputName, Source: source, Destination: archivePath,
		FilesAffected: int64ptr(files), BytesAffected: int64ptr(bytes),
		Details: map[string]any{"compression": valid.Compression.Algorithm, "split_size": valid.SplitSize}, Warnings: []string{},
	}
	steps := []PlanStep{archiveStep}
	if destination != nil {
		steps = append(steps, PlanStep{
			StepType: "transfer", Description: "Transfer archive to " + *destination, Source: archivePath, Destination: destination,
			FilesAffected: int64ptr(1), BytesAffected: nil, Details: map[string]any{}, Warnings: []string{},
		})
	}
	if valid.Verify {
		steps = append(steps, PlanStep{
			StepType: "verify", Description: "Verify backup archive integrity", Source: archivePath, Destination: nil,
			FilesAffected: int64ptr(1), BytesAffected: nil, Details: map[string]any{"requested": true}, Warnings: []string{},
		})
	}
	_ = name
	return Plan{Command: "backup create", DryRun: true, Steps: steps, TotalFiles: files, TotalBytes: bytes, TimingEstimate: "unknown", Warnings: []string{}}, nil
}

func Create(spec models.BackupSpec, implicitDefault bool, options Options) (Result, error) {
	started := time.Now()
	valid, destination, name, archivePath, err := Normalize(spec)
	if err != nil {
		return Result{}, err
	}
	if valid.DryRun {
		steps := map[string]bool{"archive": true, "transfer": destination != nil, "verify": valid.Verify}
		return Result{Success: true, DryRun: true, ArchivePaths: []string{}, Warnings: []string{}, Errors: []string{}, StepResults: steps, Checksums: map[string]string{}, DurationMS: time.Since(started).Milliseconds()}, nil
	}

	runID := options.RunID
	if runID == "" {
		runID = randomRunID()
	}
	options.RunID = runID
	if options.RunStore != nil {
		snapshot := snapshotSpec(valid, destination, archivePath)
		_, err = options.RunStore.RecordStartWithOrigin(runID, "backup", valid.Sources, destination, archivePath, snapshot, options.RunOrigin)
		if err != nil {
			return Result{}, err
		}
		_, _ = options.RunStore.RecordPhase(runID, "preparing")
	}
	complete := func(status string, result *Result, opErr error) {
		if options.RunStore == nil {
			return
		}
		var files, bytes *int64
		var msg *string
		if result != nil {
			files = int64ptr(result.FilesProcessed)
			bytes = int64ptr(result.BytesWritten)
			if len(result.Errors) > 0 {
				joined := strings.Join(result.Errors, "; ")
				msg = &joined
			}
		}
		if opErr != nil {
			text := opErr.Error()
			msg = &text
		}
		_, _ = options.RunStore.RecordCompletion(runID, status, files, bytes, msg)
	}

	if isCancelled(options) {
		complete("cancelled", nil, ErrCancelled)
		return Result{}, ErrCancelled
	}

	var stagingDir string
	if destination != nil {
		stagingDir, err = os.MkdirTemp("", "bkx-backup-stage-")
		if err != nil {
			complete("failed", nil, err)
			return Result{}, berror.WrapExternal("creating backup staging directory", "", "go-os", err)
		}
		defer os.RemoveAll(stagingDir)
	}
	output := name + ".bakery"
	if stagingDir != "" {
		output = filepath.Join(stagingDir, output)
	} else if archivePath != nil {
		output = *archivePath
	}

	archiveSpec := models.ArchiveCreateSpec{
		Sources: valid.Sources, OutputPath: output, Includes: valid.Includes, Excludes: valid.Excludes,
		SplitSize: valid.SplitSize, Compression: valid.Compression, Verify: valid.Verify, DryRun: false, SkipSymlinks: valid.SkipSymlinks,
	}
	emit(options, "archiving", 0, 0)
	archiveResult, err := archiveio.Create(archiveSpec, implicitDefault, func(files int, bytes int64) {
		emit(options, "archiving", int64(files), bytes)
	})
	if err != nil {
		complete("failed", nil, err)
		return Result{}, err
	}
	archiveParts := []string{output}
	if len(archiveResult.SplitParts) > 0 {
		archiveParts = append([]string(nil), archiveResult.SplitParts...)
	}
	if isCancelled(options) {
		if destination == nil {
			for _, part := range archiveParts {
				_ = os.Remove(part)
			}
		}
		complete("cancelled", nil, ErrCancelled)
		return Result{}, ErrCancelled
	}

	steps := map[string]bool{"archive": archiveResult.Success}
	warnings := append([]string{}, archiveResult.Warnings...)
	if valid.Verify {
		ok := archiveResult.VerificationPassed != nil && *archiveResult.VerificationPassed
		steps["verify_archive"] = ok
		if !ok {
			result := failedResult(started, steps, warnings, []string{"Archive verification did not pass"}, int64(archiveResult.FilesArchived), archiveResult.BytesWritten, []string{output})
			complete("failed", &result, nil)
			return result, nil
		}
	}

	logicalHash, err := hashing.Files(archiveParts, "sha256")
	if err != nil {
		complete("failed", nil, err)
		return Result{}, err
	}
	checksums := map[string]string{filepath.Base(output): logicalHash.Checksum, "archive": logicalHash.Checksum}
	for i, part := range archiveParts {
		if i == 0 {
			continue // base name represents the logical concatenated archive digest
		}
		partHash, hashErr := hashing.File(part, "sha256")
		if hashErr != nil {
			complete("failed", nil, hashErr)
			return Result{}, hashErr
		}
		checksums[filepath.Base(part)] = partHash.Checksum
	}
	finalPaths := []string{output}
	transferredBytes := int64(0)
	emit(options, "publishing", 0, 0)
	if destination != nil && rcloneops.IsLocation(*destination) {
		final := *archivePath
		runtime, runtimeErr := rcloneops.RuntimeFromEnvironment()
		if runtimeErr != nil {
			complete("failed", nil, runtimeErr)
			return Result{}, runtimeErr
		}
		finalParts := make([]string, len(archiveParts))
		for i, part := range archiveParts {
			suffix := strings.TrimPrefix(part, output)
			if i == 0 {
				suffix = ""
			}
			finalParts[i] = final + suffix
		}
		for _, candidate := range finalParts {
			exists, existsErr := runtime.Exists(context.Background(), candidate)
			if existsErr != nil {
				complete("failed", nil, existsErr)
				return Result{}, existsErr
			}
			if exists {
				err = berror.Validation("Remote backup destination already exists", map[string]any{"destination": candidate})
				complete("failed", nil, err)
				return Result{}, err
			}
		}
		order := make([]int, 0, len(archiveParts))
		for i := 1; i < len(archiveParts); i++ {
			order = append(order, i)
		}
		order = append(order, 0)
		published := make([]string, 0, len(order))
		rollbackRemote := func() {
			for _, remotePath := range published {
				_ = runtime.DeleteFile(context.Background(), remotePath)
			}
		}
		for _, i := range order {
			if isCancelled(options) {
				rollbackRemote()
				complete("cancelled", nil, ErrCancelled)
				return Result{}, ErrCancelled
			}
			partResult, copyErr := runtime.CopyTo(context.Background(), archiveParts[i], finalParts[i], rcloneops.TransferOptions{
				Overwrite: false, Verify: valid.Verify, Cancelled: options.Cancelled,
				Progress: func(p rcloneops.Progress) { emit(options, "publishing", p.FilesDone, transferredBytes+p.BytesDone) },
			})
			if copyErr != nil {
				rollbackRemote()
				complete("failed", nil, copyErr)
				return Result{}, copyErr
			}
			transferredBytes += partResult.Bytes
			published = append(published, finalParts[i])
		}
		steps["transfer"] = true
		finalPaths = []string{final}
		if valid.Verify {
			materialized, acquireErr := remotearchive.Acquire(context.Background(), final, remotearchive.Options{Cancelled: options.Cancelled})
			if acquireErr != nil {
				rollbackRemote()
				complete("failed", nil, acquireErr)
				return Result{}, acquireErr
			}
			verifyResult, verifyErr := archiveio.Verify(materialized.Path, []string{"sha256"})
			_ = materialized.Cleanup()
			if verifyErr != nil || !verifyResult.Success || !strings.EqualFold(verifyResult.ArchiveChecksums["sha256"], logicalHash.Checksum) {
				rollbackRemote()
				if verifyErr != nil {
					complete("failed", nil, verifyErr)
					return Result{}, verifyErr
				}
				err = berror.Validation("Remote archive verification failed after upload", map[string]any{"archive_path": final})
				complete("failed", nil, err)
				return Result{}, err
			}
			steps["verify_transfer"] = true
		}
	} else if destination != nil {
		final := filepath.Join(*destination, filepath.Base(output))
		if archivePath != nil {
			final = *archivePath
		}
		if isCancelled(options) {
			complete("cancelled", nil, ErrCancelled)
			return Result{}, ErrCancelled
		}

		finalParts := make([]string, len(archiveParts))
		for i, part := range archiveParts {
			suffix := strings.TrimPrefix(part, output)
			if i == 0 {
				suffix = ""
			}
			finalParts[i] = final + suffix
		}
		for _, candidate := range finalParts {
			if _, statErr := os.Lstat(candidate); statErr == nil {
				err = berror.Validation("Transfer destination already exists", map[string]any{"destination": candidate})
				complete("failed", nil, err)
				return Result{}, err
			} else if !os.IsNotExist(statErr) {
				err = berror.WrapExternal("checking transfer destination", candidate, "go-os", statErr)
				complete("failed", nil, err)
				return Result{}, err
			}
		}

		// Publish sidecars first and the logical base last so archive discovery
		// never observes an incomplete split archive.
		order := make([]int, 0, len(archiveParts))
		for i := 1; i < len(archiveParts); i++ {
			order = append(order, i)
		}
		order = append(order, 0)
		published := make([]string, 0, len(order))
		for _, i := range order {
			if isCancelled(options) {
				for _, path := range published {
					_ = os.Remove(path)
				}
				complete("cancelled", nil, ErrCancelled)
				return Result{}, ErrCancelled
			}
			partBytes, publishErr := publishFile(archiveParts[i], finalParts[i], valid.Verify, func(bytes int64) {
				emit(options, "publishing", 1, transferredBytes+bytes)
			})
			if publishErr != nil {
				for _, path := range published {
					_ = os.Remove(path)
				}
				complete("failed", nil, publishErr)
				return Result{}, publishErr
			}
			transferredBytes += partBytes
			published = append(published, finalParts[i])
		}
		steps["transfer"] = true
		finalPaths = []string{final}
		rollbackPublished := func() {
			for _, path := range published {
				_ = os.Remove(path)
			}
		}
		if valid.Verify {
			verifyResult, verifyErr := archiveio.Verify(final, []string{"sha256"})
			if verifyErr != nil {
				rollbackPublished()
				complete("failed", nil, verifyErr)
				return Result{}, verifyErr
			}
			if !verifyResult.Success || !strings.EqualFold(verifyResult.ArchiveChecksums["sha256"], logicalHash.Checksum) {
				rollbackPublished()
				err = berror.Validation("Transferred split archive verification failed", map[string]any{"archive_path": final})
				complete("failed", nil, err)
				return Result{}, err
			}
			steps["verify_transfer"] = true
		}
	} else {
		steps["transfer"] = true
		if valid.Verify {
			steps["verify_transfer"] = true
		}
	}

	ok := true
	result := Result{
		Success: true, DryRun: false, FilesProcessed: int64(archiveResult.FilesArchived),
		BytesWritten: archiveResult.BytesWritten + transferredBytes, ArchivesCreated: int64(len(finalPaths)), ArchivePaths: finalPaths,
		VerificationPassed: boolptrIf(valid.Verify, ok), Warnings: warnings, Errors: []string{}, StepResults: steps, Checksums: checksums,
		DurationMS: time.Since(started).Milliseconds(),
	}
	emit(options, "complete", result.FilesProcessed, result.BytesWritten)
	complete("success", &result, nil)
	return result, nil
}

func publishFile(source, destination string, verify bool, progress func(int64)) (int64, error) {
	src, err := validation.CanonicalLocalPath(source)
	if err != nil {
		return 0, err
	}
	dst, err := validation.AbsoluteLocalPath(destination)
	if err != nil {
		return 0, err
	}
	if err := ensureNoDestinationSymlink(dst); err != nil {
		return 0, err
	}
	if _, err := os.Lstat(dst); err == nil {
		return 0, berror.Validation("Transfer destination already exists", map[string]any{"destination": dst})
	} else if !os.IsNotExist(err) {
		return 0, berror.WrapExternal("checking transfer destination", dst, "go-os", err)
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return 0, berror.WrapExternal("creating transfer destination directory", filepath.Dir(dst), "go-os", err)
	}
	in, err := os.Open(src)
	if err != nil {
		return 0, berror.WrapExternal("opening transfer source", src, "go-os", err)
	}
	defer in.Close()
	out, err := os.CreateTemp(filepath.Dir(dst), "."+filepath.Base(dst)+".*.tmp")
	if err != nil {
		return 0, berror.WrapExternal("creating transfer staging file", dst, "go-os", err)
	}
	tmp := out.Name()
	published := false
	defer func() {
		_ = out.Close()
		if !published {
			_ = os.Remove(tmp)
		}
	}()
	if err := out.Chmod(0o600); err != nil {
		return 0, err
	}
	buf := make([]byte, 1024*1024)
	var total int64
	for {
		n, readErr := in.Read(buf)
		if n > 0 {
			if _, err := out.Write(buf[:n]); err != nil {
				return 0, berror.WrapExternal("writing transfer staging file", tmp, "go-os", err)
			}
			total += int64(n)
			if progress != nil {
				progress(total)
			}
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
		if readErr != nil {
			return 0, berror.WrapExternal("reading transfer source", src, "go-os", readErr)
		}
	}
	if err := out.Sync(); err != nil {
		return 0, err
	}
	if err := out.Close(); err != nil {
		return 0, err
	}
	if verify {
		a, err := hashing.File(src, "sha256")
		if err != nil {
			return 0, err
		}
		b, err := hashing.File(tmp, "sha256")
		if err != nil {
			return 0, err
		}
		if a.Checksum != b.Checksum {
			return 0, berror.Validation("Transfer verification failed", map[string]any{"source": src, "destination": dst, "algorithm": "sha256"})
		}
	}
	// Prefer renameat2(RENAME_NOREPLACE): unlike os.Rename it cannot clobber a
	// destination created after the preflight check, and unlike hard-link based
	// publication it works on Android/Termux filesystems which deny link(2).
	// If the syscall/flag is unavailable or blocked, fall back to an O_EXCL
	// copy from the already-fsynced staging file. The fallback is non-clobbering
	// and removes a partial destination on copy/sync failure.
	if err := publishNoReplace(tmp, dst); err != nil {
		return 0, err
	}
	published = true
	_ = syncDir(filepath.Dir(dst))
	return total, nil
}

func publishNoReplace(tmp, dst string) error {
	return publishNoReplaceWith(tmp, dst, renameNoReplace)
}

func publishNoReplaceWith(tmp, dst string, renameFn func(string, string) error) error {
	err := renameFn(tmp, dst)
	if err == nil {
		return nil
	}
	if errors.Is(err, syscall.EEXIST) {
		return berror.Validation("Transfer destination already exists", map[string]any{"destination": dst})
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

	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		if os.IsExist(err) {
			return berror.Validation("Transfer destination already exists", map[string]any{"destination": dst})
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

	// Linux syscall numbers. Android/arm64 uses the Linux arm64 ABI.
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
	atFDCWD := ^uintptr(99) // -100 represented as uintptr.
	_, _, errno := syscall.Syscall6(
		syscallNumber,
		atFDCWD, uintptr(unsafe.Pointer(oldPtr)),
		atFDCWD, uintptr(unsafe.Pointer(newPtr)),
		renameNoReplaceFlag, 0,
	)
	if errno != 0 {
		return errno
	}
	return nil
}

func ensureNoDestinationSymlink(path string) error {
	current := string(filepath.Separator)
	if !filepath.IsAbs(path) {
		cwd, _ := os.Getwd()
		current = cwd
	}
	parts := strings.Split(filepath.Clean(path), string(filepath.Separator))
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

func measureSources(sources []string) (int64, int64, error) {
	var files, bytes int64
	for _, source := range sources {
		st, err := os.Stat(source)
		if err != nil {
			return 0, 0, berror.WrapExternal("measuring backup source", source, "go-os", err)
		}
		if st.Mode().IsRegular() {
			files++
			bytes += st.Size()
			continue
		}
		err = filepath.WalkDir(source, func(path string, d os.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if path == source {
				return nil
			}
			info, err := os.Stat(path)
			if err != nil {
				return err
			}
			if info.Mode().IsRegular() {
				files++
				bytes += info.Size()
			}
			if d.Type()&os.ModeSymlink != 0 && d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		})
		if err != nil {
			return 0, 0, berror.WrapExternal("measuring backup source", source, "go-os", err)
		}
	}
	return files, bytes, nil
}

func overlapsBackupOutput(source, output string) bool {
	if source == output {
		return true
	}
	st, err := os.Stat(source)
	if err != nil || !st.IsDir() {
		return false
	}
	rel, err := filepath.Rel(source, output)
	return err == nil && rel != "." && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}

func snapshotSpec(spec models.BackupSpec, destination, archivePath *string) map[string]any {
	values := map[string]any{
		"sources": spec.Sources, "destination": destination, "archive_name": spec.ArchiveName,
		"excludes": spec.Excludes, "includes": spec.Includes, "split_size": spec.SplitSize,
		"compression": map[string]any{"algorithm": spec.Compression.Algorithm, "level": spec.Compression.Level},
		"verify":      spec.Verify, "dry_run": spec.DryRun, "skip_symlinks": spec.SkipSymlinks,
		"_resolved_destination": destination, "_archive_path": archivePath,
	}
	return map[string]any{"type": "BackupSpec", "values": values}
}

func failedResult(start time.Time, steps map[string]bool, warnings, errs []string, files, bytes int64, paths []string) Result {
	verify := false
	hasVerify := false
	for key := range steps {
		if strings.Contains(key, "verify") {
			hasVerify = true
		}
	}
	return Result{Success: false, DryRun: false, FilesProcessed: files, BytesWritten: bytes, ArchivesCreated: int64(len(paths)), ArchivePaths: paths, VerificationPassed: boolptrIf(hasVerify, verify), Warnings: warnings, Errors: errs, StepResults: steps, Checksums: map[string]string{}, DurationMS: time.Since(start).Milliseconds()}
}

func randomRunID() string {
	buf := make([]byte, 16)
	if _, err := rand.Read(buf); err == nil {
		return hex.EncodeToString(buf)
	}
	return fmt.Sprintf("go08-%d", time.Now().UnixNano())
}

func isCancelled(options Options) bool { return options.Cancelled != nil && options.Cancelled() }
func emit(options Options, stage string, files, bytes int64) {
	if options.RunStore != nil && options.RunID != "" {
		phase := "running"
		switch stage {
		case "complete":
			phase = "finalizing"
		case "verifying":
			phase = "verifying"
		}
		_, _ = options.RunStore.RecordPhase(options.RunID, phase)
	}
	if options.Progress != nil {
		options.Progress(ProgressEvent{Stage: stage, Files: files, Bytes: bytes})
	}
}
func int64ptr(v int64) *int64 { return &v }
func boolptrIf(enabled bool, v bool) *bool {
	if !enabled {
		return nil
	}
	return &v
}
func syncDir(path string) error {
	d, err := os.Open(path)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}
