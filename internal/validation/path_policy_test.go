package validation

import (
	"path/filepath"
	"testing"
)

func TestBR23ContentUrisAreNotRemoteLocations(t *testing.T) {
	if IsRemoteLocation("content://provider/document/1") {
		t.Fatal("content URI was treated as a remote transfer location instead of requiring staging")
	}
	if _, err := NormalizeTransferLocation("content://provider/document/1", "source"); err == nil {
		t.Fatal("content URI transfer location was accepted")
	}
}

func TestBR23SafeLocalPathPolicyRejectsProtectedDestinations(t *testing.T) {
	for _, path := range []string{"/", "/proc/self", "/sdcard/Android/data/com.example", "/storage/emulated/0/Android/obb/com.example", "/data/system/users/0"} {
		if _, err := RequireSafeLocalWriteDestination(path); err == nil {
			t.Fatalf("protected destination accepted: %s", path)
		}
	}
}

func TestBR23SafeReadRejectsContentAndPseudoFilesystem(t *testing.T) {
	if _, err := RequireSafeLocalReadPath("content://provider/document/1"); err == nil {
		t.Fatal("content URI read path accepted")
	}
	if _, err := RequireSafeLocalReadPath("/proc/self/status"); err == nil {
		t.Fatal("pseudo filesystem read path accepted")
	}
}

func TestBR23StorageCapacityProbeAcceptsTempDestination(t *testing.T) {
	dest := filepath.Join(t.TempDir(), "archive.bakery")
	if err := EnsureCapacityForWriteDestination(dest, 1); err != nil {
		t.Fatalf("temp destination rejected: %v", err)
	}
}

func TestBR36AndroidPrivatePackageRootParsing(t *testing.T) {
	cases := map[string]string{
		"/data/data/com.termux/files/usr/tmp/output":  "/data/data/com.termux",
		"/data/user/0/com.example/files/output":       "/data/user/0/com.example",
		"/data/user_de/10/com.example/cache/output":   "/data/user_de/10/com.example",
		"/data/system/users/0/settings.xml":           "",
		"/storage/emulated/0/Download/archive.bakery": "",
	}
	for input, want := range cases {
		if got := androidPrivatePackageRoot(input); got != want {
			t.Fatalf("androidPrivatePackageRoot(%q)=%q want %q", input, got, want)
		}
	}
}
