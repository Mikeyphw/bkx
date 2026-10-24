package compatfixtures

import (
	"bufio"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// These are deliberately maintained Android release, validation, and test utilities.
// GO20 retired the Python *application*; it did not retire Devtool/Android
// operator tooling. A new Python source file needs an explicit policy review.
var reviewedOperationalPython = map[string]bool{
	"scripts/bkx-audit-rm16-receipts.py":                  true,
	"scripts/bkx-devtool-life.py":                         true,
	"scripts/bkx-devtool-release.py":                      true,
	"scripts/bkx-rel07-build-matching-instrumentation.py": true,
	"scripts/bkx-rel07-qualify.py":                        true,
	"scripts/bkx-rel08-evidence.py":                       true,
	"scripts/bkx_apksigner_certificate.py":                true,
	"scripts/produce-bkx-unsigned-candidates.py":          true,
	"scripts/test-android-editions.py":                    true,
	"scripts/test-bkx-go-android-executable.py":           true,
	"scripts/test-bkx-rel03-gates.py":                     true,
	"scripts/test-bkx-rel0405-producer.py":                true,
	"scripts/test-bkx-rel05-agp-metadata-resolution.py":   true,
	"scripts/test-bkx-rel05-apk-packaging-memory.py":      true,
	"scripts/test-bkx-rel05-dispatch-422.py":              true,
	"scripts/test-bkx-rel05-hf04-native-abi-contract.py":  true,
	"scripts/test-bkx-rel06-handoff.py":                   true,
	"scripts/test-bkx-rel07-hf01-termux-signer.py":        true,
	"scripts/test-bkx-rel07-hf02-modern-package-path.py":  true,
	"scripts/test-bkx-rel07-qualification.py":             true,
	"scripts/test-bkx-rel08-evidence.py":                  true,
	"scripts/test-bkx-rel09-ci-hf01.py":                   true,
	"scripts/test-bkx-rel09-consolidated.py":              true,
	"scripts/verify-android-editions.py":                  true,
	"scripts/verify-bclone-source-lock.py":                true,
	"scripts/verify-bkx-go-android-executable.py":         true,
	"scripts/verify-bkx-rel03-gates.py":                   true,
	"scripts/verify-bkx-rel0405-producer.py":              true,
	"scripts/verify-bkx-rel06-handoff.py":                 true,
	"scripts/verify-bkx-rel07-qualification.py":           true,
	"scripts/verify-bkx-rel08-evidence.py":                true,
}

func allowedOperationalPython(rel string) bool {
	return reviewedOperationalPython[rel]
}

// The Android Go runtime builder may invoke only this reviewed, fail-closed
// ELF contract validator. Any second interpreter call or changed invocation
// must be approved separately, not hidden behind a broad Python exemption.
func reviewedRuntimePythonHook(text string) bool {
	const invocation = `python3 "$repo/scripts/verify-bkx-go-android-executable.py" "$tmp"`
	return strings.Count(text, "python3") == 1 && strings.Count(text, invocation) == 1
}

func TestRepositoryPythonPurgeSeal(t *testing.T) {
	root := filepath.Clean(filepath.Join("..", ".."))

	forbiddenFiles := map[string]bool{
		"pyproject.toml":     true,
		"setup.cfg":          true,
		"setup.py":           true,
		"mypy.ini":           true,
		"pyrightconfig.json": true,
	}
	skipDirs := map[string]bool{
		".git":                 true,
		".devtool":             true,
		"artifacts":            true,
		"build":                true,
		"dist":                 true,
		".gradle":              true,
		".kotlin":              true,
		".cxx":                 true,
		".externalNativeBuild": true,
		"__pycache__":          true,
		".pytest_cache":        true,
		".mypy_cache":          true,
		".ruff_cache":          true,
		".pyright":             true,
		".venv":                true,
		"venv":                 true,
		// The deterministic GO16 archive is historical evidence, not an active path.
		filepath.ToSlash(filepath.Join("migration", "archive")): true,
	}

	err := filepath.WalkDir(root, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		rel = filepath.ToSlash(rel)
		if rel == "." {
			return nil
		}
		if entry.IsDir() {
			if skipDirs[rel] || skipDirs[entry.Name()] {
				return filepath.SkipDir
			}
			return nil
		}
		for _, prefix := range []string{"src/bkx/", "tests/", "tools/go_migration/"} {
			if strings.HasPrefix(rel, prefix) {
				t.Errorf("forbidden Python-era active file remains: %s", rel)
			}
		}
		if forbiddenFiles[rel] || forbiddenFiles[entry.Name()] {
			t.Errorf("forbidden Python project file remains active: %s", rel)
		}
		// Refuse Python runtime/application and stray tooling, but allow only
		// the explicitly reviewed Devtool/Android support scripts.
		lower := strings.ToLower(entry.Name())
		if strings.HasSuffix(lower, ".pyc") || (strings.HasSuffix(lower, ".py") && !allowedOperationalPython(rel)) {
			t.Errorf("unreviewed Python source/bytecode remains: %s", rel)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}

	mustNotContain := map[string][]string{
		".devtool.toml": {
			"bkx_reference",
			"[python]",
			"[python.package]",
			"python = \"python3\"",
		},
		".github/workflows/ci.yml": {
			"setup-python",
			"python3",
			"pip install",
			"pytest",
			"src/bkx",
		},
		"scripts/build-go-release.sh": {
			"python3",
			"PYTHONPATH",
			"src/bkx",
			"pyproject.toml",
		},
		"scripts/build-go-android-runtime.sh": {
			"PYTHONPATH",
			"src/bkx",
			"pyproject.toml",
		},
		"build-logic/android-verification.gradle.kts": {
			"python3",
			"PYTHONPATH",
			"src/bkx",
			"pyproject.toml",
		},
		"android/BKX.Android/app/build.gradle.kts": {
			"python3",
			"PYTHONPATH",
			"src/bkx",
			"pyproject.toml",
		},
	}
	for rel, needles := range mustNotContain {
		data, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(rel)))
		if err != nil {
			t.Fatalf("read %s: %v", rel, err)
		}
		text := string(data)
		for _, needle := range needles {
			if strings.Contains(text, needle) {
				t.Errorf("active file %s contains forbidden Python dependency marker %q", rel, needle)
			}
		}
	}

	// Android's verified Go executable needs its strict ELF interpreter/page
	// inspector; do not mistakenly classify this as the retired Python app.
	goBuilder, err := os.ReadFile(filepath.Join(root, "scripts/build-go-android-runtime.sh"))
	if err != nil {
		t.Fatal(err)
	}
	if !reviewedRuntimePythonHook(string(goBuilder)) {
		t.Error("Android Go runtime builder does not use exactly the reviewed ELF Python verifier")
	}

	devtool, err := os.Open(filepath.Join(root, ".devtool.toml"))
	if err != nil {
		t.Fatal(err)
	}
	defer devtool.Close()
	scanner := bufio.NewScanner(devtool)
	sawGoTarget := false
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "[targets.bkx]" {
			sawGoTarget = true
		}
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	if !sawGoTarget {
		t.Fatal("Go production Devtool target [targets.bkx] is missing")
	}
}
