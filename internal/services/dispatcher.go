package services

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"bkx/internal/archiveio"
	"bkx/internal/backupops"
	"bkx/internal/berror"
	"bkx/internal/fsutil"
	"bkx/internal/models"
	"bkx/internal/runstore"
	"bkx/internal/transferops"
)

const StatusSuccess = "success"

var ErrCancelled = errors.New("service operation cancelled")

type Issue struct {
	Code    string `json:"code"`
	Message string `json:"message"`
	Action  string `json:"action"`
}
type Artifact struct {
	Label                 string  `json:"label"`
	Path                  string  `json:"path"`
	SHA256                *string `json:"sha256,omitempty"`
	SizeBytes             *int64  `json:"size_bytes,omitempty"`
	ModifiedAtEpochMillis *int64  `json:"modified_at_epoch_millis,omitempty"`
}
type Result struct {
	Status    string     `json:"status"`
	Summary   string     `json:"summary"`
	Errors    []Issue    `json:"errors"`
	Artifacts []Artifact `json:"artifacts"`
}
type Progress struct {
	Phase      string   `json:"phase"`
	Message    string   `json:"message"`
	Progress   *float64 `json:"progress,omitempty"`
	FilesDone  *int64   `json:"files_done,omitempty"`
	FilesTotal *int64   `json:"files_total,omitempty"`
	BytesDone  *int64   `json:"bytes_done,omitempty"`
	BytesTotal *int64   `json:"bytes_total,omitempty"`
}
type OperationContext struct {
	RequestID string
	Cancelled func() bool
	Progress  func(Progress)
}
type StorageLocation struct {
	Label string
	Path  string
}
type Dispatcher struct {
	RunStore         *runstore.Store
	StorageLocations []StorageLocation
}

func New(store *runstore.Store) *Dispatcher {
	return &Dispatcher{RunStore: store, StorageLocations: defaultStorageLocations()}
}
func SupportedOperations() []string {
	return []string{"archive.browse", "archive.info", "archive.list", "archive.verify", "backup.create", "backup.plan", "filesystem.list", "filesystem.stat", "restore.extract", "restore.preview", "runs.list", "runs.show", "storage.locations", "transfer.plan", "transfer.run"}
}

func (d *Dispatcher) Dispatch(ctx OperationContext, operation string, raw json.RawMessage) (Result, error) {
	p, err := decodePayload(raw)
	if err != nil {
		return Result{}, err
	}
	if err := checkCancelled(ctx, "preparing"); err != nil {
		return Result{}, err
	}
	switch strings.TrimSpace(operation) {
	case "backup.plan":
		return d.backupPlan(ctx, p)
	case "backup.create":
		return d.backupCreate(ctx, p)
	case "archive.list":
		return d.archiveList(p)
	case "archive.info":
		return d.archiveInfo(p)
	case "archive.browse":
		return d.archiveBrowse(p)
	case "archive.verify":
		return d.archiveVerify(ctx, p)
	case "restore.preview":
		return d.restorePreview(ctx, p)
	case "restore.extract":
		return d.restoreExtract(ctx, p)
	case "transfer.plan":
		return d.transferPlan(ctx, p)
	case "transfer.run":
		return d.transferRun(ctx, p)
	case "runs.list":
		return d.runsList(p)
	case "runs.show":
		return d.runsShow(p)
	case "storage.locations":
		return d.storage(p)
	case "filesystem.list":
		return d.filesystemList(p)
	case "filesystem.stat":
		return d.filesystemStat(p)
	default:
		return Result{}, berror.Validation("Unsupported GO10 service operation: "+operation, map[string]any{"operation": operation, "supported": SupportedOperations()})
	}
}
func decodePayload(raw json.RawMessage) (map[string]any, error) {
	if len(raw) == 0 {
		return map[string]any{}, nil
	}
	dec := json.NewDecoder(strings.NewReader(string(raw)))
	dec.UseNumber()
	var p map[string]any
	if err := dec.Decode(&p); err != nil {
		return nil, berror.Validation("Service payload must be a JSON object", map[string]any{"reason": err.Error()})
	}
	if p == nil {
		return nil, berror.Validation("Service payload must be a JSON object", nil)
	}
	var trailing any
	if err := dec.Decode(&trailing); !errors.Is(err, io.EOF) {
		return nil, berror.Validation("Service payload contains trailing JSON data", nil)
	}
	return p, nil
}

