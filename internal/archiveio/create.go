package archiveio

import (
	"archive/tar"
	"archive/zip"
	"compress/flate"
	"compress/gzip"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"bkx/internal/berror"
	"bkx/internal/fsutil"
	"bkx/internal/models"
	"bkx/internal/validation"

	"github.com/klauspost/compress/zstd"
)

const (
	zipMethodBzip2 = 12
	zipMethodLZMA  = 14
	zipMethodZstd  = 93
)

type CreateResult struct {
	Success            bool     `json:"success"`
	DryRun             bool     `json:"dry_run"`
	FilesArchived      int      `json:"files_archived"`
	BytesWritten       int64    `json:"bytes_written"`
	OriginalSize       int64    `json:"original_size"`
	ArchivePath        string   `json:"archive_path"`
	SplitParts         []string `json:"split_parts"`
	VerificationPassed *bool    `json:"verification_passed"`
	CompressionRatio   float64  `json:"compression_ratio"`
	Warnings           []string `json:"warnings"`
	Errors             []string `json:"errors"`
	DurationMS         int64    `json:"duration_ms"`
}

func Create(spec models.ArchiveCreateSpec, implicitDefault bool, progress func(int, int64)) (CreateResult, error) {
	started := time.Now()
	validated, err := validation.ValidateArchiveCreateSpec(spec)
	if err != nil {
		return CreateResult{}, err
	}
	output, err := validation.CanonicalLocalPath(validated.OutputPath)
	if err != nil {
		return CreateResult{}, err
	}
	if _, err := os.Lstat(output); err == nil {
		return CreateResult{}, berror.Validation("Archive output already exists", map[string]any{"output_path": output})
	} else if !os.IsNotExist(err) {
		return CreateResult{}, berror.WrapExternal("checking archive output", output, "go-os", err)
	}

	policy := fsutil.SymlinkReject
	if validated.SkipSymlinks {
		policy = fsutil.SymlinkSkip
	}
	manifest, err := fsutil.BuildSourceManifest(validated.Sources, validated.Includes, validated.Excludes, output, policy)
	if err != nil {
		return CreateResult{}, err
	}

	if validated.DryRun {
		return CreateResult{
			Success: true, DryRun: true, ArchivePath: output, SplitParts: []string{},
			Warnings: []string{"Dry run - archive not created"}, Errors: []string{}, DurationMS: time.Since(started).Milliseconds(),
		}, nil
	}

	format := detectCreateFormat(output)
	profile, warnings, err := resolveCreateCompression(validated.Compression, implicitDefault, output, format)
	if err != nil {
		return CreateResult{}, err
	}

	if err := os.MkdirAll(filepath.Dir(output), 0o755); err != nil {
		return CreateResult{}, berror.WrapExternal("creating archive output directory", filepath.Dir(output), "go-os", err)
	}
	tmp, err := os.CreateTemp(filepath.Dir(output), "."+filepath.Base(output)+".*.tmp")
	if err != nil {
		return CreateResult{}, berror.WrapExternal("creating archive staging file", output, "go-os", err)
	}
	tmpPath := tmp.Name()
	stagedPaths := []string{tmpPath}
	published := false
	defer func() {
		if !published {
			removeAll(stagedPaths)
		}
	}()

	var sink io.WriteCloser = tmp
	var splitSink *splitStagingWriter
	if validated.SplitSize != nil {
		splitSink, err = newSplitStagingWriter(tmpPath, tmp, *validated.SplitSize)
		if err != nil {
			_ = tmp.Close()
			return CreateResult{}, err
		}
		sink = splitSink
	}

	if format == "zip" {
		err = writeZipArchive(sink, manifest, profile, progress)
	} else {
		err = writeTarArchive(sink, filepath.Dir(output), manifest, profile, progress)
	}
	closeErr := sink.Close()
	if splitSink != nil {
		stagedPaths = splitSink.Paths()
	}
	if err != nil {
		return CreateResult{}, err
	}
	if closeErr != nil {
		return CreateResult{}, berror.WrapExternal("closing archive staging output", tmpPath, "go-os", closeErr)
	}

	for _, staged := range stagedPaths {
		if err := syncFile(staged); err != nil {
			return CreateResult{}, berror.WrapExternal("syncing archive staging file", staged, "go-os", err)
		}
	}

	var verification *bool
	if validated.Verify {
		result, verifyErr := Verify(tmpPath, []string{"sha256"})
		if verifyErr != nil {
			return CreateResult{}, verifyErr
		}
		ok := result.Success
		verification = &ok
		if !ok {
			return CreateResult{}, berror.Validation("Archive verification failed before publication", map[string]any{"output_path": output, "failed_entries": result.FailedEntries})
		}
	}

	finalPaths := multipartFinalPaths(output, len(stagedPaths))
	if err := publishMultipart(stagedPaths, finalPaths); err != nil {
		return CreateResult{}, berror.WrapExternal("publishing archive", output, "go-os", err)
	}
	published = true

	written, err := multipartTotalSize(finalPaths)
	if err != nil {
		return CreateResult{}, mapPathError(output, err)
	}
	original := manifest.TotalSize()
	ratio := 1.0
	if original > 0 {
		ratio = float64(written) / float64(original)
	}
	splitParts := []string{}
	if validated.SplitSize != nil {
		splitParts = finalPaths
	}
	result := CreateResult{
		Success: true, DryRun: false, FilesArchived: len(manifest.Files()), BytesWritten: written, OriginalSize: original,
		ArchivePath: output, SplitParts: splitParts, VerificationPassed: verification, CompressionRatio: ratio,
		Warnings: warnings, Errors: []string{}, DurationMS: time.Since(started).Milliseconds(),
	}
	if progress != nil {
		progress(result.FilesArchived, result.BytesWritten)
	}
	return result, nil
}

