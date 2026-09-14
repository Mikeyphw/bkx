package resticops

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"bkx/internal/berror"
	"bkx/internal/models"
	"bkx/internal/validation"
)

const (
	DefaultSnapshotListLimit   = 1000
	MaxSnapshotListLimit       = 5000
	DefaultSnapshotDiffLimit   = 1000
	MaxSnapshotDiffLimit       = 5000
	DefaultSnapshotBrowseLimit = 1000
	MaxSnapshotBrowseLimit     = 5000
)

type SnapshotListOptions struct {
	Host  string
	Path  string
	Tags  []string
	Limit int
}

type SnapshotListResult struct {
	Success    bool                        `json:"success"`
	Repository string                      `json:"repository"`
	Snapshots  []models.SnapshotDescriptor `json:"snapshots"`
	Total      int                         `json:"total"`
	Limit      int                         `json:"limit,omitempty"`
	Truncated  bool                        `json:"truncated,omitempty"`
}

type SnapshotBrowseResult struct {
	Success    bool                  `json:"success"`
	Repository string                `json:"repository"`
	SnapshotID string                `json:"snapshot_id"`
	Path       string                `json:"path"`
	Entries    []models.SnapshotNode `json:"entries"`
	Limit      int                   `json:"limit,omitempty"`
	Truncated  bool                  `json:"truncated,omitempty"`
}

type SnapshotBrowseOptions struct {
	Recursive bool
	Limit     int
}

type DiffChange struct {
	Path     string `json:"path"`
	Modifier string `json:"modifier"`
}

type DiffStat struct {
	Files     int64  `json:"files"`
	Dirs      int64  `json:"dirs"`
	Others    int64  `json:"others"`
	DataBlobs int64  `json:"data_blobs"`
	TreeBlobs int64  `json:"tree_blobs"`
	Bytes     uint64 `json:"bytes"`
}

type DiffStatistics struct {
	SourceSnapshot string   `json:"source_snapshot"`
	TargetSnapshot string   `json:"target_snapshot"`
	ChangedFiles   int64    `json:"changed_files"`
	Added          DiffStat `json:"added"`
	Removed        DiffStat `json:"removed"`
}

type SnapshotDiffResult struct {
	Success    bool           `json:"success"`
	Repository string         `json:"repository"`
	Source     string         `json:"source_snapshot"`
	Target     string         `json:"target_snapshot"`
	Changes    []DiffChange   `json:"changes"`
	Statistics DiffStatistics `json:"statistics"`
	Limit      int            `json:"limit,omitempty"`
	Truncated  bool           `json:"truncated,omitempty"`
}

type RestoreOptions struct {
	Target    string
	Includes  []string
	Excludes  []string
	Overwrite string
	DryRun    bool
}

type RestoreResult struct {
	Success       bool     `json:"success"`
	Repository    string   `json:"repository"`
	SnapshotID    string   `json:"snapshot_id"`
	Target        string   `json:"target"`
	DryRun        bool     `json:"dry_run"`
	Includes      []string `json:"includes,omitempty"`
	Excludes      []string `json:"excludes,omitempty"`
	Overwrite     string   `json:"overwrite"`
	FilesRestored int64    `json:"files_restored"`
	FilesSkipped  int64    `json:"files_skipped"`
	BytesRestored int64    `json:"bytes_restored"`
	BytesSkipped  int64    `json:"bytes_skipped"`
	TotalFiles    int64    `json:"total_files"`
	TotalBytes    int64    `json:"total_bytes"`
	DurationMS    int64    `json:"duration_ms"`
}

type CheckOptions struct {
	ReadData       bool
	ReadDataSubset string
}

type MaintenanceResult struct {
	Success    bool   `json:"success"`
	Repository string `json:"repository"`
	Operation  string `json:"operation"`
	DryRun     bool   `json:"dry_run,omitempty"`
	SnapshotID string `json:"snapshot_id,omitempty"`
	DurationMS int64  `json:"duration_ms"`
}

