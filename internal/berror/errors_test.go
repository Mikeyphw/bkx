package berror

import "testing"

func TestExitCodeParity(t *testing.T) {
	cases := []struct {
		err  error
		want int
	}{
		{nil, 0},
		{New(KindGeneric, "x", nil), 1},
		{Validation("x", nil), 2},
		{NotFound("x", nil), 3},
		{Permission("x", nil), 4},
		{External("x", nil), 5},
		{New(KindOptional, "x", nil), 5},
	}
	for _, tc := range cases {
		if got := ExitCode(tc.err); got != tc.want {
			t.Fatalf("ExitCode(%v)=%d want %d", tc.err, got, tc.want)
		}
	}
}

func TestDetailsAreCopied(t *testing.T) {
	src := map[string]any{"field": "name"}
	err := Validation("bad", src)
	src["field"] = "changed"
	if err.Details["field"] != "name" {
		t.Fatalf("details alias caller map: %#v", err.Details)
	}
}