func (d *Dispatcher) backupPlan(ctx OperationContext, p map[string]any) (Result, error) {
	emit(ctx, "planning", "Reviewing backup plan.", floatPtr(.15))
	spec, err := backupSpecFromBridge(p, true)
	if err != nil {
		return Result{}, err
	}
	plan, err := backupops.PlanBackup(spec)
	if err != nil {
		return Result{}, err
	}
	plan.Command = "backup.plan"
	tok, err := planToken(plan)
	if err != nil {
		return Result{}, err
	}
	issues := []Issue{}
	for _, w := range plan.Warnings {
		issues = append(issues, warningIssue("plan_warning", w))
	}
	return success(fmt.Sprintf("Backup plan ready: %d files, %d steps.", plan.TotalFiles, len(plan.Steps)), issues, []Artifact{artifact("backup_plan", "embedded://plan/"+requestID(ctx), tok)}), nil
}
func (d *Dispatcher) backupCreate(ctx OperationContext, p map[string]any) (Result, error) {
	spec, err := backupSpecFromBridge(p, false)
	if err != nil {
		return Result{}, err
	}
	plan, err := backupops.PlanBackup(spec)
	if err != nil {
		return Result{}, err
	}
	total := plan.TotalFiles
	emit(ctx, "archiving", "Creating backup archive.", floatPtr(.05))
	r, err := backupops.Create(spec, false, backupops.Options{RunStore: d.RunStore, RunID: requestID(ctx), Cancelled: ctx.Cancelled, Progress: func(e backupops.ProgressEvent) {
		var f *float64
		if total > 0 {
			v := float64(e.Files) / float64(total)
			f = &v
		}
		emitDetailed(ctx, e.Stage, fmt.Sprintf("%s: %d files, %d bytes.", title(e.Stage), e.Files, e.Bytes), f, int64Ptr(e.Files), int64Ptr(plan.TotalFiles), int64Ptr(e.Bytes), int64Ptr(plan.TotalBytes))
	}})
	if err != nil {
		if errors.Is(err, backupops.ErrCancelled) {
			return Result{}, ErrCancelled
		}
		return Result{}, err
	}
	issues := []Issue{}
	for _, x := range r.Errors {
		issues = append(issues, errorIssue("backup_error", x))
	}
	for _, x := range r.Warnings {
		issues = append(issues, warningIssue("backup_warning", x))
	}
	arts := []Artifact{}
	for _, path := range r.ArchivePaths {
		digest := r.Checksums[filepath.Base(path)]
		if digest == "" {
			digest = r.Checksums["archive"]
		}
		arts = append(arts, artifact("backup_archive", path, digest))
	}
	status := StatusSuccess
	if !r.Success {
		status = "failure"
	}
	word := "failed"
	if r.Success {
		word = "completed"
	}
	return Result{Status: status, Summary: fmt.Sprintf("Backup %s: %d files, %d bytes written.", word, r.FilesProcessed, r.BytesWritten), Errors: issues, Artifacts: arts}, nil
}