type PruneOptions struct {
	DryRun        bool
	MaxUnused     string
	MaxRepackSize string
}

func prepareAccess(spec models.RepositoryAccessSpec, options Options) (Runner, string, []string, []string, error) {
	repository, err := NormalizeRepository(spec.Repository)
	if err != nil {
		return nil, "", nil, nil, err
	}
	runner, err := ResolveRunner(options)
	if err != nil {
		return nil, "", nil, nil, err
	}
	env, err := credentialEnvironment(options.Environ, spec.CredentialRef)
	if err != nil {
		return nil, "", nil, nil, err
	}
	commandRepo, backendArgs, env, err := repositoryInvocation(repository, env)
	if err != nil {
		return nil, "", nil, nil, err
	}
	base := append([]string{}, backendArgs...)
	base = append(base, "--repo", commandRepo)
	return runner, repository, base, env, nil
}

func ListSnapshots(spec models.RepositoryAccessSpec, filters SnapshotListOptions, options Options) (SnapshotListResult, error) {
	runner, repository, base, env, err := prepareAccess(spec, options)
	if err != nil {
		return SnapshotListResult{}, err
	}
	limit := normalizeLimit(filters.Limit, DefaultSnapshotListLimit, MaxSnapshotListLimit)
	args := append([]string{"--json"}, base...)
	args = append(args, "snapshots")
	if strings.TrimSpace(filters.Host) != "" {
		args = append(args, "--host", strings.TrimSpace(filters.Host))
	}
	if strings.TrimSpace(filters.Path) != "" {
		args = append(args, "--path", strings.TrimSpace(filters.Path))
	}
	for _, tag := range filters.Tags {
		if strings.TrimSpace(tag) != "" {
			args = append(args, "--tag", strings.TrimSpace(tag))
		}
	}
	stdout, stderr, runErr := runner.Run(args, env)
	if runErr != nil {
		return SnapshotListResult{}, external("listing Restic snapshots", repository, stderr, runErr)
	}
	var raw []struct {
		ID       string   `json:"id"`
		ShortID  string   `json:"short_id"`
		Time     string   `json:"time"`
		Hostname string   `json:"hostname"`
		Username string   `json:"username"`
		Paths    []string `json:"paths"`
		Tags     []string `json:"tags"`
		Size     *uint64  `json:"summary,omitempty"`
	}
	if err := json.Unmarshal(stdout, &raw); err != nil {
		return SnapshotListResult{}, berror.External("Restic snapshots returned invalid JSON", map[string]any{"tool": "restic", "reason": err.Error()})
	}
	snapshots := make([]models.SnapshotDescriptor, 0, len(raw))
	for index, item := range raw {
		if strings.TrimSpace(item.ID) == "" {
			return SnapshotListResult{}, berror.External("Restic snapshots returned a record without an id", map[string]any{"tool": "restic", "index": index})
		}
		if _, err := parseSnapshotTime(item.Time); err != nil {
			return SnapshotListResult{}, berror.External("Restic snapshots returned an invalid timestamp", map[string]any{"tool": "restic", "snapshot_id": item.ID, "time": item.Time, "reason": err.Error()})
		}
		short := item.ShortID
		if short == "" && len(item.ID) >= 8 {
			short = item.ID[:8]
		}
		snapshots = append(snapshots, models.SnapshotDescriptor{ID: item.ID, ShortID: short, Time: item.Time, Hostname: item.Hostname, Username: item.Username, Paths: append([]string(nil), item.Paths...), Tags: append([]string(nil), item.Tags...), Size: item.Size})
	}
	sort.SliceStable(snapshots, func(i, j int) bool {
		ti, _ := parseSnapshotTime(snapshots[i].Time)
		tj, _ := parseSnapshotTime(snapshots[j].Time)
		if !ti.Equal(tj) {
			return ti.After(tj)
		}
		return snapshots[i].ID > snapshots[j].ID
	})
	total := len(snapshots)
	truncated := false
	if total > limit {
		snapshots = snapshots[:limit]
		truncated = true
	}
	return SnapshotListResult{Success: true, Repository: repository, Snapshots: snapshots, Total: total, Limit: limit, Truncated: truncated}, nil
}

