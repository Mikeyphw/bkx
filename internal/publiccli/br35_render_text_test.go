package publiccli

import (
	"bytes"
	"strings"
	"testing"
)

func TestBR35HumanTypedErrorCollectionsRenderAsJson(t *testing.T) {
	var stdout, stderr bytes.Buffer
	r := renderer{json: false, stdout: &stdout, stderr: &stderr}
	if err := r.typedError("collection failed", map[string]any{
		"items": []string{"alpha", "beta,gamma"},
		"codes": []int64{7, 9},
	}); err != nil {
		t.Fatal(err)
	}
	text := stderr.String()
	if !strings.Contains(text, `items: ["alpha","beta,gamma"]`) {
		t.Fatalf("items rendered with non-JSON collection formatting: %s", text)
	}
	if !strings.Contains(text, `codes: [7,9]`) {
		t.Fatalf("codes rendered with non-JSON collection formatting: %s", text)
	}
	if strings.Contains(text, "[alpha beta") || strings.Contains(text, "[7 9]") {
		t.Fatalf("Go-native slice formatting leaked to human renderer: %s", text)
	}
}
