package validation

import "testing"

func TestBR32SplitSizeHasSafeMinimum(t *testing.T) {
	tooSmall := MinSplitSizeBytes - 1
	if _, err := ValidateSplitSize(&tooSmall); err == nil {
		t.Fatal("split size below the safe minimum was accepted")
	}
	minimum := MinSplitSizeBytes
	if got, err := ValidateSplitSize(&minimum); err != nil || got == nil || *got != minimum {
		t.Fatalf("minimum split size rejected: got=%v err=%v", got, err)
	}
}
