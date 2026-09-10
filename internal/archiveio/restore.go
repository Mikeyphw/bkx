package archiveio

import (
	"archive/tar"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"bkx/internal/berror"
	"bkx/internal/models"
	"bkx/internal/validation"
)

type RestoreFileInfo struct {
	ArchivePath string `json:"archive_path"`
	DiskPath    string `json:"disk_path"`
	Size        int64  `json:"size"`
	MtimeNS     int64  `json:"mtime_ns"`
	IsPartial   bool   `json:"is_partial"`
}

type RestorePreviewResult struct {
	Success       bool              `json:"success"`
	ArchivePath   string            `json:"archive_path"`
	TotalFiles    int               `json:"total_files"`
	TotalSize     int64             `json:"total_size"`
	Files         []RestoreFileInfo `json:"files"`
	ExistingFiles int               `json:"existing_files"`
	Warnings      []string          `json:"warnings"`
}

type RestoreResult struct {
	Success            bool     `json:"success"`
	DryRun             bool     `json:"dry_run"`
	FilesRestored      int      `json:"files_restored"`
	BytesRestored      int64    `json:"bytes_restored"`
	ArchivePath        string   `json:"archive_path"`
	DestinationPath    string   `json:"destination_path"`
	RestoredPaths      []string `json:"restored_paths"`
	VerificationPassed *bool    `json:"verification_passed"`
	Warnings           []string `json:"warnings"`
	Errors             []string `json:"errors"`
	DurationMS         int64    `json:"duration_ms"`
}

var restoreRename = os.Rename

type restoreMember struct {
	RawName   string
	Name      string
	IsDir     bool
	Size      int64
	MtimeNS   int64
	EntryType string
}

func ParseRestoreSpecJSON(raw string) (models.RestoreSpec, error) {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal([]byte(raw), &fields); err != nil {
		return models.RestoreSpec{}, berror.Validation("Invalid RestoreSpec JSON", map[string]any{"reason": err.Error()})
	}
	for _, key := range []string{"archive", "destination", "paths", "overwrite", "verify", "dry_run", "fresh_dir", "confirm_overwrite"} {
		if _, ok := fields[key]; !ok {
			return models.RestoreSpec{}, berror.Validation("Invalid RestoreSpec JSON", map[string]any{"reason": "missing required field", "field": key})
		}
	}
	var spec models.RestoreSpec
	dec := json.NewDecoder(strings.NewReader(raw))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&spec); err != nil {
		return models.RestoreSpec{}, berror.Validation("Invalid RestoreSpec JSON", map[string]any{"reason": err.Error()})
	}
	var trailing any
	if err := dec.Decode(&trailing); !errors.Is(err, io.EOF) {
		return models.RestoreSpec{}, berror.Validation("Invalid RestoreSpec JSON", map[string]any{"reason": "trailing JSON data"})
	}
	return validation.ValidateRestoreSpec(spec)
}

func PreviewRestore(spec models.RestoreSpec) (RestorePreviewResult, error) {
	normalized, archive, destination, err := normalizeRestoreSpec(spec)
	if err != nil {
		return RestorePreviewResult{}, err
	}
	members, err := previewRestoreMembers(archive, normalized.Paths)
	if err != nil {
		return RestorePreviewResult{}, err
	}
	if err := ensureRestoreSelectorsFound(normalized.Paths, members); err != nil {
		return RestorePreviewResult{}, err
	}

	files := make([]RestoreFileInfo, 0, len(members))
	var totalSize int64
	existing := 0
	for _, member := range members {
		if member.IsDir {
			continue
		}
		target, err := restoreTarget(destination, member.Name)
		if err != nil {
			return RestorePreviewResult{}, err
		}
		if _, err := os.Lstat(target); err == nil {
			existing++
		} else if !os.IsNotExist(err) {
			return RestorePreviewResult{}, berror.WrapExternal("checking restore target", target, "go-os", err)
		}
		files = append(files, RestoreFileInfo{
			ArchivePath: member.Name,
			DiskPath:    target,
			Size:        member.Size,
			MtimeNS:     member.MtimeNS,
			IsPartial:   len(normalized.Paths) > 0,
		})
		totalSize += member.Size
	}
	warnings := []string{}
	if pathExists(destination) && normalized.ConfirmOverwrite {
		warnings = append(warnings, "Restore destination already exists and requires confirmation")
	}
	return RestorePreviewResult{
		Success: true, ArchivePath: archive, TotalFiles: len(files), TotalSize: totalSize,
		Files: files, ExistingFiles: existing, Warnings: warnings,
	}, nil
}

