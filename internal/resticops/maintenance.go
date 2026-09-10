package resticops

import (
	"bytes"
	"encoding/json"
	"fmt"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"bkx/internal/berror"
	"bkx/internal/models"
)

type SnapshotListOptions struct {
	Host string
	Path string
	Tags []string
}

type SnapshotListResult struct {
	Success    bool                        `json:"success"`
	Repository string                      `json:"repository"`
	Snapshots  []models.SnapshotDescriptor `json:"snapshots"`
	Total      int                         `json:"total"`
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
}

type RestoreOptions struct {
	Target    string
	Includes  []string
	Excludes  []string
	Overwrite string
	DryRun    bool
}

type RestoreResult struct {
	Success    bool     `json:"success"`
	Repository string   `json:"repository"`
	SnapshotID string   `json:"snapshot_id"`
	Target     string   `json:"target"`
	DryRun     bool     `json:"dry_run"`
	Includes   []string `json:"includes,omitempty"`
	Excludes   []string `json:"excludes,omitempty"`
	Overwrite  string   `json:"overwrite"`
	DurationMS int64    `json:"duration_ms"`
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
		Size     *uint64  `json:"size"`
	}
	if err := json.Unmarshal(stdout, &raw); err != nil {
		return SnapshotListResult{}, berror.External("Restic snapshots returned invalid JSON", map[string]any{"tool": "restic", "reason": err.Error()})
	}
	snapshots := make([]models.SnapshotDescriptor, 0, len(raw))
	for _, item := range raw {
		short := item.ShortID
		if short == "" && len(item.ID) >= 8 {
			short = item.ID[:8]
		}
		snapshots = append(snapshots, models.SnapshotDescriptor{ID: item.ID, ShortID: short, Time: item.Time, Hostname: item.Hostname, Username: item.Username, Paths: append([]string(nil), item.Paths...), Tags: append([]string(nil), item.Tags...), Size: item.Size})
	}
	sort.SliceStable(snapshots, func(i, j int) bool { return snapshots[i].Time > snapshots[j].Time })
	return SnapshotListResult{Success: true, Repository: repository, Snapshots: snapshots, Total: len(snapshots)}, nil
}

func ResolveSnapshot(spec models.RepositoryAccessSpec, selector string, options Options) (models.SnapshotDescriptor, error) {
	value := strings.TrimSpace(selector)
	if value == "" {
		return models.SnapshotDescriptor{}, berror.Validation("snapshot selector must not be empty", nil)
	}
	listed, err := ListSnapshots(spec, SnapshotListOptions{}, options)
	if err != nil {
		return models.SnapshotDescriptor{}, err
	}
	if len(listed.Snapshots) == 0 {
		return models.SnapshotDescriptor{}, berror.NotFound("No snapshots exist in this repository", nil)
	}
	if value == "latest" {
		return listed.Snapshots[0], nil
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

func DiffSnapshots(spec models.RepositoryAccessSpec, source, target string, metadata bool, options Options) (SnapshotDiffResult, error) {
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
	args := append([]string{"--json"}, base...)
	args = append(args, "diff")
	if metadata {
		args = append(args, "--metadata")
	}
	args = append(args, src.ID, dst.ID)
	stdout, stderr, runErr := runner.Run(args, env)
	if runErr != nil {
		return SnapshotDiffResult{}, external("comparing Restic snapshots", repository, stderr, runErr)
	}
	out := SnapshotDiffResult{Success: true, Repository: repository, Source: src.ID, Target: dst.ID, Changes: []DiffChange{}}
	for _, line := range bytes.Split(stdout, []byte{'\n'}) {
		line = bytes.TrimSpace(line)
		if len(line) == 0 {
			continue
		}
		var head struct {
			MessageType string `json:"message_type"`
		}
		if json.Unmarshal(line, &head) != nil {
			continue
		}
		switch head.MessageType {
		case "change":
			var item struct {
				Path     string `json:"path"`
				Modifier string `json:"modifier"`
			}
			if json.Unmarshal(line, &item) == nil {
				out.Changes = append(out.Changes, DiffChange{Path: item.Path, Modifier: item.Modifier})
			}
		case "statistics":
			var stats struct {
				SourceSnapshot string   `json:"source_snapshot"`
				TargetSnapshot string   `json:"target_snapshot"`
				ChangedFiles   int64    `json:"changed_files"`
				Added          DiffStat `json:"added"`
				Removed        DiffStat `json:"removed"`
			}
			if json.Unmarshal(line, &stats) == nil {
				out.Statistics = DiffStatistics{SourceSnapshot: stats.SourceSnapshot, TargetSnapshot: stats.TargetSnapshot, ChangedFiles: stats.ChangedFiles, Added: stats.Added, Removed: stats.Removed}
			}
		}
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
	args := append([]string{}, base...)
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
	emit(options, "restoring", 0, 0)
	_, stderr, runErr := runner.Run(args, env)
	if runErr != nil {
		return RestoreResult{}, external("restoring Restic snapshot", repository, stderr, runErr)
	}
	return RestoreResult{Success: true, Repository: repository, SnapshotID: snapshot.ID, Target: abs, DryRun: restore.DryRun, Includes: append([]string(nil), restore.Includes...), Excludes: append([]string(nil), restore.Excludes...), Overwrite: overwrite, DurationMS: time.Since(started).Milliseconds()}, nil
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
	_, stderr, runErr := runner.Run(args, env)
	if runErr != nil {
		return MaintenanceResult{}, external("checking Restic repository", repository, stderr, runErr)
	}
	return MaintenanceResult{Success: true, Repository: repository, Operation: "check", DurationMS: time.Since(started).Milliseconds()}, nil
}

func ForgetSnapshot(spec models.RepositoryAccessSpec, selector string, options Options) (MaintenanceResult, error) {
	started := time.Now()
	if strings.TrimSpace(selector) == "latest" {
		return MaintenanceResult{}, berror.Validation("BKX refuses 'latest' for snapshot forget; provide an explicit snapshot ID", nil)
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
	_, stderr, runErr := runner.Run(args, env)
	if runErr != nil {
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
	_, stderr, runErr := runner.Run(args, env)
	if runErr != nil {
		return MaintenanceResult{}, external("applying Restic retention policy", repository, stderr, runErr)
	}
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
	_, stderr, runErr := runner.Run(args, env)
	if runErr != nil {
		return MaintenanceResult{}, external("pruning Restic repository", repository, stderr, runErr)
	}
	return MaintenanceResult{Success: true, Repository: repository, Operation: "prune", DryRun: prune.DryRun, DurationMS: time.Since(started).Milliseconds()}, nil
}
