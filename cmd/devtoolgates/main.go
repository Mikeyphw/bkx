// Command devtoolgates validates local source contracts without Python, Android builds,
// root access, broker traffic, or release qualification. Do not weaken repository-purity.
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const moduleName = "bkx"

type check struct {
	label  string
	passed bool
}

func repoRoot() (string, error) {
	dir, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for {
		data, err := os.ReadFile(filepath.Join(dir, "go.mod"))
		if err == nil && strings.HasPrefix(string(data), "module "+moduleName+"\n") {
			return dir, nil
		}
		next := filepath.Dir(dir)
		if next == dir {
			return "", fmt.Errorf("could not locate %s repository root", moduleName)
		}
		dir = next
	}
}

func read(root, relative string) (string, error) {
	path := filepath.Join(root, filepath.FromSlash(relative))
	info, err := os.Lstat(path)
	if err != nil {
		return "", fmt.Errorf("%s: %w", relative, err)
	}
	if !info.Mode().IsRegular() || info.Size() == 0 {
		return "", fmt.Errorf("%s: regular nonempty file required (symlinks denied)", relative)
	}
	bytes, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("%s: %w", relative, err)
	}
	if len(strings.TrimSpace(string(bytes))) == 0 {
		return "", fmt.Errorf("%s: empty file", relative)
	}
	return string(bytes), nil
}

func allChecks(checks []check) error {
	var failed []string
	for _, c := range checks {
		if !c.passed {
			failed = append(failed, c.label)
		}
	}
	if len(failed) > 0 {
		return fmt.Errorf("failed source contracts: %s", strings.Join(failed, ", "))
	}
	return nil
}

func containsAll(haystack string, needles ...string) bool {
	for _, needle := range needles {
		if !strings.Contains(haystack, needle) {
			return false
		}
	}
	return true
}

const (
	bkxClient = "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/r1/SkybindR1ReadClient.kt"
	bkxScreen = "android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/more/RcloneRemotesScreen.kt"
	bkxTest   = "android/BKX.Android/app/src/androidTest/java/com/mikeyphw/bkx/android/r1/SkybindR1ClientSafetyInstrumentationTest.kt"
)

func actionsChecks(root string) ([]check, error) {
	files := []string{".github/workflows/ci.yml", ".github/workflows/devtool-android-build.yml", ".github/workflows/devtool-bkx-go-build.yml"}
	contents := make(map[string]string, len(files))
	for _, path := range files {
		s, err := read(root, path)
		if err != nil {
			return nil, err
		}
		contents[path] = s
	}
	ci := contents[files[0]]
	debug := contents[files[1]]
	return []check{
		{"WORKFLOW_FILES_NONEMPTY_NO_SYMLINK", len(contents) == len(files)},
		{"CI_HOSTED_ANDROID_V4", containsAll(ci, "android-actions/setup-android@v4", "packages: 'platforms;android-36 build-tools;36.0.0 platform-tools ndk;29.0.14206865'", "log-accepted-android-sdk-licenses: 'false'") && !strings.Contains(ci, "android-actions/setup-android@v3")},
		{"CI_SDK_NDK_VERIFICATION", containsAll(ci, "platforms/android-36/android.jar", "build-tools/36.0.0/apksigner", "ndk/29.0.14206865/source.properties")},
		{"HOSTED_DEBUG_V4_SOURCE_BINDING", containsAll(debug, "android-actions/setup-android@v4", "git rev-parse HEAD", "verify-native-page-size.go", "apksigner", "BKX_BUILD_COMMIT") && !strings.Contains(debug, "android-actions/setup-android@v3")},
		{"CI_GO_NATIVE_R1_CONTRACT", containsAll(ci, "go run ./cmd/devtoolgates actions", "go run ./cmd/devtoolgates r1")},
	}, nil
}

func r1Checks(root string) ([]check, error) {
	client, err := read(root, bkxClient)
	if err != nil {
		return nil, err
	}
	screen, err := read(root, bkxScreen)
	if err != nil {
		return nil, err
	}
	tests, err := read(root, bkxTest)
	if err != nil {
		return nil, err
	}
	return []check{
		{"REAL_BKX_UI_CALLS_CLIENT", strings.Contains(screen, "SkybindR1ReadClient.read(op, remote, path)")},
		{"READS_RUN_OFF_MAIN", strings.Contains(screen, "withContext(Dispatchers.IO)")},
		{"APP_UID_SOCKET_NOT_ROOT_PROCESS", strings.Contains(client, "android.net.LocalSocket") && !strings.Contains(client, "su -c")},
		{"EXACT_READONLY_SET", containsAll(client, "rclone.lsjson", "rclone.size", "rclone.listremotes", "rclone.version", "system.capabilities")},
		{"OPERATOR_GRANT_REQUIRED", containsAll(client, "GRANTS", "skybind-r1-grant-handoff-v1")},
		{"NO_UNAUTHORIZED_NATIVE_FALLBACK", !strings.Contains(strings.ToLower(client), "rclone copy") && !strings.Contains(client, "ProcessBuilder")},
		{"VALIDATE_BEFORE_NETWORK", containsAll(client, "require(validPath(path))", "require(operation in operations)")},
		{"MALICIOUS_ARGUMENT_TESTS", containsAll(tests, "deniesWriteOperationsBeforeConnecting", "deniesPathEscapeBeforeConnecting")},
		{"EXACT_HANDOFF_GATEWAY", containsAll(client, "skybind-r1-consumer-gateway-v1", "LocalSocketAddress.Namespace.ABSTRACT")},
	}, nil
}

func main() {
	if len(os.Args) != 2 || (os.Args[1] != "actions" && os.Args[1] != "r1") {
		fmt.Fprintln(os.Stderr, "usage: go run ./cmd/devtoolgates {actions|r1}")
		os.Exit(2)
	}
	root, err := repoRoot()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	var checks []check
	if os.Args[1] == "actions" {
		checks, err = actionsChecks(root)
	} else {
		checks, err = r1Checks(root)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "FAIL_SOURCE_INPUT:", err)
		os.Exit(2)
	}
	for _, c := range checks {
		verdict := "PASS_"
		if !c.passed {
			verdict = "FAIL_"
		}
		fmt.Println(verdict + c.label)
	}
	if err = allChecks(checks); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	fmt.Println("PASS_" + strings.ToUpper(moduleName) + "_" + strings.ToUpper(os.Args[1]) + "_SOURCE_ONLY_NOT_DEVICE_OR_RELEASE")
}