func ParseCreateSpecJSON(raw string) (models.ArchiveCreateSpec, bool, error) {
	var envelope struct {
		Spec            models.ArchiveCreateSpec `json:"spec"`
		ImplicitDefault bool                     `json:"implicit_default"`
	}
	if err := json.Unmarshal([]byte(raw), &envelope); err != nil {
		return models.ArchiveCreateSpec{}, false, berror.Validation("Invalid GO06 archive create JSON", map[string]any{"reason": err.Error()})
	}
	return envelope.Spec, envelope.ImplicitDefault, nil
}

func detectCreateFormat(path string) string {
	for _, suffix := range strings.Split(strings.ToLower(filepath.Base(path)), ".") {
		if suffix == "zip" {
			return "zip"
		}
	}
	return "tar"
}

func resolveCreateCompression(profile models.CompressionProfile, implicitDefault bool, output, format string) (models.CompressionProfile, []string, error) {
	_ = implicitDefault // retained for protocol-v1 call compatibility; native zstd needs no fallback.
	_ = output
	algorithm := profile.Algorithm
	switch algorithm {
	case "none", "gzip", "zstd", "bzip2", "xz":
		return profile, []string{}, nil
	default:
		return models.CompressionProfile{}, nil, berror.Validation(
			fmt.Sprintf("Compression algorithm is unsupported for builtin %s creation", format),
			map[string]any{"algorithm": algorithm, "supported": []string{"none", "gzip", "zstd", "bzip2", "xz"}},
		)
	}
}

func writeTarArchive(out io.Writer, workDir string, manifest fsutil.SourceManifest, profile models.CompressionProfile, progress func(int, int64)) error {
	if profile.Algorithm == "bzip2" || profile.Algorithm == "xz" {
		raw, err := os.CreateTemp(workDir, ".bkx-go06-raw-*.tar")
		if err != nil {
			return err
		}
		rawPath := raw.Name()
		defer os.Remove(rawPath)
		if err := writeTarStream(raw, manifest, "none", 0, progress); err != nil {
			_ = raw.Close()
			return err
		}
		if err := raw.Sync(); err != nil {
			_ = raw.Close()
			return err
		}
		if err := raw.Close(); err != nil {
			return err
		}
		return compressFileExternal(rawPath, out, profile)
	}
	return writeTarStream(out, manifest, profile.Algorithm, profile.Level, progress)
}