func (d *Dispatcher) archiveInfo(p map[string]any) (Result, error) {
	path, err := archivePath(p)
	if err != nil {
		return Result{}, err
	}
	r, err := archiveio.Info(path)
	if err != nil {
		return Result{}, err
	}
	return success(fmt.Sprintf("Archive: %d entries, %d uncompressed bytes, format %s.", r.TotalEntries, r.UncompressedSize, r.Format), nil, []Artifact{artifact("archive", r.ArchivePath, "")}), nil
}
func (d *Dispatcher) archiveBrowse(p map[string]any) (Result, error) {
	path, err := archivePath(p)
	if err != nil {
		return Result{}, err
	}
	page, err := mapping(p, "page", map[string]any{})
	if err != nil {
		return Result{}, err
	}
	size, err := intValueDefault(page, "page_size", 100)
	if err != nil {
		return Result{}, err
	}
	offset := 0
	if v, ok := page["page_token"]; ok && v != nil && fmt.Sprint(v) != "" {
		offset, err = strconv.Atoi(fmt.Sprint(v))
		if err != nil {
			return Result{}, berror.Validation("page_token must be an integer offset", nil)
		}
	}
	r, err := archiveio.Browse(path, archiveio.BrowseOptions{Limit: size, Offset: offset, SortBy: "path", SortOrder: "asc"})
	if err != nil {
		return Result{}, err
	}
	arts := []Artifact{}
	for _, e := range r.Entries {
		label := "archive_entry"
		switch e.EntryType {
		case "directory":
			label = "archive_entry_dir"
		case "symlink":
			label = "archive_entry_symlink"
		case "special":
			label = "archive_entry_special"
		}
		sz := e.Size
		mt := max64(0, e.MtimeNS/1_000_000)
		arts = append(arts, Artifact{Label: label, Path: e.Path, SizeBytes: &sz, ModifiedAtEpochMillis: &mt})
	}
	next := r.Offset + len(r.Entries)
	if next < r.TotalMatches {
		arts = append(arts, artifact("archive_next_page", fmt.Sprintf("embedded://archive/page/%d", next), ""))
	}
	return success(fmt.Sprintf("Archive page contains %d of %d matching entries (%d bytes).", len(r.Entries), r.TotalMatches, r.TotalSize), nil, arts), nil
}
func (d *Dispatcher) archiveVerify(ctx OperationContext, p map[string]any) (Result, error) {
	path, err := archivePath(p)
	if err != nil {
		return Result{}, err
	}
	emit(ctx, "verifying", "Verifying archive structure and contents.", floatPtr(.1))
	if err := checkCancelled(ctx, "archive verification"); err != nil {
		return Result{}, err
	}
	r, err := archiveio.Verify(path, []string{"sha256"})
	if err != nil {
		return Result{}, err
	}
	issues := []Issue{}
	for _, x := range r.Warnings {
		issues = append(issues, warningIssue("archive_verify_warning", x))
	}
	for _, x := range r.Errors {
		issues = append(issues, errorIssue("archive_verify_error", x))
	}
	for _, x := range r.FailedEntries {
		issues = append(issues, errorIssue("archive_verification_entry_failed", x))
	}
	status := StatusSuccess
	if !r.Success || !r.StructureValid || !r.ChecksumsValid {
		status = "failure"
	}
	word := "failed"
	if status == StatusSuccess {
		word = "passed"
	}
	return Result{Status: status, Summary: fmt.Sprintf("Archive verification %s; checked %d entries.", word, r.EntriesChecked), Errors: issues, Artifacts: []Artifact{artifact("archive_verification", r.ArchivePath, r.ArchiveChecksums["sha256"])}}, nil
}
func (d *Dispatcher) archiveList(p map[string]any) (Result, error) {
	roots, err := stringSliceDefault(p, "roots", []string{})
	if err != nil {
		return Result{}, berror.Validation("archive.list roots must be a list of absolute path strings", nil)
	}
	depth, err := intValueDefault(p, "max_depth", 4)
	if err != nil {
		return Result{}, err
	}
	limit, err := intValueDefault(p, "limit", 500)
	if err != nil {
		return Result{}, err
	}
	if depth < 0 {
		depth = 0
	}
	if depth > 8 {
		depth = 8
	}
	if limit < 1 {
		limit = 1
	}
	if limit > 2000 {
		limit = 2000
	}
	seen := map[string]bool{}
	items := []Artifact{}
	add := func(path string) {
		if len(items) >= limit {
			return
		}
		info, e := os.Lstat(path)
		if e != nil || info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() || !looksLikeArchive(path) {
			return
		}
		abs, e := filepath.Abs(path)
		if e != nil || seen[abs] {
			return
		}
		seen[abs] = true
		sz := info.Size()
		if logicalSize, sizeErr := archiveio.LogicalSize(abs); sizeErr == nil {
			sz = logicalSize
		}
		mt := max64(0, info.ModTime().UnixNano()/1_000_000)
		items = append(items, Artifact{Label: "archive", Path: abs, SizeBytes: &sz, ModifiedAtEpochMillis: &mt})
	}
	for _, root := range roots {
		if len(items) >= limit || !filepath.IsAbs(root) {
			continue
		}
		scanArchiveRoot(root, depth, limit, add)
	}
	if d.RunStore != nil {
		records, e := d.RunStore.List(min(limit, 200), "")
		if e != nil {
			return Result{}, e
		}
		for _, r := range records {
			if r.ArchivePath != nil {
				add(*r.ArchivePath)
			}
		}
	}
	sort.Slice(items, func(i, j int) bool {
		mi, mj := int64(0), int64(0)
		if items[i].ModifiedAtEpochMillis != nil {
			mi = *items[i].ModifiedAtEpochMillis
		}
		if items[j].ModifiedAtEpochMillis != nil {
			mj = *items[j].ModifiedAtEpochMillis
		}
		if mi != mj {
			return mi > mj
		}
		li, lj := strings.ToLower(items[i].Path), strings.ToLower(items[j].Path)
		if li != lj {
			return li < lj
		}
		return items[i].Path < items[j].Path
	})
	return success(fmt.Sprintf("Indexed %d BKX-compatible archives from direct storage and run history.", len(items)), nil, items), nil
}

