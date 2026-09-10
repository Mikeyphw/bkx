package resticops

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"bkx/internal/berror"
	"bkx/internal/models"
	"bkx/internal/runstore"
)

var ErrCancelled = errors.New("restic backup cancelled")

type Runner interface {
	Run(args []string, env []string) (stdout []byte, stderr []byte, err error)
}

type ExecRunner struct{ Path string }

func (r ExecRunner) Run(args []string, env []string) ([]byte, []byte, error) {
	cmd := exec.Command(r.Path, args...)
	cmd.Env = env
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	return stdout.Bytes(), stderr.Bytes(), err
}

type Options struct {
	Runner    Runner
	Environ   []string
	RunStore  *runstore.Store
	RunID     string
	Cancelled func() bool
	Progress  func(ProgressEvent)
}

type ProgressEvent struct {
	Stage string `json:"stage"`
	Files int64  `json:"files"`
	Bytes int64  `json:"bytes"`
}

type Plan struct {
	Engine     string   `json:"engine"`
	Repository string   `json:"repository"`
	Sources    []string `json:"sources"`
	Excludes   []string `json:"excludes"`
	Tags       []string `json:"tags"`
	Verify     bool     `json:"verify"`
	DryRun     bool     `json:"dry_run"`
	Warnings   []string `json:"warnings"`
}

type BackupResult struct {
	Success            bool     `json:"success"`
	Engine             string   `json:"engine"`
	Repository         string   `json:"repository"`
	SnapshotID         string   `json:"snapshot_id,omitempty"`
	FilesProcessed     int64    `json:"files_processed"`
	BytesProcessed     int64    `json:"bytes_processed"`
	DataAdded          int64    `json:"data_added"`
	VerificationPassed *bool    `json:"verification_passed,omitempty"`
	Warnings           []string `json:"warnings"`
	DurationMS         int64    `json:"duration_ms"`
}

type RepositoryResult struct {
	Success    bool   `json:"success"`
	Engine     string `json:"engine"`
	Repository string `json:"repository"`
	Created    bool   `json:"created"`
}

type summaryMessage struct {
	MessageType         string `json:"message_type"`
	SnapshotID          string `json:"snapshot_id"`
	TotalFilesProcessed int64  `json:"total_files_processed"`
	TotalBytesProcessed int64  `json:"total_bytes_processed"`
	DataAdded           int64  `json:"data_added"`
}

type statusMessage struct {
	MessageType string  `json:"message_type"`
	FilesDone   int64   `json:"files_done"`
	BytesDone   int64   `json:"bytes_done"`
	PercentDone float64 `json:"percent_done"`
}

func ResolveRunner(options Options) (Runner, error) {
	if options.Runner != nil {
		return options.Runner, nil
	}
	path, err := exec.LookPath("restic")
	if err != nil {
		return nil, berror.External("restic is required for incremental snapshot backups", map[string]any{"tool": "restic", "action": "Install restic and ensure it is available on PATH."})
	}
	return ExecRunner{Path: path}, nil
}

func NormalizeLocalRepository(raw string) (string, error) {
	value := strings.TrimSpace(raw)
	if value == "" {
		return "", berror.Validation("Restic repository must not be empty", nil)
	}
	if strings.Contains(value, "://") || strings.HasPrefix(value, "rclone:") || strings.HasPrefix(value, "s3:") || strings.HasPrefix(value, "sftp:") {
		return "", berror.Validation("BR06 supports local Restic repositories only; remote/cloud repositories arrive in BR07+BR08", map[string]any{"repository": value})
	}
	abs, err := filepath.Abs(value)
	if err != nil {
		return "", berror.Validation("Invalid local Restic repository path", map[string]any{"repository": value, "reason": err.Error()})
	}
	return filepath.Clean(abs), nil
}

func credentialEnvironment(base []string, ref *string) ([]string, error) {
	env := append([]string(nil), base...)
	if len(env) == 0 {
		env = os.Environ()
	}
	lookup := func(key string) (string, bool) {
		prefix := key + "="
		for i := len(env) - 1; i >= 0; i-- {
			if strings.HasPrefix(env[i], prefix) {
				return strings.TrimPrefix(env[i], prefix), true
			}
		}
		return "", false
	}
	if ref != nil {
		id := strings.TrimSpace(*ref)
		normalized := regexp.MustCompile(`[^A-Za-z0-9]+`).ReplaceAllString(strings.ToUpper(id), "_")
		if secret, ok := lookup("BKX_CREDENTIAL_" + normalized); ok && secret != "" {
			env = append(env, "RESTIC_PASSWORD="+secret)
			return env, nil
		}
		if file, ok := lookup("BKX_CREDENTIAL_FILE_" + normalized); ok && strings.TrimSpace(file) != "" {
			env = append(env, "RESTIC_PASSWORD_FILE="+file)
			return env, nil
		}
		return nil, berror.Validation("Restic credential reference is unresolved", map[string]any{"credential_ref": id, "expected_env": "BKX_CREDENTIAL_" + normalized})
	}
	if value, ok := lookup("RESTIC_PASSWORD"); ok && value != "" {
		return env, nil
	}
	if value, ok := lookup("RESTIC_PASSWORD_FILE"); ok && strings.TrimSpace(value) != "" {
		return env, nil
	}
	return nil, berror.Validation("Restic requires a password supplied outside the operation spec", map[string]any{"action": "Set RESTIC_PASSWORD/RESTIC_PASSWORD_FILE or use --credential-ref with BKX_CREDENTIAL_<ID>."})
}

