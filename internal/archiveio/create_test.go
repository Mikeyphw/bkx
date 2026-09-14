package archiveio

import (
	"os"
	"os/exec"
	"path/filepath"
	"testing"

	"bkx/internal/models"
	"bkx/internal/validation"
)

func createSpec(src, out, algorithm string, level int) models.ArchiveCreateSpec {
	return models.ArchiveCreateSpec{
		Sources: []string{src}, OutputPath: out,
		Includes: []string{}, Excludes: []string{},
		Compression: models.CompressionProfile{Algorithm: algorithm, Level: level},
		Verify:      true,
	}
}

func fixtureTree(t *testing.T) string {
	t.Helper()
	root := filepath.Join(t.TempDir(), "source")
	if err := os.MkdirAll(filepath.Join(root, "empty"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(root, "nested"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "a.txt"), []byte("alpha"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "nested", "b.txt"), []byte("bravo"), 0o640); err != nil {
		t.Fatal(err)
	}
	return root
}

func TestCreateTarAndZipTransactionalVerified(t *testing.T) {
	src := fixtureTree(t)
	for _, tc := range []struct {
		name, alg string
		level     int
	}{
		{"out.tar", "none", 0}, {"out.tar.gz", "gzip", 1}, {"out.tar.zst", "zstd", 3}, {"out.zip", "gzip", 6}, {"out-zstd.zip", "zstd", 3},
	} {
		t.Run(tc.name, func(t *testing.T) {
			out := filepath.Join(t.TempDir(), tc.name)
			got, err := Create(createSpec(src, out, tc.alg, tc.level), false, nil)
			if err != nil {
				t.Fatal(err)
			}
			if !got.Success || got.DryRun || got.FilesArchived != 2 || got.OriginalSize != 10 {
				t.Fatalf("unexpected result: %+v", got)
			}
			if got.VerificationPassed == nil || !*got.VerificationPassed {
				t.Fatalf("verification not passed: %+v", got)
			}
			verified, err := Verify(out, []string{"sha256"})
			if err != nil || !verified.Success {
				t.Fatalf("verify=%+v err=%v", verified, err)
			}
			listed, err := List(out, nil, 0, 100)
			if err != nil {
				t.Fatal(err)
			}
			if listed.TotalEntries != 5 {
				t.Fatalf("entries=%d want 5: %+v", listed.TotalEntries, listed.Entries)
			}
		})
	}
}

func TestCreateOptionalCodecsWhenToolsPresent(t *testing.T) {
	src := fixtureTree(t)
	cases := []struct {
		name, alg, tool string
		level           int
	}{
		{"out.tar.bz2", "bzip2", "bzip2", 5}, {"out.tar.xz", "xz", "xz", 3},
		{"out-bz.zip", "bzip2", "bzip2", 5}, {"out-xz.zip", "xz", "xz", 3},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := exec.LookPath(tc.tool); err != nil {
				t.Skipf("optional tool %s unavailable", tc.tool)
			}
			out := filepath.Join(t.TempDir(), tc.name)
			got, err := Create(createSpec(src, out, tc.alg, tc.level), false, nil)
			if err != nil {
				t.Fatal(err)
			}
			if !got.Success || got.VerificationPassed == nil || !*got.VerificationPassed {
				t.Fatalf("unexpected result: %+v", got)
			}
		})
	}
}

func TestCreateImplicitZstdUsesNativeCodec(t *testing.T) {
	src := fixtureTree(t)
	for _, name := range []string{"default.tar", "default.zip"} {
		out := filepath.Join(t.TempDir(), name)
		got, err := Create(createSpec(src, out, "zstd", 3), true, nil)
		if err != nil {
			t.Fatal(err)
		}
		if len(got.Warnings) != 0 {
			t.Fatalf("native zstd must not be downgraded: warnings=%v", got.Warnings)
		}
		verified, err := Verify(out, []string{"sha256"})
		if err != nil || !verified.Success {
			t.Fatalf("verify=%+v err=%v", verified, err)
		}
	}
}

func TestCreateUnsupportedFailsClosed(t *testing.T) {
	src := fixtureTree(t)
	out := filepath.Join(t.TempDir(), "explicit.tar")
	if _, err := Create(createSpec(src, out, "lz4", 3), false, nil); err == nil {
		t.Fatal("unsupported lz4 unexpectedly succeeded")
	}
	if _, err := os.Stat(out); !os.IsNotExist(err) {
		t.Fatalf("output should not exist: %v", err)
	}
}