func ExtractRestore(spec models.RestoreSpec, progress func(string, int, int64)) (RestoreResult, error) {
	started := time.Now()
	normalized, archive, destination, err := normalizeRestoreSpec(spec)
	if err != nil {
		return RestoreResult{}, err
	}
	if pathExists(destination) && normalized.ConfirmOverwrite {
		return RestoreResult{}, berror.Validation(
			"Restore destination already exists and requires explicit confirmation",
			map[string]any{"destination": destination, "confirm_overwrite": true},
		)
	}

	preview, err := PreviewRestore(normalized)
	if err != nil {
		return RestoreResult{}, err
	}
	if normalized.DryRun {
		warnings := append([]string{"Dry run - files not extracted"}, preview.Warnings...)
		return RestoreResult{
			Success: true, DryRun: true, FilesRestored: preview.TotalFiles, BytesRestored: preview.TotalSize,
			ArchivePath: archive, DestinationPath: destination, RestoredPaths: []string{},
			Warnings: warnings, Errors: []string{}, DurationMS: time.Since(started).Milliseconds(),
		}, nil
	}

	var verificationPassed *bool
	warnings := []string{}
	if normalized.Verify {
		if progress != nil {
			progress("verifying", 0, 0)
		}
		result, err := Verify(archive, []string{"sha256"})
		if err != nil {
			return RestoreResult{}, err
		}
		warnings = append(warnings, result.Warnings...)
		if !result.Success {
			return RestoreResult{}, berror.External(
				"Archive verification failed: "+archive,
				map[string]any{"failures": result.FailedEntries, "errors": result.Errors},
			)
		}
		yes := true
		verificationPassed = &yes
	}

	members, err := safeRestoreMembers(archive, normalized.Paths)
	if err != nil {
		return RestoreResult{}, err
	}
	if err := ensureRestoreSelectorsFound(normalized.Paths, members); err != nil {
		return RestoreResult{}, err
	}
	if err := ensureNoSymlinkDestinationRoot(destination); err != nil {
		return RestoreResult{}, err
	}
	parent := filepath.Dir(destination)
	if err := os.MkdirAll(parent, 0o755); err != nil {
		return RestoreResult{}, berror.WrapExternal("creating restore destination parent", parent, "go-os", err)
	}
	stage, err := os.MkdirTemp(parent, "."+filepath.Base(destination)+".bkx-restore.")
	if err != nil {
		return RestoreResult{}, berror.WrapExternal("creating restore staging directory", parent, "go-os", err)
	}
	published := false
	defer func() {
		if !published {
			_ = os.RemoveAll(stage)
		}
	}()

	restoredRel, bytesDone, err := extractMembersToStage(archive, stage, members, progress)
	if err != nil {
		return RestoreResult{}, err
	}
	finalPaths, err := publishStagedRestore(stage, destination, restoredRel, normalized.Overwrite)
	if err != nil {
		return RestoreResult{}, err
	}
	published = true
	return RestoreResult{
		Success: true, DryRun: false, FilesRestored: len(restoredRel), BytesRestored: bytesDone,
		ArchivePath: archive, DestinationPath: destination, RestoredPaths: finalPaths,
		VerificationPassed: verificationPassed, Warnings: warnings, Errors: []string{},
		DurationMS: time.Since(started).Milliseconds(),
	}, nil
}

func normalizeRestoreSpec(spec models.RestoreSpec) (models.RestoreSpec, string, string, error) {
	spec, err := validation.ValidateRestoreSpec(spec)
	if err != nil {
		return models.RestoreSpec{}, "", "", err
	}
	archive, err := canonicalArchive(spec.Archive)
	if err != nil {
		return models.RestoreSpec{}, "", "", err
	}
	var destination string
	if spec.Destination != nil {
		destination, err = validation.AbsoluteLocalPath(*spec.Destination)
		if err != nil {
			return models.RestoreSpec{}, "", "", err
		}
	} else if spec.FreshDir {
		stem := archiveStem(archive)
		destination, err = validation.AbsoluteLocalPath(fmt.Sprintf("restored_%s_%d", stem, time.Now().Unix()))
		if err != nil {
			return models.RestoreSpec{}, "", "", err
		}
	} else {
		destination, err = os.Getwd()
		if err != nil {
			return models.RestoreSpec{}, "", "", berror.WrapExternal("resolving restore destination", "", "go-os", err)
		}
		destination = filepath.Clean(destination)
	}
	spec.Archive = archive
	spec.Destination = &destination
	return spec, archive, destination, nil
}

