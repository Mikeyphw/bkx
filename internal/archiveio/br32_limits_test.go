package archiveio

import (
	"math"
	"testing"

	"bkx/internal/models"
)

func TestBR32ArchivePagingRejectsOverflowBeforeOpeningArchive(t *testing.T) {
	if _, _, err := safePageWindow(math.MaxInt, 2, 0); err == nil {
		t.Fatal("oversized page window was accepted")
	}
}

func TestBR32CompressionExtensionWarningsRespectExplicitness(t *testing.T) {
	profile := models.CompressionProfile{Algorithm: "zstd", Level: 3}
	_, warnings, err := ResolveCreateProfileForPlan("backup.tar", profile, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(warnings) == 0 {
		t.Fatal("explicit zstd compression on .tar should warn about extension mismatch")
	}
	_, implicitWarnings, err := ResolveCreateProfileForPlan("backup.tar", profile, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(implicitWarnings) != 0 {
		t.Fatalf("implicit/default compression should not create copy warnings: %v", implicitWarnings)
	}
}

func TestBR32RestorePathResultIsBounded(t *testing.T) {
	paths := make([]string, maxRestoreResultPaths+3)
	for i := range paths {
		paths[i] = "file"
	}
	bounded, truncated := boundRestorePaths(paths)
	if !truncated || len(bounded) != maxRestoreResultPaths {
		t.Fatalf("bounded=%d truncated=%v", len(bounded), truncated)
	}
}