func (d *Dispatcher) restorePreview(ctx OperationContext, p map[string]any) (Result, error) {
	emit(ctx, "planning", "Reviewing restore safety and collisions.", floatPtr(.1))
	if err := checkCancelled(ctx, "restore preview"); err != nil {
		return Result{}, err
	}
	spec, err := restoreSpecFromBridge(p, true)
	if err != nil {
		return Result{}, err
	}
	r, err := archiveio.PreviewRestore(spec)
	if err != nil {
		return Result{}, err
	}
	tok, err := token(restorePlan(spec))
	if err != nil {
		return Result{}, err
	}
	issues := []Issue{}
	for _, x := range r.Warnings {
		issues = append(issues, warningIssue("restore_warning", x))
	}
	if r.ExistingFiles > 0 {
		issues = append(issues, warningIssue("overwrite_collision", fmt.Sprintf("%d selected restore targets already exist.", r.ExistingFiles)))
	}
	arts := []Artifact{artifact("restore_plan", "embedded://plan/"+requestID(ctx), tok)}
	status := StatusSuccess
	if spec.Verify {
		vr, e := archiveio.Verify(spec.Archive, []string{"sha256"})
		if e != nil {
			return Result{}, e
		}
		arts = append(arts, artifact("archive_verification", spec.Archive, vr.ArchiveChecksums["sha256"]))
		if !vr.Success {
			issues = append(issues, errorIssue("archive_verification_failed", "Archive verification failed during restore preview."))
			status = "failure"
		}
	}
	return Result{Status: status, Summary: fmt.Sprintf("Restore preview: %d files, %d bytes; %d existing targets.", r.TotalFiles, r.TotalSize, r.ExistingFiles), Errors: issues, Artifacts: arts}, nil
}
func (d *Dispatcher) restoreExtract(ctx OperationContext, p map[string]any) (Result, error) {
	spec, err := restoreSpecFromBridge(p, false)
	if err != nil {
		return Result{}, err
	}
	preview, err := archiveio.PreviewRestore(spec)
	if err != nil {
		return Result{}, err
	}
	total := preview.TotalFiles
	emit(ctx, "verifying", "Checking restore archive before writing files.", floatPtr(.05))
	if err := checkCancelled(ctx, "restore verification"); err != nil {
		return Result{}, err
	}
	r, err := archiveio.ExtractRestore(spec, func(phase string, files int, bytes int64) {
		var f *float64
		if total > 0 {
			v := float64(files) / float64(total)
			if phase == "verifying" {
				v *= .4
			} else if phase == "extracting" {
				v = .4 + v*.55
			}
			f = &v
		}
		filesDone := int64(files)
		filesTotal := int64(total)
		emitDetailed(ctx, phase, fmt.Sprintf("%s: %d of %d files (%d bytes).", title(phase), files, total, bytes), f, &filesDone, &filesTotal, int64Ptr(bytes), int64Ptr(preview.TotalSize))
	})
	if err != nil {
		return Result{}, err
	}
	if err := checkCancelled(ctx, "restore publication"); err != nil {
		return Result{}, err
	}
	emit(ctx, "publishing", "Restore files published safely.", floatPtr(.95))
	issues := []Issue{}
	for _, x := range r.Warnings {
		issues = append(issues, warningIssue("restore_warning", x))
	}
	for _, x := range r.Errors {
		issues = append(issues, errorIssue("restore_error", x))
	}
	arts := []Artifact{}
	for _, path := range r.RestoredPaths {
		arts = append(arts, artifact("restored_path", path, ""))
	}
	status := StatusSuccess
	if !r.Success {
		status = "failure"
	}
	word := "failed"
	if r.Success {
		word = "completed"
	}
	return Result{Status: status, Summary: fmt.Sprintf("Restore %s: %d files, %d bytes.", word, r.FilesRestored, r.BytesRestored), Errors: issues, Artifacts: arts}, nil
}

func (d *Dispatcher) transferPlan(ctx OperationContext, p map[string]any) (Result, error) {
	spec, err := transferSpecFromBridge(p, true)
	if err != nil {
		return Result{}, err
	}
	plan, err := transferops.PlanTransfer(spec)
	if err != nil {
		return Result{}, err
	}
	plan.Command = "transfer.plan"
	tok, err := planToken(plan)
	if err != nil {
		return Result{}, err
	}
	issues := []Issue{}
	for _, x := range plan.Warnings {
		issues = append(issues, warningIssue("plan_warning", x))
	}
	return success(fmt.Sprintf("Transfer plan ready: %d files, %d steps.", plan.TotalFiles, len(plan.Steps)), issues, []Artifact{artifact("transfer_plan", "embedded://plan/"+requestID(ctx), tok)}), nil
}
func (d *Dispatcher) transferRun(ctx OperationContext, p map[string]any) (Result, error) {
	spec, err := transferSpecFromBridge(p, false)
	if err != nil {
		return Result{}, err
	}
	emit(ctx, "transferring", "Starting local transfer.", floatPtr(.02))
	r, err := transferops.Run(spec, transferops.Options{RunStore: d.RunStore, RunID: requestID(ctx), Cancelled: ctx.Cancelled, Progress: func(e transferops.ProgressEvent) {
		var f *float64
		if e.TotalFiles > 0 {
			v := float64(e.FilesTransferred) / float64(e.TotalFiles)
			f = &v
		}
		emitDetailed(ctx, "transferring", fmt.Sprintf("Transferred %d of %d files.", e.FilesTransferred, e.TotalFiles), f, int64Ptr(e.FilesTransferred), int64Ptr(e.TotalFiles), int64Ptr(e.BytesTransferred), int64Ptr(e.TotalBytes))
	}})
	if err != nil {
		if errors.Is(err, transferops.ErrCancelled) {
			return Result{}, ErrCancelled
		}
		return Result{}, err
	}
	issues := []Issue{}
	for _, x := range r.Warnings {
		issues = append(issues, warningIssue("transfer_warning", x))
	}
	for _, x := range r.Errors {
		issues = append(issues, errorIssue("transfer_error", x))
	}
	status := StatusSuccess
	if !r.Success || isFalse(r.VerificationPassed) || isFalse(r.ChecksumVerified) {
		status = "failure"
		if isFalse(r.VerificationPassed) || isFalse(r.ChecksumVerified) {
			issues = append(issues, errorIssue("transfer_verification_failed", "Transfer verification did not pass."))
		}
	}
	word := "failed"
	if status == StatusSuccess {
		word = "completed"
	}
	return Result{Status: status, Summary: fmt.Sprintf("Transfer %s: %d files, %d bytes.", word, r.FilesTransferred, r.BytesTransferred), Errors: issues, Artifacts: []Artifact{}}, nil
}