func normalizeLimit(value, def, max int) int {
	if value <= 0 {
		value = def
	}
	if value > max {
		value = max
	}
	return value
}

func parseSnapshotTime(value string) (time.Time, error) {
	parsed, err := time.Parse(time.RFC3339Nano, strings.TrimSpace(value))
	if err == nil {
		return parsed, nil
	}
	return time.Parse(time.RFC3339, strings.TrimSpace(value))
}

func ResolveSnapshot(spec models.RepositoryAccessSpec, selector string, options Options) (models.SnapshotDescriptor, error) {
	value := strings.TrimSpace(selector)
	if value == "" {
		return models.SnapshotDescriptor{}, berror.Validation("snapshot selector must not be empty", nil)
	}
	listOptions := SnapshotListOptions{}
	if value != "latest" && !strings.HasPrefix(value, "latest~") && len(value) >= 8 && len(value) >= 32 {
		listOptions.Limit = MaxSnapshotListLimit
	}
	listed, err := ListSnapshots(spec, listOptions, options)
	if err != nil {
		return models.SnapshotDescriptor{}, err
	}
	if len(listed.Snapshots) == 0 {
		return models.SnapshotDescriptor{}, berror.NotFound("No snapshots exist in this repository", nil)
	}
	if value == "latest" {
		return listed.Snapshots[0], nil
	}
	if strings.HasPrefix(value, "latest~") {
		rawOffset := strings.TrimPrefix(value, "latest~")
		offset, parseErr := strconv.Atoi(rawOffset)
		if parseErr != nil || offset < 0 {
			return models.SnapshotDescriptor{}, berror.Validation("latest snapshot selector must use latest~N with a non-negative integer", map[string]any{"snapshot": value})
		}
		if offset >= len(listed.Snapshots) {
			return models.SnapshotDescriptor{}, berror.NotFound("Snapshot selector is older than the available snapshot history: "+value, map[string]any{"snapshot": value, "available": len(listed.Snapshots)})
		}
		return listed.Snapshots[offset], nil
	}
	matches := []models.SnapshotDescriptor{}
	for _, item := range listed.Snapshots {
		if item.ID == value {
			return item, nil
		}
		if strings.HasPrefix(item.ID, value) {
			matches = append(matches, item)
		}
	}
	if len(matches) == 1 {
		return matches[0], nil
	}
	if len(matches) > 1 {
		return models.SnapshotDescriptor{}, berror.Validation("snapshot selector is ambiguous", map[string]any{"snapshot": value, "matches": len(matches)})
	}
	return models.SnapshotDescriptor{}, berror.NotFound("Snapshot not found: "+value, map[string]any{"snapshot": value})
}

func BrowseSnapshot(spec models.RepositoryAccessSpec, selector, path string, recursive bool, options Options) (SnapshotBrowseResult, error) {
	return BrowseSnapshotWithOptions(spec, selector, path, SnapshotBrowseOptions{Recursive: recursive}, options)
}

