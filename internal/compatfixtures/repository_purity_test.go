package compatfixtures

import (
	"bufio"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

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
		if strings.HasSuffix(strings.ToLower(entry.Name()), ".py") || strings.HasSuffix(strings.ToLower(entry.Name()), ".pyc") {
			t.Errorf("active Python source/bytecode remains: %s", rel)
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
			"python3",
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
