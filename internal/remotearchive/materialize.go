package remotearchive

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"

	"bkx/internal/rcloneops"
)

var splitSuffix = regexp.MustCompile(`^\.part([0-9]{3,})$`)

type Progress struct {
	FilesDone  int64
	FilesTotal int64
	BytesDone  int64
	BytesTotal int64
}

type Options struct {
	Cancelled func() bool
	Progress  func(Progress)
}

type Materialized struct {
	Path       string
	Source     string
	Remote     bool
	PartCount  int
	cleanupDir string
}

func (m Materialized) Cleanup() error {
	if m.cleanupDir == "" {
		return nil
	}
	return os.RemoveAll(m.cleanupDir)
}

func Acquire(ctx context.Context, archive string, options Options) (Materialized, error) {
	if !rcloneops.IsLocation(archive) {
		return Materialized{Path: archive, Source: archive, PartCount: 1}, nil
	}
	runtime, err := rcloneops.RuntimeFromEnvironment()
	if err != nil {
		return Materialized{}, err
	}
	parent, err := rcloneops.ParentURI(archive)
	if err != nil {
		return Materialized{}, err
	}
	baseName := rcloneops.BaseName(archive)
	if baseName == "" || baseName == "." || baseName == "/" || strings.ContainsRune(baseName, '\x00') {
		return Materialized{}, fmt.Errorf("invalid remote archive file name")
	}
	entries, err := runtime.List(ctx, parent)
	if err != nil {
		return Materialized{}, fmt.Errorf("list remote archive parts: %w", err)
	}
	type candidate struct {
		index int
		name  string
		size  int64
	}
	candidates := make([]candidate, 0, 4)
	for _, entry := range entries {
		if entry.IsDir {
			continue
		}
		switch {
		case entry.Name == baseName:
			candidates = append(candidates, candidate{index: 1, name: entry.Name, size: entry.Size})
		case strings.HasPrefix(entry.Name, baseName+".part"):
			suffix := strings.TrimPrefix(entry.Name, baseName)
			match := splitSuffix.FindStringSubmatch(suffix)
			if match == nil {
				continue
			}
			n, convErr := strconv.Atoi(match[1])
			if convErr != nil || n < 2 {
				continue
			}
			candidates = append(candidates, candidate{index: n, name: entry.Name, size: entry.Size})
		}
	}
	sort.Slice(candidates, func(i, j int) bool { return candidates[i].index < candidates[j].index })
	if len(candidates) == 0 || candidates[0].index != 1 {
		return Materialized{}, fmt.Errorf("remote archive does not exist: %s", archive)
	}
	for i, part := range candidates {
		expected := i + 1
		if part.index != expected {
			return Materialized{}, fmt.Errorf("remote split archive is missing part %03d", expected)
		}
	}

	tempDir, err := os.MkdirTemp("", "bkx-remote-archive-")
	if err != nil {
		return Materialized{}, fmt.Errorf("create remote archive staging directory: %w", err)
	}
	localBase := filepath.Join(tempDir, baseName)
	totalBytes := int64(0)
	for _, part := range candidates {
		if part.size > 0 {
			totalBytes += part.size
		}
	}
	emit(options, Progress{FilesTotal: int64(len(candidates)), BytesTotal: totalBytes})
	var bytesDone int64
	for i, part := range candidates {
		if cancelled(options) || ctx.Err() != nil {
			_ = os.RemoveAll(tempDir)
			return Materialized{}, context.Canceled
		}
		remotePart, joinErr := rcloneops.JoinURI(parent, part.name)
		if joinErr != nil {
			_ = os.RemoveAll(tempDir)
			return Materialized{}, joinErr
		}
		localPart := localBase
		if part.index > 1 {
			localPart += fmt.Sprintf(".part%03d", part.index)
		}
		_, copyErr := runtime.CopyTo(ctx, remotePart, localPart, rcloneops.TransferOptions{
			Overwrite: true,
			Cancelled: options.Cancelled,
			Progress: func(p rcloneops.Progress) {
				emit(options, Progress{
					FilesDone: int64(i), FilesTotal: int64(len(candidates)),
					BytesDone: bytesDone + p.BytesDone, BytesTotal: totalBytes,
				})
			},
		})
		if copyErr != nil {
			_ = os.RemoveAll(tempDir)
			return Materialized{}, fmt.Errorf("download remote archive part %s: %w", part.name, copyErr)
		}
		if info, statErr := os.Stat(localPart); statErr == nil {
			bytesDone += info.Size()
		} else {
			bytesDone += part.size
		}
		emit(options, Progress{FilesDone: int64(i + 1), FilesTotal: int64(len(candidates)), BytesDone: bytesDone, BytesTotal: totalBytes})
	}
	return Materialized{Path: localBase, Source: archive, Remote: true, PartCount: len(candidates), cleanupDir: tempDir}, nil
}

func cancelled(options Options) bool { return options.Cancelled != nil && options.Cancelled() }
func emit(options Options, progress Progress) {
	if options.Progress != nil {
		options.Progress(progress)
	}
}