func BrowseSnapshotWithOptions(spec models.RepositoryAccessSpec, selector, path string, browseOptions SnapshotBrowseOptions, options Options) (SnapshotBrowseResult, error) {
	runner, repository, base, env, err := prepareAccess(spec, options)
	if err != nil {
		return SnapshotBrowseResult{}, err
	}
	snapshot, err := ResolveSnapshot(spec, selector, options)
	if err != nil {
		return SnapshotBrowseResult{}, err
	}
	browsePath := strings.TrimSpace(path)
	if browsePath == "" {
		browsePath = "/"
	}
	if !strings.HasPrefix(browsePath, "/") {
		return SnapshotBrowseResult{}, berror.Validation("snapshot browse path must be absolute within the snapshot", map[string]any{"path": browsePath})
	}
	browsePath = filepath.Clean(strings.ReplaceAll(browsePath, "\\", "/"))
	if browsePath == "." {
		browsePath = "/"
	}
	args := append([]string{"--json"}, base...)
	args = append(args, "ls")
	if browseOptions.Recursive {
		args = append(args, "--recursive")
	}
	args = append(args, snapshot.ID, browsePath)
	stdout, stderr, runErr := runner.Run(args, env)
	if runErr != nil {
		return SnapshotBrowseResult{}, external("browsing Restic snapshot", repository, stderr, runErr)
	}
	limit := normalizeLimit(browseOptions.Limit, DefaultSnapshotBrowseLimit, MaxSnapshotBrowseLimit)
	entries := []models.SnapshotNode{}
	truncated := false
	for lineNumber, line := range bytes.Split(stdout, []byte{'\n'}) {
		line = bytes.TrimSpace(line)
		if len(line) == 0 {
			continue
		}
		var head struct {
			MessageType string `json:"message_type"`
			StructType  string `json:"struct_type"`
		}
		if err := json.Unmarshal(line, &head); err != nil {
			return SnapshotBrowseResult{}, berror.External("Restic snapshot browse returned invalid JSON", map[string]any{"tool": "restic", "line": lineNumber + 1, "reason": err.Error()})
		}
		if head.MessageType != "node" && head.StructType != "node" {
			continue
		}
		var node struct {
			Name        string `json:"name"`
			Type        string `json:"type"`
			Path        string `json:"path"`
			Size        uint64 `json:"size"`
			Permissions string `json:"permissions"`
			MTime       string `json:"mtime"`
		}
		if err := json.Unmarshal(line, &node); err != nil {
			return SnapshotBrowseResult{}, berror.External("Restic snapshot browse returned a malformed node", map[string]any{"tool": "restic", "line": lineNumber + 1, "reason": err.Error()})
		}
		if strings.TrimSpace(node.Path) == "" || strings.TrimSpace(node.Type) == "" {
			return SnapshotBrowseResult{}, berror.External("Restic snapshot browse returned an incomplete node", map[string]any{"tool": "restic", "line": lineNumber + 1})
		}
		cleanPath := filepath.Clean(strings.ReplaceAll(node.Path, "\\", "/"))
		if cleanPath == "." {
			cleanPath = "/"
		}
		if !strings.HasPrefix(cleanPath, "/") {
			return SnapshotBrowseResult{}, berror.External("Restic snapshot browse returned a non-absolute node path", map[string]any{"tool": "restic", "path": node.Path})
		}
		// restic includes the selected directory itself. The browser renders
		// children only, so omit that exact node while retaining root children.
		if cleanPath == browsePath {
			continue
		}
		if len(entries) < limit {
			entries = append(entries, models.SnapshotNode{
				Name: strings.TrimSpace(node.Name), Type: strings.TrimSpace(node.Type), Path: cleanPath, Size: node.Size,
				Permissions: node.Permissions, ModTime: node.MTime,
			})
		} else {
			truncated = true
		}
	}
	sort.SliceStable(entries, func(i, j int) bool {
		id, jd := entries[i].Type == "dir", entries[j].Type == "dir"
		if id != jd {
			return id
		}
		return strings.ToLower(entries[i].Name) < strings.ToLower(entries[j].Name)
	})
	return SnapshotBrowseResult{Success: true, Repository: repository, SnapshotID: snapshot.ID, Path: browsePath, Entries: entries, Limit: limit, Truncated: truncated}, nil
}

type SnapshotDiffOptions struct {
	Metadata   bool
	MaxChanges int
}