func writeTarStream(out io.Writer, manifest fsutil.SourceManifest, algorithm string, level int, progress func(int, int64)) error {
	writer := out
	var compressor io.WriteCloser
	switch algorithm {
	case "gzip":
		gz, err := gzip.NewWriterLevel(out, level)
		if err != nil {
			return err
		}
		compressor = gz
		writer = gz
	case "zstd":
		zst, err := zstd.NewWriter(
			out,
			zstd.WithEncoderLevel(zstd.EncoderLevelFromZstd(level)),
			zstd.WithEncoderConcurrency(1),
		)
		if err != nil {
			return err
		}
		compressor = zst
		writer = zst
	}

	tw := tar.NewWriter(writer)
	filesDone := 0
	var bytesDone int64
	for _, entry := range manifest.Entries {
		info, err := os.Lstat(entry.Path)
		if err != nil {
			_ = tw.Close()
			if compressor != nil {
				_ = compressor.Close()
			}
			return mapPathError(entry.Path, err)
		}
		if entry.IsDir {
			hdr := &tar.Header{Name: strings.TrimSuffix(entry.ArchiveName, "/") + "/", Typeflag: tar.TypeDir, Mode: 0o644, ModTime: time.Unix(0, entry.MtimeNS), Uid: 0, Gid: 0, Uname: "", Gname: ""}
			if err := tw.WriteHeader(hdr); err != nil {
				_ = tw.Close()
				if compressor != nil {
					_ = compressor.Close()
				}
				return err
			}
		} else {
			hdr, err := tar.FileInfoHeader(info, "")
			if err != nil {
				_ = tw.Close()
				if compressor != nil {
					_ = compressor.Close()
				}
				return err
			}
			hdr.Name = entry.ArchiveName
			hdr.Uid, hdr.Gid, hdr.Uname, hdr.Gname = 0, 0, "", ""
			if err := tw.WriteHeader(hdr); err != nil {
				_ = tw.Close()
				if compressor != nil {
					_ = compressor.Close()
				}
				return err
			}
			src, err := os.Open(entry.Path)
			if err != nil {
				_ = tw.Close()
				if compressor != nil {
					_ = compressor.Close()
				}
				return mapPathError(entry.Path, err)
			}
			_, copyErr := io.Copy(tw, src)
			closeErr := src.Close()
			if copyErr != nil {
				_ = tw.Close()
				if compressor != nil {
					_ = compressor.Close()
				}
				return copyErr
			}
			if closeErr != nil {
				_ = tw.Close()
				if compressor != nil {
					_ = compressor.Close()
				}
				return closeErr
			}
			filesDone++
			bytesDone += entry.Size
		}
		if progress != nil {
			progress(filesDone, bytesDone)
		}
	}
	if err := tw.Close(); err != nil {
		if compressor != nil {
			_ = compressor.Close()
		}
		return err
	}
	if compressor != nil {
		return compressor.Close()
	}
	return nil
}

func compressFileExternal(rawPath string, out io.Writer, profile models.CompressionProfile) error {
	program := profile.Algorithm
	args := []string{"-c", fmt.Sprintf("-%d", profile.Level), "--", rawPath}
	exe, err := exec.LookPath(program)
	if err != nil {
		return berror.External("Optional compression tool is required during GO06 migration", map[string]any{"tool": program, "algorithm": profile.Algorithm})
	}
	cmd := exec.Command(exe, args...)
	cmd.Stdout = out
	var stderr strings.Builder
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return berror.External("External compression failed", map[string]any{"tool": program, "reason": strings.TrimSpace(stderr.String())})
	}
	return nil
}