func archiveStem(path string) string {
	base := filepath.Base(path)
	ext := filepath.Ext(base)
	if ext != "" {
		base = strings.TrimSuffix(base, ext)
	}
	return base
}

func previewRestoreMembers(archive string, selectors []string) ([]restoreMember, error) {
	entries, _, err := readEntries(archive, false)
	if err != nil {
		return nil, err
	}
	out := make([]restoreMember, 0, len(entries))
	for _, entry := range entries {
		normalized, err := validation.NormalizeArchiveMember(entry.Path)
		if err != nil {
			return nil, err
		}
		if entry.IsDir {
			normalized = strings.TrimSuffix(normalized, "/") + "/"
		}
		member := restoreMember{
			RawName: entry.Path, Name: normalized, IsDir: entry.IsDir, Size: entry.Size,
			MtimeNS: entry.MtimeNS, EntryType: entry.EntryType,
		}
		if restoreMemberSelected(member.Name, selectors) {
			out = append(out, member)
		}
	}
	return out, nil
}

func safeRestoreMembers(archive string, selectors []string) ([]restoreMember, error) {
	format, err := detectFormat(archive)
	if err != nil {
		return nil, err
	}
	if format == "zip" {
		zr, err := openZipArchive(archive)
		if err != nil {
			return nil, berror.WrapExternal("reading zip archive", archive, "go-stdlib", err)
		}
		defer zr.Close()
		out := make([]restoreMember, 0, len(zr.File))
		for _, file := range zr.File {
			normalized, err := validation.NormalizeArchiveMember(file.Name)
			if err != nil {
				return nil, err
			}
			info := file.FileInfo()
			mode := info.Mode()
			if mode&os.ModeSymlink != 0 {
				return nil, berror.Validation("Archive symlink members are unsafe for restore", map[string]any{"member": file.Name})
			}
			isDir := info.IsDir()
			if !isDir && !mode.IsRegular() && mode.Type() != 0 {
				return nil, berror.Validation("Unsupported ZIP member type", map[string]any{"member": file.Name, "mode": mode.String()})
			}
			if isDir {
				normalized = strings.TrimSuffix(normalized, "/") + "/"
			}
			member := restoreMember{RawName: file.Name, Name: normalized, IsDir: isDir, Size: int64(file.UncompressedSize64), MtimeNS: zipMtimeNS(file), EntryType: map[bool]string{true: "directory", false: "file"}[isDir]}
			if restoreMemberSelected(member.Name, selectors) {
				out = append(out, member)
			}
		}
		return out, nil
	}

	rc, _, err := openTarStream(archive)
	if err != nil {
		return nil, err
	}
	defer rc.Close()
	tr := tar.NewReader(rc)
	out := []restoreMember{}
	for {
		hdr, err := tr.Next()
		if errors.Is(err, io.EOF) {
			if _, err := io.Copy(io.Discard, rc); err != nil {
				return nil, berror.WrapExternal("finishing compressed tar archive", archive, "go-codec", err)
			}
			break
		}
		if err != nil {
			return nil, berror.WrapExternal("reading tar archive", archive, "go-stdlib", err)
		}
		normalized, err := validation.NormalizeArchiveMember(hdr.Name)
		if err != nil {
			return nil, err
		}
		member := restoreMember{RawName: hdr.Name, Name: normalized, Size: hdr.Size, MtimeNS: hdr.ModTime.UnixNano()}
		switch hdr.Typeflag {
		case tar.TypeDir:
			member.IsDir, member.Size, member.EntryType = true, 0, "directory"
			member.Name = strings.TrimSuffix(normalized, "/") + "/"
		case tar.TypeReg, tar.TypeRegA:
			member.EntryType = "file"
		case tar.TypeSymlink, tar.TypeLink:
			return nil, berror.Validation("Archive link members are unsafe for restore", map[string]any{"member": hdr.Name})
		default:
			return nil, berror.Validation("Unsupported TAR member type", map[string]any{"member": hdr.Name, "type": hdr.Typeflag})
		}
		if restoreMemberSelected(member.Name, selectors) {
			out = append(out, member)
		}
	}
	return out, nil
}