func DiffSnapshots(spec models.RepositoryAccessSpec, source, target string, metadata bool, options Options) (SnapshotDiffResult, error) {
	return DiffSnapshotsWithOptions(spec, source, target, SnapshotDiffOptions{Metadata: metadata}, options)
}

func DiffSnapshotsWithOptions(spec models.RepositoryAccessSpec, source, target string, diffOptions SnapshotDiffOptions, options Options) (SnapshotDiffResult, error) {
	runner, repository, base, env, err := prepareAccess(spec, options)
	if err != nil {
		return SnapshotDiffResult{}, err
	}
	src, err := ResolveSnapshot(spec, source, options)
	if err != nil {
		return SnapshotDiffResult{}, err
	}
	dst, err := ResolveSnapshot(spec, target, options)
	if err != nil {
		return SnapshotDiffResult{}, err
	}
	limit := normalizeLimit(diffOptions.MaxChanges, DefaultSnapshotDiffLimit, MaxSnapshotDiffLimit)
	args := append([]string{"--json"}, base...)
	args = append(args, "diff")
	if diffOptions.Metadata {
		args = append(args, "--metadata")
	}
	args = append(args, src.ID, dst.ID)
	stdout, stderr, runErr := runner.Run(args, env)
	if runErr != nil {
		return SnapshotDiffResult{}, external("comparing Restic snapshots", repository, stderr, runErr)
	}
	out := SnapshotDiffResult{Success: true, Repository: repository, Source: src.ID, Target: dst.ID, Changes: []DiffChange{}, Limit: limit}
	for lineNumber, line := range bytes.Split(stdout, []byte{'\n'}) {
		line = bytes.TrimSpace(line)
		if len(line) == 0 {
			continue
		}
		var head struct {
			MessageType string `json:"message_type"`
		}
		if err := json.Unmarshal(line, &head); err != nil {
			return SnapshotDiffResult{}, berror.External("Restic diff returned invalid JSON", map[string]any{"tool": "restic", "line": lineNumber + 1, "reason": err.Error()})
		}
		switch head.MessageType {
		case "change":
			var item struct {
				Path     string `json:"path"`
				Modifier string `json:"modifier"`
			}
			if err := json.Unmarshal(line, &item); err != nil {
				return SnapshotDiffResult{}, berror.External("Restic diff returned a malformed change", map[string]any{"tool": "restic", "line": lineNumber + 1, "reason": err.Error()})
			}
			if strings.TrimSpace(item.Path) == "" || strings.TrimSpace(item.Modifier) == "" {
				return SnapshotDiffResult{}, berror.External("Restic diff returned an incomplete change", map[string]any{"tool": "restic", "line": lineNumber + 1})
			}
			if len(out.Changes) < limit {
				out.Changes = append(out.Changes, DiffChange{Path: item.Path, Modifier: item.Modifier})
			} else {
				out.Truncated = true
			}
		case "statistics":
			var stats struct {
				SourceSnapshot string   `json:"source_snapshot"`
				TargetSnapshot string   `json:"target_snapshot"`
				ChangedFiles   int64    `json:"changed_files"`
				Added          DiffStat `json:"added"`
				Removed        DiffStat `json:"removed"`
			}
			if err := json.Unmarshal(line, &stats); err != nil {
				return SnapshotDiffResult{}, berror.External("Restic diff returned malformed statistics", map[string]any{"tool": "restic", "line": lineNumber + 1, "reason": err.Error()})
			}
			out.Statistics = DiffStatistics{SourceSnapshot: stats.SourceSnapshot, TargetSnapshot: stats.TargetSnapshot, ChangedFiles: stats.ChangedFiles, Added: stats.Added, Removed: stats.Removed}
		}
	}
	if out.Statistics.ChangedFiles == 0 && len(out.Changes) > 0 {
		out.Statistics.ChangedFiles = int64(len(out.Changes))
	}
	return out, nil
}