func Init(spec models.RepositoryInitSpec, options Options) (RepositoryResult, error) {
	if err := models.ValidateRepositoryInitSpec(spec); err != nil {
		return RepositoryResult{}, berror.Validation("Invalid repository init specification", map[string]any{"reason": err.Error()})
	}
	repo, err := NormalizeLocalRepository(spec.Repository)
	if err != nil {
		return RepositoryResult{}, err
	}
	runner, err := ResolveRunner(options)
	if err != nil {
		return RepositoryResult{}, err
	}
	env, err := credentialEnvironment(options.Environ, spec.CredentialRef)
	if err != nil {
		return RepositoryResult{}, err
	}
	if err := os.MkdirAll(filepath.Dir(repo), 0o700); err != nil {
		return RepositoryResult{}, berror.WrapExternal("creating Restic repository parent", filepath.Dir(repo), "filesystem", err)
	}
	_, stderr, runErr := runner.Run([]string{"--repo", repo, "init"}, env)
	if runErr != nil {
		return RepositoryResult{}, external("initializing Restic repository", repo, stderr, runErr)
	}
	return RepositoryResult{Success: true, Engine: models.BackupEngineRestic, Repository: repo, Created: true}, nil
}

func PlanBackup(spec models.SnapshotBackupSpec) (Plan, error) {
	normalized, err := normalizeBackup(spec)
	if err != nil {
		return Plan{}, err
	}
	warnings := []string{}
	if normalized.Verify {
		warnings = append(warnings, "Repository verification runs after the snapshot; full data-read verification is reserved for repository maintenance.")
	}
	return Plan{Engine: models.BackupEngineRestic, Repository: normalized.Repository, Sources: normalized.Sources, Excludes: normalized.Excludes, Tags: normalized.Tags, Verify: normalized.Verify, DryRun: true, Warnings: warnings}, nil
}

func Backup(spec models.SnapshotBackupSpec, options Options) (BackupResult, error) {
	started := time.Now()
	normalized, err := normalizeBackup(spec)
	if err != nil {
		return BackupResult{}, err
	}
	if normalized.DryRun {
		return BackupResult{Success: true, Engine: models.BackupEngineRestic, Repository: normalized.Repository, Warnings: []string{}, DurationMS: time.Since(started).Milliseconds()}, nil
	}
	runner, err := ResolveRunner(options)
	if err != nil {
		return BackupResult{}, err
	}
	env, err := credentialEnvironment(options.Environ, normalized.CredentialRef)
	if err != nil {
		return BackupResult{}, err
	}
	runID := options.RunID
	if runID == "" {
		runID = randomRunID()
	}
	destination := normalized.Repository
	if options.RunStore != nil {
		snapshot := map[string]any{
			"engine": models.BackupEngineRestic, "repository": normalized.Repository,
			"sources": append([]string(nil), normalized.Sources...), "excludes": append([]string(nil), normalized.Excludes...),
			"includes": append([]string(nil), normalized.Includes...), "tags": append([]string(nil), normalized.Tags...),
			"verify": normalized.Verify, "dry_run": false,
		}
		if normalized.CredentialRef != nil {
			snapshot["credential_ref"] = *normalized.CredentialRef
		}
		if _, err := options.RunStore.RecordStart(runID, "backup", normalized.Sources, &destination, nil, snapshot); err != nil {
			return BackupResult{}, err
		}
	}
	complete := func(status string, result *BackupResult, opErr error) {
		if options.RunStore == nil {
			return
		}
		var files, bytes *int64
		var msg *string
		if result != nil {
			files = ptr(result.FilesProcessed)
			bytes = ptr(result.BytesProcessed)
		}
		if opErr != nil {
			text := opErr.Error()
			msg = &text
		}
		_, _ = options.RunStore.RecordCompletion(runID, status, files, bytes, msg)
	}
	if options.Cancelled != nil && options.Cancelled() {
		complete("interrupted", nil, ErrCancelled)
		return BackupResult{}, ErrCancelled
	}
	args := []string{"--json", "--repo", normalized.Repository, "backup"}
	for _, pattern := range normalized.Excludes {
		args = append(args, "--exclude", pattern)
	}
	for _, tag := range normalized.Tags {
		args = append(args, "--tag", tag)
	}
	args = append(args, normalized.Sources...)
	emit(options, "snapshotting", 0, 0)
	stdout, stderr, runErr := runner.Run(args, env)
	if runErr != nil {
		wrapped := external("creating Restic snapshot", normalized.Repository, stderr, runErr)
		complete("failed", nil, wrapped)
		return BackupResult{}, wrapped
	}
	summary, err := parseBackupJSON(stdout, options)
	if err != nil {
		complete("failed", nil, err)
		return BackupResult{}, err
	}
	result := BackupResult{Success: true, Engine: models.BackupEngineRestic, Repository: normalized.Repository, SnapshotID: summary.SnapshotID, FilesProcessed: summary.TotalFilesProcessed, BytesProcessed: summary.TotalBytesProcessed, DataAdded: summary.DataAdded, Warnings: []string{}}
	if normalized.Verify {
		emit(options, "verifying", result.FilesProcessed, result.BytesProcessed)
		_, checkErrText, checkErr := runner.Run([]string{"--repo", normalized.Repository, "check"}, env)
		ok := checkErr == nil
		result.VerificationPassed = &ok
		if checkErr != nil {
			wrapped := external("checking Restic repository", normalized.Repository, checkErrText, checkErr)
			result.Success = false
			result.DurationMS = time.Since(started).Milliseconds()
			complete("failed", &result, wrapped)
			return result, wrapped
		}
	}
	result.DurationMS = time.Since(started).Milliseconds()
	complete("success", &result, nil)
	return result, nil
}

