package publiccli

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"reflect"
	"sort"

	"bkx/internal/berror"
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
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	var out any
	if err := dec.Decode(&out); err != nil {
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
			if _, err := fmt.Fprintf(r.stdout, "OK %s\n", title); err != nil {
				return err
			}
		} else {
			if _, err := fmt.Fprintf(r.stdout, "FAILED %s\n", title); err != nil {
				return err
			}
		}
	} else {
		if _, err := fmt.Fprintln(r.stdout, title); err != nil {
			return err
		}
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
		if _, err := fmt.Fprintf(r.stdout, "  %s: %s\n", key, formatTextValue(value)); err != nil {
			return err
		}
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
	_, err := fmt.Fprintf(r.stdout, "INFO: %s\n", message)
	return err
}

func (r renderer) plan(specType string, spec any) error {
	if r.json {
		return r.writeJSON(map[string]any{"type": "plan", "success": true, "spec_type": specType, "spec": spec})
	}
	if _, err := fmt.Fprintf(r.stdout, "Plan for %s:\n", specType); err != nil {
		return err
	}
	raw, _ := toJSONValue(spec)
	if m, ok := raw.(map[string]any); ok {
		keys := make([]string, 0, len(m))
		for key := range m {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		for _, key := range keys {
			if _, err := fmt.Fprintf(r.stdout, "  %s: %s\n", key, formatTextValue(m[key])); err != nil {
				return err
			}
		}
	}
	return nil
}

func (r renderer) typedError(message string, details map[string]any) error {
	return r.errorPayload(map[string]any{"type": "error", "success": false, "message": message, "details": cloneJSONMap(details)}, message, details)
}

func (r renderer) domainError(err *berror.Error) error {
	payload := map[string]any{
		"type":       "error",
		"success":    false,
		"message":    err.Message,
		"error_type": string(err.Kind),
		"exit_code":  berror.ExitCode(err),
	}
	if len(err.Details) > 0 {
		payload["details"] = cloneJSONMap(err.Details)
	}
	if err.ToolName != nil {
		payload["tool_name"] = *err.ToolName
	}
	if err.ExitStatus != nil {
		payload["tool_exit_code"] = *err.ExitStatus
	}
	if err.Stderr != nil {
		payload["tool_stderr"] = *err.Stderr
	}
	if err.Feature != nil {
		payload["feature"] = *err.Feature
	}
	if err.Extra != nil {
		payload["extra"] = *err.Extra
	}
	if err.Dependency != nil {
		payload["dependency"] = *err.Dependency
	}
	return r.errorPayload(payload, err.Message, err.Details)
}

func (r renderer) errorPayload(payload map[string]any, message string, details map[string]any) error {
	if r.json {
		return r.writeJSON(payload)
	}
	if _, err := fmt.Fprintf(r.stderr, "ERROR: %s\n", message); err != nil {
		return err
	}
	if len(details) > 0 {
		keys := make([]string, 0, len(details))
		for key := range details {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		for _, key := range keys {
			if _, err := fmt.Fprintf(r.stderr, "  %s: %s\n", key, formatTextValue(details[key])); err != nil {
				return err
			}
		}
	}
	return nil
}

func cloneJSONMap(src map[string]any) map[string]any {
	if len(src) == 0 {
		return nil
	}
	out := make(map[string]any, len(src))
	for key, value := range src {
		out[key] = cloneJSONValue(value)
	}
	return out
}

func cloneJSONValue(value any) any {
	switch v := value.(type) {
	case map[string]any:
		return cloneJSONMap(v)
	case []any:
		out := make([]any, len(v))
		for i, item := range v {
			out[i] = cloneJSONValue(item)
		}
		return out
	case []string:
		return append([]string(nil), v...)
	case []int:
		return append([]int(nil), v...)
	case []int64:
		return append([]int64(nil), v...)
	default:
		return value
	}
}

func (r renderer) verbose(message string) {
	fmt.Fprintf(r.stderr, "verbose: %s\n", message)
}
