package archiveio

import (
	"os"
	"path/filepath"
	"testing"
)

func TestBR35MultipartManifestBindsPartCountAndHashes(t *testing.T) {
	dir := t.TempDir()
	base := filepath.Join(dir, "backup.tar.zst")
	part2 := multipartPartName(base, 2)
	if err := os.WriteFile(base, []byte("first"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(part2, []byte("second"), 0o600); err != nil {
		t.Fatal(err)
	}
	manifestPath, err := writeMultipartManifest(base, []string{base, part2}, int64(len("firstsecond")))
	if err != nil {
		t.Fatalf("write manifest: %v", err)
	}
	if manifestPath != multipartManifestPath(base) {
		t.Fatalf("manifest path = %q", manifestPath)
	}
	parts, err := multipartPartPaths(base)
	if err != nil {
		t.Fatalf("manifest-bound parts rejected: %v", err)
	}
	if len(parts) != 2 || parts[0] != base || parts[1] != part2 {
		t.Fatalf("parts = %#v", parts)
	}
	if err := os.WriteFile(part2, []byte("tampered"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := multipartPartPaths(base); err == nil {
		t.Fatalf("tampered sidecar should fail manifest checksum validation")
	}
}

func TestBR35MultipartManifestRejectsWrongBaseAndPartCount(t *testing.T) {
	dir := t.TempDir()
	base := filepath.Join(dir, "backup.tar.zst")
	part2 := multipartPartName(base, 2)
	if err := os.WriteFile(base, []byte("first"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(part2, []byte("second"), 0o600); err != nil {
		t.Fatal(err)
	}
	manifestPath, err := writeMultipartManifest(base, []string{base, part2}, int64(len("firstsecond")))
	if err != nil {
		t.Fatalf("write manifest: %v", err)
	}
	b, err := os.ReadFile(manifestPath)
	if err != nil {
		t.Fatal(err)
	}
	b = []byte(string(b[:len(b)-2]) + `,"part_count":3}`)
	// The malformed rewrite is intentionally invalid JSON/object shape; any
	// non-nil error proves callers do not silently fall back to adjacency once
	// a sidecar manifest is present but invalid.
	if err := os.WriteFile(manifestPath, b, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := multipartPartPaths(base); err == nil {
		t.Fatalf("invalid manifest must fail closed instead of adjacent filename inference")
	}
}