func restoreMemberSelected(member string, selectors []string) bool {
	if len(selectors) == 0 {
		return true
	}
	candidate := strings.TrimSuffix(member, "/")
	for _, selector := range selectors {
		requested := strings.TrimSuffix(selector, "/")
		if candidate == requested || strings.HasPrefix(candidate, requested+"/") {
			return true
		}
	}
	return false
}

func ensureRestoreSelectorsFound(selectors []string, members []restoreMember) error {
	missing := []string{}
	for _, selector := range selectors {
		requested := strings.TrimSuffix(selector, "/")
		found := false
		for _, member := range members {
			candidate := strings.TrimSuffix(member.Name, "/")
			if candidate == requested || strings.HasPrefix(candidate, requested+"/") {
				found = true
				break
			}
		}
		if !found {
			missing = append(missing, requested)
		}
	}
	if len(missing) != 0 {
		return berror.NotFound("Requested restore paths were not found in archive", map[string]any{"missing_paths": missing})
	}
	return nil
}

func extractMembersToStage(archive, stage string, members []restoreMember, progress func(string, int, int64)) ([]string, int64, error) {
	format, err := detectFormat(archive)
	if err != nil {
		return nil, 0, err
	}
	wanted := map[string]restoreMember{}
	for _, member := range members {
		wanted[member.RawName] = member
	}
	restored := []string{}
	filesDone := 0
	var bytesDone int64

	writePayload := func(member restoreMember, src io.Reader) error {
		target, err := restoreTarget(stage, member.Name)
		if err != nil {
			return err
		}
		if err := ensureNoExistingSymlinkPath(stage, target); err != nil {
			return err
		}
		if member.IsDir {
			return os.MkdirAll(target, 0o755)
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return berror.WrapExternal("creating staged restore directory", filepath.Dir(target), "go-os", err)
		}
		f, err := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o666)
		if err != nil {
			return berror.WrapExternal("creating staged restore file", target, "go-os", err)
		}
		_, copyErr := io.CopyBuffer(f, src, make([]byte, 1024*1024))
		syncErr := f.Sync()
		closeErr := f.Close()
		if copyErr != nil {
			return berror.WrapExternal("extracting archive member", member.Name, "go-stdlib", copyErr)
		}
		if syncErr != nil {
			return berror.WrapExternal("syncing staged restore file", target, "go-os", syncErr)
		}
		if closeErr != nil {
			return berror.WrapExternal("closing staged restore file", target, "go-os", closeErr)
		}
		restored = append(restored, member.Name)
		filesDone++
		bytesDone += member.Size
		if progress != nil {
			progress("extracting", filesDone, bytesDone)
		}
		return nil
	}

	if format == "zip" {
		zr, err := openZipArchive(archive)
		if err != nil {
			return nil, 0, berror.WrapExternal("reading zip archive", archive, "go-stdlib", err)
		}
		defer zr.Close()
		for _, file := range zr.File {
			member, ok := wanted[file.Name]
			if !ok {
				continue
			}
			if member.IsDir {
				if err := writePayload(member, strings.NewReader("")); err != nil {
					return nil, 0, err
				}
				continue
			}
			rc, err := openZipPayload(file)
			if err != nil {
				return nil, 0, err
			}
			err = writePayload(member, rc)
			closeErr := rc.Close()
			if err != nil {
				return nil, 0, err
			}
			if closeErr != nil {
				return nil, 0, berror.WrapExternal("closing zip member", member.Name, "go-stdlib", closeErr)
			}
		}
		return restored, bytesDone, nil
	}

	rc, _, err := openTarStream(archive)
	if err != nil {
		return nil, 0, err
	}
	defer rc.Close()
	tr := tar.NewReader(rc)
	for {
		hdr, err := tr.Next()
		if errors.Is(err, io.EOF) {
			if _, err := io.Copy(io.Discard, rc); err != nil {
				return nil, 0, berror.WrapExternal("finishing compressed tar archive", archive, "go-codec", err)
			}
			break
		}
		if err != nil {
			return nil, 0, berror.WrapExternal("reading tar archive", archive, "go-stdlib", err)
		}
		member, ok := wanted[hdr.Name]
		if !ok {
			continue
		}
		if err := writePayload(member, tr); err != nil {
			return nil, 0, err
		}
	}
	return restored, bytesDone, nil
}