func normalizeBackup(spec models.SnapshotBackupSpec) (models.SnapshotBackupSpec, error) {
	if strings.TrimSpace(spec.Engine) == "" {
		spec.Engine = models.BackupEngineRestic
	}
	if err := models.ValidateSnapshotBackupSpec(spec); err != nil {
		return spec, berror.Validation("Invalid Restic snapshot backup specification", map[string]any{"reason": err.Error()})
	}
	if len(spec.Includes) > 0 {
		return spec, berror.Validation("Restic include filters are not enabled in BR06; use explicit sources plus exclusions", map[string]any{"includes": spec.Includes})
	}
	repo, err := NormalizeLocalRepository(spec.Repository)
	if err != nil {
		return spec, err
	}
	spec.Repository = repo
	seen := map[string]bool{}
	sources := make([]string, 0, len(spec.Sources))
	for _, raw := range spec.Sources {
		abs, err := filepath.Abs(strings.TrimSpace(raw))
		if err != nil {
			return spec, berror.Validation("Invalid snapshot source path", map[string]any{"source": raw})
		}
		abs = filepath.Clean(abs)
		st, err := os.Stat(abs)
		if err != nil {
			if os.IsNotExist(err) {
				return spec, berror.NotFound("Snapshot source does not exist: "+abs, map[string]any{"source": abs})
			}
			return spec, berror.WrapExternal("checking snapshot source", abs, "filesystem", err)
		}
		if !st.IsDir() && !st.Mode().IsRegular() {
			return spec, berror.Validation("Snapshot source must be a file or directory", map[string]any{"source": abs})
		}
		if seen[abs] {
			return spec, berror.Validation("Duplicate snapshot sources are not allowed", map[string]any{"source": abs})
		}
		seen[abs] = true
		sources = append(sources, abs)
	}
	spec.Sources = sources
	return spec, nil
}

func parseBackupJSON(data []byte, options Options) (summaryMessage, error) {
	var summary summaryMessage
	for _, line := range bytes.Split(data, []byte{'\n'}) {
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
		case "status":
			var status statusMessage
			if json.Unmarshal(line, &status) == nil {
				emit(options, "snapshotting", status.FilesDone, status.BytesDone)
			}
		case "summary":
			_ = json.Unmarshal(line, &summary)
		}
	}
	if summary.MessageType != "summary" || strings.TrimSpace(summary.SnapshotID) == "" {
		return summary, berror.External("Restic backup completed without a snapshot summary", map[string]any{"tool": "restic"})
	}
	return summary, nil
}

func emit(options Options, stage string, files, bytes int64) {
	if options.Progress != nil {
		options.Progress(ProgressEvent{Stage: stage, Files: files, Bytes: bytes})
	}
}
func ptr(v int64) *int64 { return &v }
func randomRunID() string {
	b := make([]byte, 12)
	if _, err := rand.Read(b); err == nil {
		return hex.EncodeToString(b)
	}
	return fmt.Sprintf("restic-%d", time.Now().UnixNano())
}
func external(action, resource string, stderr []byte, err error) error {
	text := strings.TrimSpace(string(stderr))
	details := map[string]any{"tool": "restic", "resource": resource, "reason": err.Error()}
	if text != "" {
		details["stderr"] = text
	}
	return berror.External("Restic failed while "+action, details)
}
