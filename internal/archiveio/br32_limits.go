package archiveio

import (
	"fmt"
	"math"
	"path/filepath"
	"regexp"
	"strings"

	"bkx/internal/berror"
	"bkx/internal/models"
)

const (
	maxArchiveEntries           = 100000
	maxArchiveUncompressedBytes = int64(1 << 40) // 1 TiB logical safety ceiling.
	maxArchiveListPageStart     = math.MaxInt / 2
	maxRestoreResultPaths       = 1000
)

type archiveBudget struct {
	entries int
	bytes   int64
}

func (b *archiveBudget) add(entry Entry) error {
	b.entries++
	if b.entries > maxArchiveEntries {
		return berror.Validation("Archive has too many entries for safe CLI processing", map[string]any{"max_entries": maxArchiveEntries})
	}
	if !entry.IsDir {
		if entry.Size < 0 || b.bytes > maxArchiveUncompressedBytes-entry.Size {
			return berror.Validation("Archive logical size exceeds safe CLI processing budget", map[string]any{"max_bytes": maxArchiveUncompressedBytes})
		}
		b.bytes += entry.Size
	}
	return nil
}

func safePageWindow(page, pageSize, total int) (int, int, error) {
	if page < 0 {
		return 0, 0, berror.Validation("page must be a non-negative integer", map[string]any{"page": page})
	}
	if pageSize < 1 || pageSize > 1000 {
		return 0, 0, berror.Validation("page_size must be between 1 and 1000", map[string]any{"page_size": pageSize})
	}
	if page > maxArchiveListPageStart/pageSize {
		return 0, 0, berror.Validation("page is too large", map[string]any{"page": page, "page_size": pageSize})
	}
	start := page * pageSize
	if start > total {
		start = total
	}
	end := start + pageSize
	if end > total {
		end = total
	}
	return start, end, nil
}

func compileFnmatch(pattern string) (*regexp.Regexp, error) {
	var b strings.Builder
	b.WriteString("(?s)^")
	for i := 0; i < len(pattern); i++ {
		c := pattern[i]
		switch c {
		case '*':
			b.WriteString(".*")
		case '?':
			b.WriteByte('.')
		case '[':
			j := i + 1
			if j < len(pattern) && (pattern[j] == '!' || pattern[j] == '^') {
				j++
			}
			if j < len(pattern) && pattern[j] == ']' {
				j++
			}
			for j < len(pattern) && pattern[j] != ']' {
				j++
			}
			if j >= len(pattern) {
				b.WriteString(`\[`)
				continue
			}
			stuff := pattern[i+1 : j]
			if strings.HasPrefix(stuff, "!") {
				stuff = "^" + stuff[1:]
			}
			b.WriteByte('[')
			b.WriteString(stuff)
			b.WriteByte(']')
			i = j
		default:
			b.WriteString(regexp.QuoteMeta(string(c)))
		}
	}
	b.WriteString("$")
	return regexp.Compile(b.String())
}

func compressionExtensionWarnings(archivePath string, format string, algorithm string) []string {
	lower := strings.ToLower(filepath.Base(archivePath))
	warnings := []string{}
	if format == "zip" && algorithm != "none" && algorithm != "gzip" && algorithm != "zstd" && algorithm != "bzip2" && algorithm != "xz" {
		warnings = append(warnings, "Archive compression algorithm is not recognized for zip output")
	}
	expected := map[string][]string{
		"gzip":  {".tar.gz", ".tgz", ".gz"},
		"bzip2": {".tar.bz2", ".tbz2", ".bz2"},
		"xz":    {".tar.xz", ".txz", ".xz"},
		"zstd":  {".tar.zst", ".zst", ".zip", ".bakery"},
		"none":  {".tar", ".zip", ".bakery"},
	}
	suffixes, ok := expected[algorithm]
	if !ok {
		return warnings
	}
	matched := false
	for _, suffix := range suffixes {
		if strings.HasSuffix(lower, suffix) {
			matched = true
			break
		}
	}
	if !matched {
		warnings = append(warnings, fmt.Sprintf("Compression algorithm %s does not match archive file extension", algorithm))
	}
	return warnings
}

// ResolveCreateProfileForPlan exposes archive-create compression validation to
// CLI dry-run planning so planning and execution reject the same algorithms and
// surface the same extension warnings.
func ResolveCreateProfileForPlan(output string, profile models.CompressionProfile, implicitDefault bool) (models.CompressionProfile, []string, error) {
	format := detectCreateFormat(output)
	resolved, warnings, err := resolveCreateCompression(profile, implicitDefault, output, format)
	if err != nil {
		return models.CompressionProfile{}, nil, err
	}
	if !implicitDefault {
		warnings = append(warnings, compressionExtensionWarnings(output, format, resolved.Algorithm)...)
	}
	return resolved, warnings, nil
}
