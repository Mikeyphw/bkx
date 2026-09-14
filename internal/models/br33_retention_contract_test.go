package models

import "testing"

func TestBR33RetentionAcceptsResticNativeGroupsAndUntaggedKeep(t *testing.T) {
	last := 2
	within := "7d"
	for _, group := range []string{"host", "tags", "none"} {
		policy := RetentionPolicy{SchemaVersion: RetentionPolicySchemaVersion, KeepLast: &last, KeepWithin: &within, KeepTags: []string{""}, GroupBy: group}
		if err := ValidateRetentionPolicy(policy); err != nil {
			t.Fatalf("group %q should be accepted: %v", group, err)
		}
	}
}

func TestBR33RetentionRejectsInvalidKeepWithinSyntax(t *testing.T) {
	bad := "seven days"
	policy := RetentionPolicy{SchemaVersion: RetentionPolicySchemaVersion, KeepWithin: &bad}
	if err := ValidateRetentionPolicy(policy); err == nil {
		t.Fatal("expected invalid keep_within syntax to fail before Restic execution")
	}
}