func TestCreateSplitArchivesAreTransparentToListVerifyAndRestore(t *testing.T) {
	src := fixtureTree(t)
	blob := make([]byte, int(validation.MinSplitSizeBytes*3+4096))
	// Use a deterministic xorshift stream instead of a short-period arithmetic
	// pattern. The latter compresses so aggressively that a nominal 3x-split
	// fixture may legitimately produce only one compressed part.
	state := uint64(0x9e3779b97f4a7c15)
	for i := range blob {
		state ^= state << 13
		state ^= state >> 7
		state ^= state << 17
		blob[i] = byte(state >> 56)
	}
	if err := os.WriteFile(filepath.Join(src, "blob.bin"), blob, 0o600); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name string
		alg  string
	}{
		{name: "split.tar.zst", alg: "zstd"},
		{name: "split.zip", alg: "zstd"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			split := validation.MinSplitSizeBytes
			out := filepath.Join(t.TempDir(), tc.name)
			spec := createSpec(src, out, tc.alg, 3)
			spec.SplitSize = &split
			got, err := Create(spec, false, nil)
			if err != nil {
				t.Fatal(err)
			}
			if len(got.SplitParts) < 2 || got.SplitParts[0] != out {
				t.Fatalf("split parts=%v", got.SplitParts)
			}
			for _, part := range got.SplitParts {
				info, err := os.Stat(part)
				if err != nil {
					t.Fatal(err)
				}
				if info.Size() > split {
					t.Fatalf("part %s is %d bytes, exceeds split size %d", part, info.Size(), split)
				}
			}
			verified, err := Verify(out, []string{"sha256"})
			if err != nil || !verified.Success {
				t.Fatalf("verify=%+v err=%v", verified, err)
			}
			listed, err := List(out, nil, 0, 100)
			if err != nil || listed.TotalEntries != 6 {
				t.Fatalf("list=%+v err=%v", listed, err)
			}

			restoreDir := filepath.Join(t.TempDir(), "restore")
			result, err := ExtractRestore(models.RestoreSpec{
				Archive: out, Destination: &restoreDir, Paths: []string{}, Verify: true, FreshDir: true,
			}, nil)
			if err != nil || !result.Success || result.FilesRestored != 6 {
				t.Fatalf("restore=%+v err=%v", result, err)
			}
		})
	}
}

func TestCreateDryRunAndExistingOutput(t *testing.T) {
	src := fixtureTree(t)
	out := filepath.Join(t.TempDir(), "dry.tar")
	spec := createSpec(src, out, "none", 0)
	spec.DryRun = true
	got, err := Create(spec, false, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !got.DryRun || len(got.Warnings) != 1 {
		t.Fatalf("dry result=%+v", got)
	}
	if _, err := os.Stat(out); !os.IsNotExist(err) {
		t.Fatalf("dry-run output exists: %v", err)
	}

	existing := filepath.Join(t.TempDir(), "existing.tar")
	if err := os.WriteFile(existing, []byte("keep"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Create(createSpec(src, existing, "none", 0), false, nil); err == nil {
		t.Fatal("existing output unexpectedly overwritten")
	}
	data, _ := os.ReadFile(existing)
	if string(data) != "keep" {
		t.Fatalf("existing output changed: %q", data)
	}
}

func TestCreateCleansStagingOnCodecFailure(t *testing.T) {
	src := fixtureTree(t)
	dir := t.TempDir()
	out := filepath.Join(dir, "fail.zip")
	t.Setenv("PATH", "")
	_, err := Create(createSpec(src, out, "bzip2", 5), false, nil)
	if err == nil {
		t.Fatal("missing bzip2 unexpectedly succeeded")
	}
	if _, statErr := os.Stat(out); !os.IsNotExist(statErr) {
		t.Fatalf("output should not exist: %v", statErr)
	}
	matches, globErr := filepath.Glob(filepath.Join(dir, ".fail.zip.*.tmp"))
	if globErr != nil {
		t.Fatal(globErr)
	}
	if len(matches) != 0 {
		t.Fatalf("staging files leaked: %v", matches)
	}
}