func RestoreSnapshot(spec models.RepositoryAccessSpec, selector string, restore RestoreOptions, options Options) (RestoreResult, error) {
	started := time.Now()
	runner, repository, base, env, err := prepareAccess(spec, options)
	if err != nil {
		return RestoreResult{}, err
	}
	snapshot, err := ResolveSnapshot(spec, selector, options)
	if err != nil {
		return RestoreResult{}, err
	}
	includes, err := validation.NormalizePatterns(restore.Includes, "includes")
	if err != nil {
		return RestoreResult{}, err
	}
	excludes, err := validation.NormalizePatterns(restore.Excludes, "excludes")
	if err != nil {
		return RestoreResult{}, err
	}
	restore.Includes = includes
	restore.Excludes = excludes
	if len(restore.Includes) > 0 && len(restore.Excludes) > 0 {
		return RestoreResult{}, berror.Validation("snapshot restore accepts include or exclude filters, not both", nil)
	}
	target := strings.TrimSpace(restore.Target)
	if target == "" {
		return RestoreResult{}, berror.Validation("restore target must not be empty", nil)
	}
	abs, err := filepath.Abs(target)
	if err != nil {
		return RestoreResult{}, berror.Validation("invalid restore target", map[string]any{"target": target, "reason": err.Error()})
	}
	overwrite := strings.TrimSpace(restore.Overwrite)
	if overwrite == "" {
		overwrite = "never"
	}
	switch overwrite {
	case "always", "if-changed", "if-newer", "never":
	default:
		return RestoreResult{}, berror.Validation("overwrite must be one of always, if-changed, if-newer, never", map[string]any{"overwrite": overwrite})
	}
	args := append([]string{"--json"}, base...)
	args = append(args, "restore", snapshot.ID, "--target", abs, "--overwrite", overwrite)
	for _, pattern := range restore.Includes {
		args = append(args, "--include", pattern)
	}
	for _, pattern := range restore.Excludes {
		args = append(args, "--exclude", pattern)
	}
	if restore.DryRun {
		args = append(args, "--dry-run")
	}
	if options.Cancelled != nil && options.Cancelled() {
		return RestoreResult{}, ErrCancelled
	}
	out := RestoreResult{Success: true, Repository: repository, SnapshotID: snapshot.ID, Target: abs, DryRun: restore.DryRun, Includes: append([]string(nil), restore.Includes...), Excludes: append([]string(nil), restore.Excludes...), Overwrite: overwrite}
	parseLine := func(line []byte) {
		line = bytes.TrimSpace(line)
		if len(line) == 0 {
			return
		}
		var head struct {
			MessageType string `json:"message_type"`
		}
		if json.Unmarshal(line, &head) != nil {
			return
		}
		switch head.MessageType {
		case "status", "summary":
			var status struct {
				PercentDone   float64 `json:"percent_done"`
				TotalFiles    int64   `json:"total_files"`
				FilesRestored int64   `json:"files_restored"`
				FilesSkipped  int64   `json:"files_skipped"`
				TotalBytes    int64   `json:"total_bytes"`
				BytesRestored int64   `json:"bytes_restored"`
				BytesSkipped  int64   `json:"bytes_skipped"`
			}
			if json.Unmarshal(line, &status) != nil {
				return
			}
			out.TotalFiles = status.TotalFiles
			out.FilesRestored = status.FilesRestored
			out.FilesSkipped = status.FilesSkipped
			out.TotalBytes = status.TotalBytes
			out.BytesRestored = status.BytesRestored
			out.BytesSkipped = status.BytesSkipped
			if head.MessageType == "status" {
				progress := status.PercentDone
				if progress < 0 {
					progress = 0
				}
				if progress > 1 {
					progress = 1
				}
				emitRestore(options, "restoring", status.FilesRestored, status.TotalFiles, status.BytesRestored, status.TotalBytes, &progress)
			}
		}
	}
	emitRestore(options, "restoring", 0, 0, 0, 0, nil)
	if streaming, ok := runner.(StreamingRunner); ok {
		stderr, runErr := streaming.RunStreaming(args, env, options.Cancelled, parseLine)
		if runErr != nil {
			if errors.Is(runErr, ErrCancelled) {
				return RestoreResult{}, ErrCancelled
			}
			return RestoreResult{}, external("restoring Restic snapshot", repository, stderr, runErr)
		}
	} else {
		stdout, stderr, runErr := runner.Run(args, env)
		if runErr != nil {
			return RestoreResult{}, external("restoring Restic snapshot", repository, stderr, runErr)
		}
		for _, line := range bytes.Split(stdout, []byte{'\n'}) {
			parseLine(line)
		}
	}
	out.DurationMS = time.Since(started).Milliseconds()
	return out, nil
}