func (d *Dispatcher) filesystemList(p map[string]any) (Result, error) {
	path, err := requiredString(p, "path")
	if err != nil {
		return Result{}, err
	}
	hidden, err := boolDefault(p, "show_hidden", true)
	if err != nil {
		return Result{}, err
	}
	limit, err := intValueDefault(p, "limit", 250)
	if err != nil {
		return Result{}, err
	}
	offset, err := intValueDefault(p, "offset", 0)
	if err != nil {
		return Result{}, err
	}
	r, err := fsutil.List(path, hidden, limit, offset)
	if err != nil {
		return Result{}, err
	}
	arts := []Artifact{}
	for _, e := range r.Entries {
		sz, mt := e.SizeBytes, e.ModifiedAtEpochMillis
		arts = append(arts, Artifact{Label: e.Kind, Path: e.Path, SizeBytes: &sz, ModifiedAtEpochMillis: &mt})
	}
	suffix := ""
	if r.More {
		suffix = " More entries are available."
	}
	return success(fmt.Sprintf("Listed %d of %d entries in %s.%s", len(r.Entries), r.TotalCount, r.Path, suffix), nil, arts), nil
}
func (d *Dispatcher) filesystemStat(p map[string]any) (Result, error) {
	path, err := requiredString(p, "path")
	if err != nil {
		return Result{}, err
	}
	e, err := fsutil.Stat(path)
	if err != nil {
		return Result{}, err
	}
	sz, mt := e.SizeBytes, e.ModifiedAtEpochMillis
	return success(fmt.Sprintf("%s: %s, %d bytes, mode %04o, uid %d, gid %d.", e.Path, e.Kind, e.SizeBytes, e.Mode, e.UID, e.GID), nil, []Artifact{{Label: e.Kind, Path: e.Path, SizeBytes: &sz, ModifiedAtEpochMillis: &mt}}), nil
}
func (d *Dispatcher) storage(_ map[string]any) (Result, error) {
	arts := []Artifact{}
	for _, c := range d.StorageLocations {
		if info, err := os.Stat(c.Path); err == nil && info != nil {
			arts = append(arts, artifact(c.Label, c.Path, ""))
		}
	}
	priv := "app"
	if os.Geteuid() == 0 {
		priv = "root"
	}
	return success(fmt.Sprintf("Discovered %d direct filesystem locations with %s privileges.", len(arts), priv), nil, arts), nil
}
func (d *Dispatcher) runsList(p map[string]any) (Result, error) {
	if d.RunStore == nil {
		return Result{}, berror.Validation("Run history is not configured for this service dispatcher", nil)
	}
	limit, err := intValueDefault(p, "limit", 20)
	if err != nil {
		return Result{}, err
	}
	if limit <= 0 {
		return Result{}, berror.Validation("Run list limit must be greater than zero", map[string]any{"limit": limit})
	}
	op := optionalString(p["operation"])
	records, err := d.RunStore.List(limit, op)
	if err != nil {
		return Result{}, err
	}
	total, err := d.RunStore.Count(op)
	if err != nil {
		return Result{}, err
	}
	arts := []Artifact{}
	for _, r := range records {
		var completed any
		if r.CompletedAt != nil {
			completed = pythonISO(*r.CompletedAt)
		}
		summary := map[string]any{"run_id": r.RunID, "operation": r.Operation, "status": r.Status, "started_at": pythonISO(r.StartedAt), "completed_at": completed, "source_count": len(r.SourcePaths)}
		tok, _ := token(summary)
		arts = append(arts, artifact("run", "embedded://runs/"+r.RunID, tok))
	}
	return success(fmt.Sprintf("%d BKX runs available.", total), nil, arts), nil
}
func (d *Dispatcher) runsShow(p map[string]any) (Result, error) {
	if d.RunStore == nil {
		return Result{}, berror.Validation("Run history is not configured for this service dispatcher", nil)
	}
	id := optionalString(p["run_id"])
	if id == "" {
		id = optionalString(p["target_request_id"])
	}
	if id == "" {
		return Result{}, berror.Validation("runs.show requires run_id or target_request_id", nil)
	}
	r, err := d.RunStore.Get(id)
	if err != nil {
		return Result{}, err
	}
	if r == nil {
		return Result{}, berror.NotFound("Run not found: "+id, map[string]any{"run_id": id})
	}
	detail := map[string]any{
		"run_id": r.RunID, "operation": r.Operation, "status": r.Status, "timing": nil,
		"source_paths": r.SourcePaths, "destination_path": r.DestinationPath, "archive_path": r.ArchivePath,
		"files_count": r.FilesCount, "bytes_processed": r.BytesProcessed, "error_message": r.ErrorMessage, "spec_snapshot": r.SpecSnapshot,
	}
	if r.CompletedAt != nil {
		if started, e1 := time.Parse(time.RFC3339Nano, r.StartedAt); e1 == nil {
			if completed, e2 := time.Parse(time.RFC3339Nano, *r.CompletedAt); e2 == nil {
				detail["timing"] = []any{pythonISO(r.StartedAt), pythonFloatNumber(completed.Sub(started).Seconds())}
			}
		}
	}
	tok, _ := token(detail)
	return success(fmt.Sprintf("Run %s: %s is %s.", r.RunID, r.Operation, r.Status), nil, []Artifact{artifact("run", "embedded://runs/"+r.RunID, tok)}), nil
}
func backupSpecFromBridge(p map[string]any, dry bool) (models.BackupSpec, error) {
	source, err := mapping(p, "source", nil)
	if err != nil {
		return models.BackupSpec{}, err
	}
	dest, err := mapping(p, "destination", nil)
	if err != nil {
		return models.BackupSpec{}, err
	}
	filters, err := mapping(p, "filters", map[string]any{})
	if err != nil {
		return models.BackupSpec{}, err
	}
	archive, err := mapping(p, "archive", map[string]any{})
	if err != nil {
		return models.BackupSpec{}, err
	}
	split, err := mapping(archive, "split", map[string]any{})
	if err != nil {
		return models.BackupSpec{}, err
	}
	verification, err := mapping(archive, "verification", map[string]any{})
	if err != nil {
		return models.BackupSpec{}, err
	}
	follow, err := boolDefault(filters, "follow_symlinks", false)
	if err != nil {
		return models.BackupSpec{}, err
	}
	if follow {
		return models.BackupSpec{}, berror.Validation("BKX Android does not follow symbolic links; root backups skip links to stay inside the selected source tree", nil)
	}
	src, err := stringAlias(source, "path", "staged_path")
	if err != nil {
		return models.BackupSpec{}, err
	}
	dst, err := stringAlias(dest, "path", "staged_directory_path")
	if err != nil {
		return models.BackupSpec{}, err
	}
	algorithm := optionalString(archive["compression"])
	if algorithm == "" {
		algorithm = "gzip"
	}
	level, err := intValueDefault(archive, "compression_level", 3)
	if err != nil {
		return models.BackupSpec{}, err
	}
	var splitSize *int64
	enabled, _ := boolDefault(split, "enabled", false)
	if enabled && split["chunk_size_bytes"] != nil {
		v, e := int64Value(split["chunk_size_bytes"])
		if e != nil {
			return models.BackupSpec{}, e
		}
		splitSize = &v
	}
	verify, _ := boolDefault(verification, "verify_after_create", true)
	includes, err := stringSliceDefault(filters, "include_patterns", []string{})
	if err != nil {
		return models.BackupSpec{}, err
	}
	excludes, err := stringSliceDefault(filters, "exclude_patterns", []string{})
	if err != nil {
		return models.BackupSpec{}, err
	}
	name := optionalString(archive["archive_name"])
	var namePtr *string
	if name != "" {
		namePtr = &name
	}
	return models.BackupSpec{Sources: []string{src}, Destination: &dst, ArchiveName: namePtr, Excludes: excludes, Includes: includes, SplitSize: splitSize, Compression: models.CompressionProfile{Algorithm: strings.ToLower(algorithm), Level: level}, Verify: verify, DryRun: dry, SkipSymlinks: true}, nil
}
func restoreSpecFromBridge(p map[string]any, dry bool) (models.RestoreSpec, error) {
	archive, err := requiredString(p, "archive")
	if err != nil {
		return models.RestoreSpec{}, err
	}
	dest := optionalString(p["destination_path"])
	if dest == "" {
		if m, ok := p["destination"].(map[string]any); ok {
			dest = optionalString(m["staged_directory_path"])
		}
	}
	var destPtr *string
	if dest != "" {
		destPtr = &dest
	}
	paths, err := stringSliceDefault(p, "paths", []string{})
	if err != nil {
		return models.RestoreSpec{}, err
	}
	overwrite, _ := boolDefault(p, "overwrite", false)
	verify, _ := boolDefault(p, "verify", true)
	fresh, _ := boolDefault(p, "fresh_dir", true)
	confirm, _ := boolDefault(p, "confirm_overwrite", false)
	return models.RestoreSpec{Archive: archive, Destination: destPtr, Paths: paths, Overwrite: overwrite, Verify: verify, DryRun: dry, FreshDir: fresh, ConfirmOverwrite: confirm}, nil
}
func transferSpecFromBridge(p map[string]any, dry bool) (models.TransferSpec, error) {
	raw, ok := p["sources"].([]any)
	if !ok || len(raw) == 0 {
		return models.TransferSpec{}, berror.Validation("transfer sources must be a non-empty list", nil)
	}
	sources := []string{}
	for _, item := range raw {
		m, ok := item.(map[string]any)
		if !ok {
			return models.TransferSpec{}, berror.Validation("source endpoint must be an object", nil)
		}
		remote, _ := boolDefault(m, "remote", false)
		if remote {
			return models.TransferSpec{}, berror.Validation("GO09 local transfer supports only local Path sources and destinations", nil)
		}
		loc, err := requiredString(m, "location")
		if err != nil {
			return models.TransferSpec{}, err
		}
		sources = append(sources, loc)
	}
	destMap, err := mapping(p, "destination", nil)
	if err != nil {
		return models.TransferSpec{}, err
	}
	remote, _ := boolDefault(destMap, "remote", false)
	if remote {
		return models.TransferSpec{}, berror.Validation("GO09 local transfer supports only local Path sources and destinations", nil)
	}
	dest, err := requiredString(destMap, "location")
	if err != nil {
		return models.TransferSpec{}, err
	}
	direction := optionalString(p["mode"])
	if direction == "" {
		direction = optionalString(p["direction"])
	}
	if direction == "" {
		direction = "push"
	}
	if direction == "sync" {
		return models.TransferSpec{}, berror.Validation("GO10 service dispatch defers transfer sync until the frozen bridge advertises it", nil)
	}
	verify, _ := boolDefault(p, "verify", true)
	overwrite, _ := boolDefault(p, "overwrite", false)
	checksum := optionalString(p["checksum"])
	var checksumPtr *string
	if checksum != "" {
		checksumPtr = &checksum
	}
	return models.TransferSpec{Sources: sources, Destination: dest, Direction: direction, Verify: verify, Checksum: checksumPtr, DryRun: dry, Overwrite: overwrite}, nil
}
func archivePath(p map[string]any) (string, error) {
	m, err := mapping(p, "archive", nil)
	if err != nil {
		return "", err
	}
	return stringAlias(m, "path", "staged_path")
}
func restorePlan(spec models.RestoreSpec) map[string]any {
	steps := []map[string]any{}
	if spec.Verify {
		steps = append(steps, map[string]any{"step_type": "verify", "description": "Verify archive before restore", "source": spec.Archive, "destination": nil, "files_affected": 1, "bytes_affected": nil, "details": map[string]any{}, "warnings": []string{}})
	}
	var dest any = nil
	if spec.Destination != nil {
		dest = *spec.Destination
	}
	steps = append(steps, map[string]any{"step_type": "restore", "description": "Extract from " + filepath.Base(spec.Archive), "source": spec.Archive, "destination": dest, "files_affected": nil, "bytes_affected": nil, "details": map[string]any{"full_restore": len(spec.Paths) == 0, "selected_paths": spec.Paths, "overwrite": spec.Overwrite, "fresh_dir": spec.FreshDir, "confirm_overwrite": spec.ConfirmOverwrite, "requires_confirmation": false}, "warnings": []string{}})
	return map[string]any{"command": "restore.preview", "dry_run": true, "steps": steps, "total_files": nil, "total_bytes": nil, "warnings": []string{}}
}

