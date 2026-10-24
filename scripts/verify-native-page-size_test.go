package main

import (
	"archive/zip"
	"os"
	"path/filepath"
	"testing"
)

func apkFixture(t *testing.T, members []string, weak string) string {
	t.Helper()
	dir := t.TempDir()
	good := filepath.Join(dir, "good.so")
	bad := filepath.Join(dir, "bad.so")
	if err := writeFixture(good, 0x4000, 0x4000, 0x8000); err != nil {
		t.Fatal(err)
	}
	if err := writeFixture(bad, 0x1000, 0x1000, 0x2000); err != nil {
		t.Fatal(err)
	}
	gp, err := os.ReadFile(good)
	if err != nil {
		t.Fatal(err)
	}
	bp, err := os.ReadFile(bad)
	if err != nil {
		t.Fatal(err)
	}
	apk := filepath.Join(dir, "test.apk")
	f, err := os.Create(apk)
	if err != nil {
		t.Fatal(err)
	}
	z := zip.NewWriter(f)
	for _, m := range members {
		w, err := z.Create(m)
		if err != nil {
			t.Fatal(err)
		}
		content := []byte("verified source provenance")
		if len(m) >= 3 && m[len(m)-3:] == ".so" {
			content = gp
			if m == weak {
				content = bp
			}
		}
		if _, err := w.Write(content); err != nil {
			t.Fatal(err)
		}
	}
	if err := z.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
	return apk
}

func TestApkEditionContract(t *testing.T) {
	goLib := "lib/arm64-v8a/libbkxgo.so"
	rc := "lib/arm64-v8a/libbkxrclone_exec.so"
	prop := "assets/bkx-rclone-runtime.properties"
	cases := []struct {
		name, edition string
		members       []string
		weak          string
		expected      int
	}{
		{"embedded_ok", "embedded", []string{goLib, rc, prop}, "", 0},
		{"lite_ok", "lite", []string{goLib}, "", 0},
		{"embedded_missing_rclone", "embedded", []string{goLib}, "", 1},
		{"embedded_missing_provenance", "embedded", []string{goLib, rc}, "", 1},
		{"lite_contains_rclone", "lite", []string{goLib, rc}, "", 1},
		{"lite_contains_provenance", "lite", []string{goLib, prop}, "", 1},
		{"lite_missing_go", "lite", []string{rc}, "", 1},
		{"embedded_missing_go", "embedded", []string{rc, prop}, "", 1},
		{"lite_wrong_abi", "lite", []string{goLib, "lib/armeabi-v7a/libother.so"}, "", 1},
		{"lite_bad_page_alignment", "lite", []string{goLib}, goLib, 1},
		{"embedded_bad_rclone_alignment", "embedded", []string{goLib, rc, prop}, rc, 1},
		{"lite_duplicate_native_member", "lite", []string{goLib, goLib}, "", 1},
		{"lite_bad_extra_native_elf", "lite", []string{goLib, "lib/arm64-v8a/libother.so"}, "lib/arm64-v8a/libother.so", 1},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			apk := apkFixture(t, c.members, c.weak)
			code := apkCommand([]string{"--edition", c.edition, "--min-page-size", "16384", apk})
			if code != c.expected {
				t.Fatalf("edition=%s: returned %d, want %d", c.edition, code, c.expected)
			}
		})
	}
}

func TestApkRejectsImplicitEditionAndInvalidSelection(t *testing.T) {
	apk := apkFixture(t, []string{"lib/arm64-v8a/libbkxgo.so"}, "")
	for _, args := range [][]string{
		{apk},
		{"--edition", "release", apk},
		{"--edition", "lite"},
		{"--edition", "lite", "--min-page-size", "4097", apk},
		{"--edition", "lite", apk, apk},
	} {
		if got := apkCommand(args); got != 2 {
			t.Fatalf("unsafe arguments %v returned %d (expected 2)", args, got)
		}
	}
}