func writeZipArchive(out io.Writer, manifest fsutil.SourceManifest, profile models.CompressionProfile, progress func(int, int64)) error {
	zw := zip.NewWriter(out)
	if profile.Algorithm == "bzip2" {
		zw.RegisterCompressor(zipMethodBzip2, lazyCommandCompressor("bzip2", []string{"-c", fmt.Sprintf("-%d", profile.Level)}, nil))
	}
	if profile.Algorithm == "xz" {
		zw.RegisterCompressor(zipMethodLZMA, lazyCommandCompressor("xz", []string{"--format=raw", "--lzma1=lc=3,lp=0,pb=2,dict=8MiB", "-c"}, []byte{9, 4, 5, 0, 0x5d, 0, 0, 0x80, 0}))
	}
	if profile.Algorithm == "zstd" {
		zw.RegisterCompressor(zipMethodZstd, func(w io.Writer) (io.WriteCloser, error) {
			return zstd.NewWriter(
				w,
				zstd.WithEncoderLevel(zstd.EncoderLevelFromZstd(profile.Level)),
				zstd.WithEncoderConcurrency(1),
			)
		})
	}
	method := uint16(zip.Store)
	switch profile.Algorithm {
	case "gzip":
		method = zip.Deflate
		zw.RegisterCompressor(zip.Deflate, func(w io.Writer) (io.WriteCloser, error) { return flate.NewWriter(w, profile.Level) })
	case "zstd":
		method = zipMethodZstd
	case "bzip2":
		method = zipMethodBzip2
	case "xz":
		method = zipMethodLZMA
	}
	filesDone := 0
	var bytesDone int64
	for _, entry := range manifest.Entries {
		if entry.IsDir {
			hdr := &zip.FileHeader{Name: strings.TrimSuffix(entry.ArchiveName, "/") + "/", Method: zip.Store}
			hdr.SetMode(os.ModeDir | 0o755)
			hdr.SetModTime(time.Date(1980, 1, 1, 0, 0, 0, 0, time.UTC))
			if _, err := zw.CreateHeader(hdr); err != nil {
				_ = zw.Close()
				return err
			}
		} else {
			info, err := os.Stat(entry.Path)
			if err != nil {
				_ = zw.Close()
				return mapPathError(entry.Path, err)
			}
			hdr, err := zip.FileInfoHeader(info)
			if err != nil {
				_ = zw.Close()
				return err
			}
			hdr.Name = entry.ArchiveName
			hdr.Method = method
			dst, err := zw.CreateHeader(hdr)
			if err != nil {
				_ = zw.Close()
				return err
			}
			src, err := os.Open(entry.Path)
			if err != nil {
				_ = zw.Close()
				return mapPathError(entry.Path, err)
			}
			_, copyErr := io.Copy(dst, src)
			closeErr := src.Close()
			if copyErr != nil {
				_ = zw.Close()
				return copyErr
			}
			if closeErr != nil {
				_ = zw.Close()
				return closeErr
			}
			filesDone++
			bytesDone += entry.Size
		}
		if progress != nil {
			progress(filesDone, bytesDone)
		}
	}
	return zw.Close()
}

type lazyCommandWriter struct {
	out      io.Writer
	program  string
	args     []string
	prefix   []byte
	cmd      *exec.Cmd
	stdin    io.WriteCloser
	started  bool
	startErr error
}

func lazyCommandCompressor(program string, args []string, prefix []byte) zip.Compressor {
	return func(out io.Writer) (io.WriteCloser, error) {
		return &lazyCommandWriter{out: out, program: program, args: args, prefix: prefix}, nil
	}
}
func (w *lazyCommandWriter) start() error {
	if w.started {
		return w.startErr
	}
	w.started = true
	exe, err := exec.LookPath(w.program)
	if err != nil {
		w.startErr = berror.External("Optional compression tool is required during GO06 migration", map[string]any{"tool": w.program})
		return w.startErr
	}
	if len(w.prefix) > 0 {
		if _, err := w.out.Write(w.prefix); err != nil {
			w.startErr = err
			return err
		}
	}
	cmd := exec.Command(exe, w.args...)
	cmd.Stdout = w.out
	var stderr strings.Builder
	cmd.Stderr = &stderr
	stdin, err := cmd.StdinPipe()
	if err != nil {
		w.startErr = err
		return err
	}
	if err := cmd.Start(); err != nil {
		w.startErr = err
		return err
	}
	w.cmd, w.stdin = cmd, stdin
	return nil
}
func (w *lazyCommandWriter) Write(p []byte) (int, error) {
	if err := w.start(); err != nil {
		return 0, err
	}
	return w.stdin.Write(p)
}
func (w *lazyCommandWriter) Close() error {
	if err := w.start(); err != nil {
		return err
	}
	err1 := w.stdin.Close()
	err2 := w.cmd.Wait()
	if err1 != nil {
		return err1
	}
	return err2
}

func syncFile(path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	return f.Sync()
}
func syncDir(path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	return f.Sync()
}
