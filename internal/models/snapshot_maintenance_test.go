package models

import "testing"

func TestRetentionPolicyRejectsEmptyAndZeroCounts(t *testing.T) {
	if err := ValidateRetentionPolicy(RetentionPolicy{SchemaVersion: 1}); err == nil {
		t.Fatal("expected empty policy rejection")
	}
	zero := 0
	if err := ValidateRetentionPolicy(RetentionPolicy{SchemaVersion: 1, KeepLast: &zero}); err == nil {
		t.Fatal("expected zero count rejection")
	}
	seven := 7
	if err := ValidateRetentionPolicy(RetentionPolicy{SchemaVersion: 1, KeepDaily: &seven}); err != nil {
		t.Fatal(err)
	}
}