func emitRestore(options Options, stage string, files, totalFiles, bytes, totalBytes int64, progress *float64) {
	if options.Progress != nil {
		options.Progress(ProgressEvent{Stage: stage, Files: files, TotalFiles: totalFiles, Bytes: bytes, TotalBytes: totalBytes, Progress: progress})
	}
}

func CheckRepository(spec models.RepositoryAccessSpec, check CheckOptions, options Options) (MaintenanceResult, error) {
	started := time.Now()
	runner, repository, base, env, err := prepareAccess(spec, options)
	if err != nil {
		return MaintenanceResult{}, err
	}
	if check.ReadData && strings.TrimSpace(check.ReadDataSubset) != "" {
		return MaintenanceResult{}, berror.Validation("use either read_data or read_data_subset, not both", nil)
	}
	args := append([]string{}, base...)
	args = append(args, "check")
	if check.ReadData {
		args = append(args, "--read-data")
	}
	if v := strings.TrimSpace(check.ReadDataSubset); v != "" {
		args = append(args, "--read-data-subset", v)
	}
	emitRetentionProgress(options, "preparing", 0, 0)
	_, stderr, runErr := runWithStaleLockRecovery(runner, args, env, base, options.Cancelled, nil, nil)
	if runErr != nil {
		if errors.Is(runErr, ErrCancelled) {
			return MaintenanceResult{}, ErrCancelled
		}
		return MaintenanceResult{}, external("checking Restic repository", repository, stderr, runErr)
	}
	return MaintenanceResult{Success: true, Repository: repository, Operation: "check", DurationMS: time.Since(started).Milliseconds()}, nil
}

func ForgetSnapshot(spec models.RepositoryAccessSpec, selector string, options Options) (MaintenanceResult, error) {
	started := time.Now()
	selector = strings.TrimSpace(selector)
	if selector == "latest" || strings.HasPrefix(selector, "latest~") {
		return MaintenanceResult{}, berror.Validation("BKX refuses relative selectors for snapshot forget; provide an explicit snapshot ID", nil)
	}
	runner, repository, base, env, err := prepareAccess(spec, options)
	if err != nil {
		return MaintenanceResult{}, err
	}
	snapshot, err := ResolveSnapshot(spec, selector, options)
	if err != nil {
		return MaintenanceResult{}, err
	}
	args := append([]string{}, base...)
	args = append(args, "forget", snapshot.ID)
	emitRetentionProgress(options, "preparing", 0, 0)
	_, stderr, runErr := runWithStaleLockRecovery(runner, args, env, base, options.Cancelled, nil, nil)
	if runErr != nil {
		if errors.Is(runErr, ErrCancelled) {
			return MaintenanceResult{}, ErrCancelled
		}
		return MaintenanceResult{}, external("forgetting Restic snapshot", repository, stderr, runErr)
	}
	return MaintenanceResult{Success: true, Repository: repository, Operation: "forget", SnapshotID: snapshot.ID, DurationMS: time.Since(started).Milliseconds()}, nil
}

