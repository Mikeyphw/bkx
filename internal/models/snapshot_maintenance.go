package models

import (
	"fmt"
	"regexp"
	"strings"
)

const RetentionPolicySchemaVersion = 1

type SnapshotDescriptor struct {
	ID       string   `json:"id"`
	ShortID  string   `json:"short_id,omitempty"`
	Time     string   `json:"time"`
	Hostname string   `json:"hostname,omitempty"`
	Username string   `json:"username,omitempty"`
	Paths    []string `json:"paths,omitempty"`
	Tags     []string `json:"tags,omitempty"`
	Size     *uint64  `json:"size,omitempty"`
}

// SnapshotNode is a safe, read-only view of one node returned by `restic ls --json`.
// It deliberately excludes ownership-sensitive or repository-internal identifiers.
type SnapshotNode struct {
	Name        string `json:"name"`
	Type        string `json:"type"`
	Path        string `json:"path"`
	Size        uint64 `json:"size,omitempty"`
	Permissions string `json:"permissions,omitempty"`
	ModTime     string `json:"mtime,omitempty"`
}

type RetentionPolicy struct {
	SchemaVersion int      `json:"schema_version"`
	KeepLast      *int     `json:"keep_last,omitempty"`
	KeepHourly    *int     `json:"keep_hourly,omitempty"`
	KeepDaily     *int     `json:"keep_daily,omitempty"`
	KeepWeekly    *int     `json:"keep_weekly,omitempty"`
	KeepMonthly   *int     `json:"keep_monthly,omitempty"`
	KeepYearly    *int     `json:"keep_yearly,omitempty"`
	KeepWithin    *string  `json:"keep_within,omitempty"`
	KeepTags      []string `json:"keep_tags,omitempty"`
	GroupBy       string   `json:"group_by,omitempty"`
}

func ValidateRetentionPolicy(policy RetentionPolicy) error {
	if policy.SchemaVersion == 0 {
		policy.SchemaVersion = RetentionPolicySchemaVersion
	}
	if policy.SchemaVersion != RetentionPolicySchemaVersion {
		return fmt.Errorf("retention schema_version must be %d", RetentionPolicySchemaVersion)
	}
	set := false
	for name, value := range map[string]*int{
		"keep_last":    policy.KeepLast,
		"keep_hourly":  policy.KeepHourly,
		"keep_daily":   policy.KeepDaily,
		"keep_weekly":  policy.KeepWeekly,
		"keep_monthly": policy.KeepMonthly,
		"keep_yearly":  policy.KeepYearly,
	} {
		if value == nil {
			continue
		}
		set = true
		if *value < 1 {
			return fmt.Errorf("%s must be at least 1; BKX refuses zero-count retention policies", name)
		}
	}
	if policy.KeepWithin != nil {
		value := strings.TrimSpace(*policy.KeepWithin)
		if value == "" {
			return fmt.Errorf("keep_within must not be empty")
		}
		if !resticDurationPattern.MatchString(value) {
			return fmt.Errorf("keep_within must use Restic duration syntax, for example 24h, 7d, 1m, or 1y")
		}
		set = true
	}
	for range policy.KeepTags {
		set = true
	}
	if !set {
		return fmt.Errorf("retention policy must contain at least one keep rule")
	}
	switch policy.GroupBy {
	case "", "none", "host", "tags", "host,paths", "host,paths,tags", "paths,tags", "host,tags", "paths":
	default:
		return fmt.Errorf("unsupported retention group_by %q", policy.GroupBy)
	}
	return nil
}

func CloneRetentionPolicy(in *RetentionPolicy) *RetentionPolicy {
	if in == nil {
		return nil
	}
	out := *in
	if in.KeepLast != nil {
		v := *in.KeepLast
		out.KeepLast = &v
	}
	if in.KeepHourly != nil {
		v := *in.KeepHourly
		out.KeepHourly = &v
	}
	if in.KeepDaily != nil {
		v := *in.KeepDaily
		out.KeepDaily = &v
	}
	if in.KeepWeekly != nil {
		v := *in.KeepWeekly
		out.KeepWeekly = &v
	}
	if in.KeepMonthly != nil {
		v := *in.KeepMonthly
		out.KeepMonthly = &v
	}
	if in.KeepYearly != nil {
		v := *in.KeepYearly
		out.KeepYearly = &v
	}
	if in.KeepWithin != nil {
		v := *in.KeepWithin
		out.KeepWithin = &v
	}
	out.KeepTags = append([]string(nil), in.KeepTags...)
	return &out
}

type RepositoryAccessSpec struct {
	Repository    string  `json:"repository"`
	CredentialRef *string `json:"credential_ref,omitempty"`
}

var resticDurationPattern = regexp.MustCompile(`^[0-9]+(?:h|d|w|m|y)$`)
