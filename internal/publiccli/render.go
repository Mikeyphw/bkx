package publiccli

import (
	"encoding/json"
	"fmt"
	"io"
	"reflect"
	"sort"
)

type renderer struct {
	json   bool
	stdout io.Writer
	stderr io.Writer
}

func (r renderer) writeJSON(payload any) error {
	normalized, err := toJSONValue(payload)
	if err != nil {
		return err
	}
	enc := json.NewEncoder(r.stdout)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "  ")
	return enc.Encode(normalized)
}

func toJSONValue(value any) (any, error) {
	data, err := json.Marshal(value)
	if err != nil {
		return nil, err
	}
	var out any
	if err := json.Unmarshal(data, &out); err != nil {
		return nil, err
	}
	return out, nil
}

func resultPayload(value any, kind string) (map[string]any, error) {
	raw, err := toJSONValue(value)
	if err != nil {
		return nil, err
	}
	payload, ok := raw.(map[string]any)
	if !ok {
		payload = map[string]any{"value": raw}
	}
	if duration, ok := payload["duration_ms"]; ok {
		delete(payload, "duration_ms")
		payload["timing"] = []any{float64(0), duration}
	}
	switch kind {
	case "archive_list", "archive_info":
		if _, ok := payload["warnings"]; !ok {
			payload["warnings"] = []any{}
		}
		if _, ok := payload["errors"]; !ok {
			payload["errors"] = []any{}
		}
	}
	payload["type"] = kind
	return payload, nil
}

func (r renderer) result(value any, kind string) error {
	payload, err := resultPayload(value, kind)
	if err != nil {
		return err
	}
	if r.json {
		return r.writeJSON(payload)
	}
	title := kind
	if success, ok := payload["success"].(bool); ok {
		if success {
			fmt.Fprintf(r.stdout, "OK %s\n", title)
		} else {
			fmt.Fprintf(r.stdout, "FAILED %s\n", title)
		}
	} else {
		fmt.Fprintln(r.stdout, title)
	}
	keys := make([]string, 0, len(payload))
	for key := range payload {
		if key != "type" && key != "success" {
			keys = append(keys, key)
		}
	}
	sort.Strings(keys)
	for _, key := range keys {
		value := payload[key]
		if isEmptyRenderValue(value) {
			continue
		}
		fmt.Fprintf(r.stdout, "  %s: %s\n", key, formatTextValue(value))
	}
	return nil
}

func isEmptyRenderValue(value any) bool {
	if value == nil {
		return true
	}
	rv := reflect.ValueOf(value)
	switch rv.Kind() {
	case reflect.Slice, reflect.Map, reflect.Array:
		return rv.Len() == 0
	}
	return false
}

func formatTextValue(value any) string {
	switch value.(type) {
	case map[string]any, []any:
		data, _ := json.Marshal(value)
		return string(data)
	default:
		return fmt.Sprint(value)
	}
}

func (r renderer) message(message string) error {
	if r.json {
		return r.writeJSON(map[string]any{"type": "message", "level": "info", "message": message})
	}
	fmt.Fprintf(r.stdout, "INFO: %s\n", message)
	return nil
}

func (r renderer) plan(specType string, spec any) error {
	if r.json {
		return r.writeJSON(map[string]any{"type": "plan", "success": true, "spec_type": specType, "spec": spec})
	}
	fmt.Fprintf(r.stdout, "Plan for %s:\n", specType)
	raw, _ := toJSONValue(spec)
	if m, ok := raw.(map[string]any); ok {
		keys := make([]string, 0, len(m))
		for key := range m {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		for _, key := range keys {
			fmt.Fprintf(r.stdout, "  %s: %s\n", key, formatTextValue(m[key]))
		}
	}
	return nil
}

func (r renderer) typedError(message string, details map[string]any) error {
	if r.json {
		payload := map[string]any{"type": "error", "success": false, "message": message}
		if len(details) > 0 {
			payload["details"] = details
		}
		return r.writeJSON(payload)
	}
	fmt.Fprintf(r.stdout, "ERROR: %s\n", message)
	if len(details) > 0 {
		keys := make([]string, 0, len(details))
		for key := range details {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		for _, key := range keys {
			fmt.Fprintf(r.stdout, "  %s: %s\n", key, formatTextValue(details[key]))
		}
	}
	return nil
}

func (r renderer) verbose(message string) {
	fmt.Fprintf(r.stderr, "verbose: %s\n", message)
}