func publishStagedRestore(stage, destination string, restored []string, overwrite bool) ([]string, error) {
	if err := ensureNoSymlinkDestinationRoot(destination); err != nil {
		return nil, err
	}
	if !pathExists(destination) {
		if err := restoreRename(stage, destination); err != nil {
			return nil, berror.WrapExternal("publishing staged restore", destination, "go-os", err)
		}
		if err := syncDir(filepath.Dir(destination)); err != nil {
			return nil, err
		}
		out := make([]string, 0, len(restored))
		for _, rel := range restored {
			target, err := restoreTarget(destination, rel)
			if err != nil {
				return nil, err
			}
			out = append(out, target)
		}
		return out, nil
	}
	st, err := os.Lstat(destination)
	if err != nil {
		return nil, berror.WrapExternal("checking restore destination", destination, "go-os", err)
	}
	if st.Mode()&os.ModeSymlink != 0 || !st.IsDir() {
		return nil, berror.Validation("Restore destination must be a real directory when it already exists", map[string]any{"destination": destination})
	}

	dirs, files, err := stagedTree(stage)
	if err != nil {
		return nil, err
	}
	// Preflight every target before mutation. This prevents a late collision or
	// symlink from producing a partially published restore.
	for _, rel := range append(append([]string{}, dirs...), files...) {
		target, err := restoreTarget(destination, rel)
		if err != nil {
			return nil, err
		}
		if err := ensureNoExistingSymlinkPath(destination, target); err != nil {
			return nil, err
		}
	}
	if !overwrite {
		for _, rel := range files {
			target, _ := restoreTarget(destination, rel)
			if pathExists(target) {
				return nil, berror.Validation("Restore target already exists and overwrite is disabled", map[string]any{"path": target})
			}
		}
	}

	rollback, err := os.MkdirTemp(filepath.Dir(destination), "."+filepath.Base(destination)+".bkx-rollback.")
	if err != nil {
		return nil, berror.WrapExternal("creating restore rollback directory", filepath.Dir(destination), "go-os", err)
	}
	defer os.RemoveAll(rollback)

	createdFiles := []string{}
	replaced := map[string]string{}
	createdDirs := []string{}
	rollbackNeeded := true
	defer func() {
		if !rollbackNeeded {
			return
		}
		for i := len(createdFiles) - 1; i >= 0; i-- {
			_ = os.Remove(createdFiles[i])
		}
		keys := make([]string, 0, len(replaced))
		for target := range replaced {
			keys = append(keys, target)
		}
		sort.Sort(sort.Reverse(sort.StringSlice(keys)))
		for _, target := range keys {
			_ = os.Remove(target)
			_ = restoreRename(replaced[target], target)
		}
		for i := len(createdDirs) - 1; i >= 0; i-- {
			_ = os.Remove(createdDirs[i])
		}
	}()

	sort.SliceStable(dirs, func(i, j int) bool {
		return len(strings.Split(filepath.Clean(dirs[i]), string(os.PathSeparator))) < len(strings.Split(filepath.Clean(dirs[j]), string(os.PathSeparator)))
	})
	for _, rel := range dirs {
		target, _ := restoreTarget(destination, rel)
		if !pathExists(target) {
			if err := os.Mkdir(target, 0o755); err != nil && !os.IsExist(err) {
				return nil, berror.WrapExternal("creating restore directory", target, "go-os", err)
			}
			createdDirs = append(createdDirs, target)
		}
	}
	sort.Strings(files)
	finalPaths := make([]string, 0, len(restored))
	for _, rel := range files {
		source, _ := restoreTarget(stage, rel)
		target, _ := restoreTarget(destination, rel)
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return nil, berror.WrapExternal("creating restore target parent", filepath.Dir(target), "go-os", err)
		}
		existed := pathExists(target)
		if existed {
			backup, _ := restoreTarget(rollback, rel)
			if err := os.MkdirAll(filepath.Dir(backup), 0o755); err != nil {
				return nil, err
			}
			if err := restoreRename(target, backup); err != nil {
				return nil, berror.WrapExternal("backing up restore target", target, "go-os", err)
			}
			replaced[target] = backup
		}
		if err := restoreRename(source, target); err != nil {
			return nil, berror.WrapExternal("publishing restore target", target, "go-os", err)
		}
		if !existed {
			createdFiles = append(createdFiles, target)
		}
	}
	for _, rel := range restored {
		target, err := restoreTarget(destination, rel)
		if err != nil {
			return nil, err
		}
		finalPaths = append(finalPaths, target)
	}
	if err := syncDir(destination); err != nil {
		return nil, err
	}
	rollbackNeeded = false
	_ = os.RemoveAll(stage)
	return finalPaths, nil
}