func retentionArgs(policy models.RetentionPolicy) ([]string, error) {
	if policy.SchemaVersion == 0 {
		policy.SchemaVersion = models.RetentionPolicySchemaVersion
	}
	if err := models.ValidateRetentionPolicy(policy); err != nil {
		return nil, berror.Validation("invalid retention policy", map[string]any{"reason": err.Error()})
	}
	args := []string{}
	addInt := func(flag string, v *int) {
		if v != nil {
			args = append(args, flag, fmt.Sprint(*v))
		}
	}
	addInt("--keep-last", policy.KeepLast)
	addInt("--keep-hourly", policy.KeepHourly)
	addInt("--keep-daily", policy.KeepDaily)
	addInt("--keep-weekly", policy.KeepWeekly)
	addInt("--keep-monthly", policy.KeepMonthly)
	addInt("--keep-yearly", policy.KeepYearly)
	if policy.KeepWithin != nil {
		args = append(args, "--keep-within", *policy.KeepWithin)
	}
	for _, tag := range policy.KeepTags {
		args = append(args, "--keep-tag", tag)
	}
	if strings.TrimSpace(policy.GroupBy) != "" {
		args = append(args, "--group-by", policy.GroupBy)
	}
	return args, nil
}

func ApplyRetention(spec models.RepositoryAccessSpec, policy models.RetentionPolicy, dryRun bool, options Options) (MaintenanceResult, error) {
	started := time.Now()
	runner, repository, base, env, err := prepareAccess(spec, options)
	if err != nil {
		return MaintenanceResult{}, err
	}
	policyArgs, err := retentionArgs(policy)
	if err != nil {
		return MaintenanceResult{}, err
	}
	args := append([]string{}, base...)
	args = append(args, "forget")
	args = append(args, policyArgs...)
	if dryRun {
		args = append(args, "--dry-run")
	}
	emitRetentionProgress(options, "preparing", 0, 0)
	_, stderr, runErr := runWithStaleLockRecovery(runner, args, env, base, options.Cancelled, nil, nil)
	if runErr != nil {
		if errors.Is(runErr, ErrCancelled) {
			return MaintenanceResult{}, ErrCancelled
		}
		return MaintenanceResult{}, external("applying Restic retention policy", repository, stderr, runErr)
	}
	emitRetentionProgress(options, "complete", 1, 1)
	return MaintenanceResult{Success: true, Repository: repository, Operation: "retention", DryRun: dryRun, DurationMS: time.Since(started).Milliseconds()}, nil
}

func PruneRepository(spec models.RepositoryAccessSpec, prune PruneOptions, options Options) (MaintenanceResult, error) {
	started := time.Now()
	runner, repository, base, env, err := prepareAccess(spec, options)
	if err != nil {
		return MaintenanceResult{}, err
	}
	args := append([]string{}, base...)
	args = append(args, "prune")
	if prune.DryRun {
		args = append(args, "--dry-run")
	}
	if v := strings.TrimSpace(prune.MaxUnused); v != "" {
		args = append(args, "--max-unused", v)
	}
	if v := strings.TrimSpace(prune.MaxRepackSize); v != "" {
		args = append(args, "--max-repack-size", v)
	}
	emitRetentionProgress(options, "preparing", 0, 0)
	_, stderr, runErr := runWithStaleLockRecovery(runner, args, env, base, options.Cancelled, nil, nil)
	if runErr != nil {
		if errors.Is(runErr, ErrCancelled) {
			return MaintenanceResult{}, ErrCancelled
		}
		return MaintenanceResult{}, external("pruning Restic repository", repository, stderr, runErr)
	}
	return MaintenanceResult{Success: true, Repository: repository, Operation: "prune", DryRun: prune.DryRun, DurationMS: time.Since(started).Milliseconds()}, nil
}

func emitRetentionProgress(options Options, stage string, current, total int64) {
	if options.Progress != nil {
		options.Progress(ProgressEvent{Stage: stage, Files: current, TotalFiles: total})
	}
}