func success(summary string, issues []Issue, arts []Artifact) Result {
	if issues == nil {
		issues = []Issue{}
	}
	if arts == nil {
		arts = []Artifact{}
	}
	return Result{Status: StatusSuccess, Summary: summary, Errors: issues, Artifacts: arts}
}
func artifact(label, path, digest string) Artifact {
	a := Artifact{Label: label, Path: path}
	if digest != "" {
		a.SHA256 = &digest
	}
	return a
}
func errorIssue(code, msg string) Issue {
	return Issue{Code: code, Message: msg, Action: "Review the operation details and retry."}
}
func warningIssue(code, msg string) Issue {
	return Issue{Code: code, Message: msg, Action: "Review this warning before continuing."}
}
func requestID(ctx OperationContext) string {
	id := strings.TrimSpace(ctx.RequestID)
	if id == "" {
		return "service"
	}
	return id
}
func emit(ctx OperationContext, phase, msg string, f *float64) {
	emitDetailed(ctx, phase, msg, f, nil, nil, nil, nil)
}

func emitDetailed(ctx OperationContext, phase, msg string, f *float64, filesDone, filesTotal, bytesDone, bytesTotal *int64) {
	if ctx.Progress == nil {
		return
	}
	if f != nil {
		v := *f
		if v < 0 {
			v = 0
		}
		if v > 1 {
			v = 1
		}
		f = &v
	}
	ctx.Progress(Progress{
		Phase: phase, Message: msg, Progress: f,
		FilesDone: filesDone, FilesTotal: filesTotal,
		BytesDone: bytesDone, BytesTotal: bytesTotal,
	})
}
func checkCancelled(ctx OperationContext, phase string) error {
	if ctx.Cancelled != nil && ctx.Cancelled() {
		return fmt.Errorf("%w during %s", ErrCancelled, phase)
	}
	return nil
}
func floatPtr(v float64) *float64 { return &v }
func int64Ptr(v int64) *int64     { return &v }
func max64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}
func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
func isFalse(v *bool) bool { return v != nil && !*v }
func title(s string) string {
	parts := strings.Fields(strings.ReplaceAll(s, "_", " "))
	for i, p := range parts {
		if p != "" {
			parts[i] = strings.ToUpper(p[:1]) + p[1:]
		}
	}
	return strings.Join(parts, " ")
}
func mapping(p map[string]any, key string, def map[string]any) (map[string]any, error) {
	v, ok := p[key]
	if !ok || v == nil {
		if def != nil {
			return def, nil
		}
		return nil, berror.Validation(key+" must be an object", nil)
	}
	m, ok := v.(map[string]any)
	if !ok {
		return nil, berror.Validation(key+" must be an object", nil)
	}
	return m, nil
}
func requiredString(p map[string]any, key string) (string, error) {
	s, ok := p[key].(string)
	if !ok || strings.TrimSpace(s) == "" {
		return "", berror.Validation(key+" must be a non-empty string", nil)
	}
	return strings.TrimSpace(s), nil
}
func stringAlias(p map[string]any, primary, legacy string) (string, error) {
	if s := optionalString(p[primary]); s != "" {
		return s, nil
	}
	return requiredString(p, legacy)
}
func optionalString(v any) string {
	if v == nil {
		return ""
	}
	s, ok := v.(string)
	if !ok {
		return ""
	}
	return strings.TrimSpace(s)
}
func boolDefault(p map[string]any, key string, def bool) (bool, error) {
	v, ok := p[key]
	if !ok || v == nil {
		return def, nil
	}
	b, ok := v.(bool)
	if !ok {
		return false, berror.Validation(key+" must be a boolean", nil)
	}
	return b, nil
}
func intValueDefault(p map[string]any, key string, def int) (int, error) {
	v, ok := p[key]
	if !ok || v == nil {
		return def, nil
	}
	i, err := int64Value(v)
	return int(i), err
}
func int64Value(v any) (int64, error) {
	switch x := v.(type) {
	case json.Number:
		i, err := x.Int64()
		if err != nil {
			return 0, berror.Validation("Expected integer", nil)
		}
		return i, nil
	case float64:
		if x != float64(int64(x)) {
			return 0, berror.Validation("Expected integer", nil)
		}
		return int64(x), nil
	case int:
		return int64(x), nil
	case int64:
		return x, nil
	default:
		return 0, berror.Validation("Expected integer", map[string]any{"value": fmt.Sprint(v)})
	}
}
func stringSliceDefault(p map[string]any, key string, def []string) ([]string, error) {
	v, ok := p[key]
	if !ok || v == nil {
		return append([]string(nil), def...), nil
	}
	raw, ok := v.([]any)
	if !ok {
		return nil, berror.Validation(key+" must be a list of strings", nil)
	}
	out := []string{}
	for _, item := range raw {
		s, ok := item.(string)
		if !ok {
			return nil, berror.Validation(key+" must be a list of strings", nil)
		}
		if s != "" {
			out = append(out, s)
		}
	}
	return out, nil
}
func planToken(value any) (string, error) {
	raw, err := json.Marshal(value)
	if err != nil {
		return "", err
	}
	var generic map[string]any
	if err := json.Unmarshal(raw, &generic); err != nil {
		return "", err
	}
	delete(generic, "timing_estimate")
	return token(generic)
}