func stagedTree(stage string) ([]string, []string, error) {
	dirs := []string{}
	files := []string{}
	err := filepath.WalkDir(stage, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if path == stage {
			return nil
		}
		rel, err := filepath.Rel(stage, path)
		if err != nil {
			return err
		}
		if d.Type()&os.ModeSymlink != 0 {
			return berror.Validation("Restore staging tree unexpectedly contains a symlink", map[string]any{"path": path})
		}
		if d.IsDir() {
			dirs = append(dirs, rel)
		} else if d.Type().IsRegular() {
			files = append(files, rel)
		} else {
			return berror.Validation("Restore staging tree unexpectedly contains a special file", map[string]any{"path": path})
		}
		return nil
	})
	if err != nil {
		return nil, nil, err
	}
	return dirs, files, nil
}

func restoreTarget(root, member string) (string, error) {
	normalized, err := validation.NormalizeArchiveMember(member)
	if err != nil {
		return "", err
	}
	normalized = strings.TrimSuffix(normalized, "/")
	base, err := filepath.Abs(root)
	if err != nil {
		return "", err
	}
	target := filepath.Join(base, filepath.FromSlash(normalized))
	rel, err := filepath.Rel(base, target)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) {
		return "", berror.Validation("Path escapes destination root", map[string]any{"root": base, "path": member})
	}
	return target, nil
}

func ensureNoExistingSymlinkPath(root, target string) error {
	base, err := filepath.Abs(root)
	if err != nil {
		return err
	}
	absolute, err := filepath.Abs(target)
	if err != nil {
		return err
	}
	rel, err := filepath.Rel(base, absolute)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) {
		return berror.Validation("Restore target escaped staging root", map[string]any{"root": base, "target": absolute})
	}
	current := base
	if st, err := os.Lstat(current); err == nil && st.Mode()&os.ModeSymlink != 0 {
		return berror.Validation("Restore refuses to write through an existing symlink", map[string]any{"path": current})
	}
	for _, part := range strings.Split(rel, string(os.PathSeparator)) {
		if part == "." || part == "" {
			continue
		}
		current = filepath.Join(current, part)
		if st, err := os.Lstat(current); err == nil {
			if st.Mode()&os.ModeSymlink != 0 {
				return berror.Validation("Restore refuses to write through an existing symlink", map[string]any{"path": current})
			}
		} else if !os.IsNotExist(err) {
			return berror.WrapExternal("checking restore path", current, "go-os", err)
		}
	}
	return nil
}

func ensureNoSymlinkDestinationRoot(destination string) error {
	absolute, err := filepath.Abs(destination)
	if err != nil {
		return err
	}
	volume := filepath.VolumeName(absolute)
	current := string(os.PathSeparator)
	rest := strings.TrimPrefix(absolute, volume)
	rest = strings.TrimPrefix(rest, string(os.PathSeparator))
	if volume != "" {
		current = volume + string(os.PathSeparator)
	}
	for _, part := range strings.Split(rest, string(os.PathSeparator)) {
		if part == "" {
			continue
		}
		current = filepath.Join(current, part)
		st, err := os.Lstat(current)
		if err == nil && st.Mode()&os.ModeSymlink != 0 {
			return berror.Validation("Restore refuses to write through a destination symlink", map[string]any{"path": current})
		}
		if err != nil && !os.IsNotExist(err) {
			return berror.WrapExternal("checking restore destination", current, "go-os", err)
		}
	}
	return nil
}

func pathExists(path string) bool {
	_, err := os.Lstat(path)
	return err == nil
}