func token(value any) (string, error) {
	var buf strings.Builder
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(value); err != nil {
		return "", err
	}
	canonical := strings.TrimSuffix(buf.String(), "\n")
	sum := sha256.Sum256([]byte(canonical))
	return hex.EncodeToString(sum[:]), nil
}
func looksLikeArchive(path string) bool {
	name := strings.ToLower(filepath.Base(path))
	for _, suffix := range []string{".bakery", ".zip", ".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz"} {
		if strings.HasSuffix(name, suffix) {
			return true
		}
	}
	return false
}
func scanArchiveRoot(root string, depth, limit int, add func(string)) {
	info, err := os.Lstat(root)
	if err != nil || info.Mode()&os.ModeSymlink != 0 {
		return
	}
	if info.Mode().IsRegular() {
		add(root)
		return
	}
	type node struct {
		path  string
		depth int
	}
	stack := []node{{root, 0}}
	visited := 0
	cap := limit * 20
	if cap < 1000 {
		cap = 1000
	}
	for len(stack) > 0 && visited < cap {
		n := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		entries, err := os.ReadDir(n.path)
		if err != nil {
			continue
		}
		for _, entry := range entries {
			visited++
			if visited >= cap {
				break
			}
			path := filepath.Join(n.path, entry.Name())
			li, err := os.Lstat(path)
			if err != nil || li.Mode()&os.ModeSymlink != 0 {
				continue
			}
			if li.Mode().IsRegular() {
				add(path)
			} else if li.IsDir() && n.depth < depth {
				stack = append(stack, node{path, n.depth + 1})
			}
		}
	}
}
func pythonFloatNumber(value float64) json.Number {
	raw := strconv.FormatFloat(value, 'g', -1, 64)
	if !strings.ContainsAny(raw, ".eE") {
		raw += ".0"
	}
	return json.Number(raw)
}

func pythonISO(raw string) string {
	t, err := time.Parse(time.RFC3339Nano, raw)
	if err != nil {
		return raw
	}
	t = t.Truncate(time.Microsecond)
	base := t.Format("2006-01-02T15:04:05")
	if ns := t.Nanosecond(); ns != 0 {
		base += fmt.Sprintf(".%06d", ns/1000)
	}
	_, offset := t.Zone()
	sign := "+"
	if offset < 0 {
		sign = "-"
		offset = -offset
	}
	return fmt.Sprintf("%s%s%02d:%02d", base, sign, offset/3600, (offset%3600)/60)
}

func defaultStorageLocations() []StorageLocation {
	return []StorageLocation{{"shared-storage", "/storage/emulated/0"}, {"termux-files", "/data/data/com.termux/files"}, {"termux-home", "/data/data/com.termux/files/home"}, {"termux-storage", "/data/data/com.termux/files/home/storage"}, {"termux-prefix", "/data/data/com.termux/files/usr"}, {"data-adb", "/data/adb"}, {"app-data", "/data/user/0"}, {"removable-storage", "/mnt/media_rw"}}
}
