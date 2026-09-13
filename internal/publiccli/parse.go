package publiccli

import (
	"fmt"
	"strconv"
	"strings"
)

type optionKind int

const (
	optionValue optionKind = iota
	optionMultiValue
	optionBoolTrue
	optionBoolFalse
)

type optionSpec struct {
	Key  string
	Kind optionKind
}

type leafArgs struct {
	Positionals []string
	Values      map[string][]string
	Bools       map[string]bool
	BoolSet     map[string]bool
}

func parseLeaf(args []string, specs map[string]optionSpec) (leafArgs, bool, error) {
	out := leafArgs{Values: map[string][]string{}, Bools: map[string]bool{}, BoolSet: map[string]bool{}}
	positionalOnly := false
	for i := 0; i < len(args); i++ {
		token := args[i]
		if !positionalOnly && (token == "--help" || token == "-h") {
			return out, true, nil
		}
		if !positionalOnly && token == "--" {
			positionalOnly = true
			continue
		}
		if !positionalOnly && strings.HasPrefix(token, "-") {
			lookup := token
			inlineValue := ""
			hasInlineValue := false
			if strings.HasPrefix(token, "--") {
				if head, tail, ok := strings.Cut(token, "="); ok {
					lookup = head
					inlineValue = tail
					hasInlineValue = true
				}
			}
			spec, ok := specs[lookup]
			if !ok {
				return out, false, fmt.Errorf("no such option: %s", token)
			}
			switch spec.Kind {
			case optionBoolTrue:
				if hasInlineValue {
					return out, false, fmt.Errorf("option %s does not take a value", lookup)
				}
				out.Bools[spec.Key] = true
				out.BoolSet[spec.Key] = true
			case optionBoolFalse:
				if hasInlineValue {
					return out, false, fmt.Errorf("option %s does not take a value", lookup)
				}
				out.Bools[spec.Key] = false
				out.BoolSet[spec.Key] = true
			case optionValue, optionMultiValue:
				value := inlineValue
				if !hasInlineValue {
					if i+1 >= len(args) {
						return out, false, fmt.Errorf("option %s requires a value", lookup)
					}
					i++
					value = args[i]
				}
				if spec.Kind == optionValue {
					out.Values[spec.Key] = []string{value}
				} else {
					out.Values[spec.Key] = append(out.Values[spec.Key], value)
				}
			}
			continue
		}
		out.Positionals = append(out.Positionals, token)
	}
	return out, false, nil
}

func (a leafArgs) has(key string) bool {
	if _, ok := a.Values[key]; ok {
		return true
	}
	return a.BoolSet[key]
}

func (a leafArgs) stringValue(key string) (string, bool) {
	values := a.Values[key]
	if len(values) == 0 {
		return "", false
	}
	return values[len(values)-1], true
}

func (a leafArgs) multiValue(key string) []string {
	return append([]string(nil), a.Values[key]...)
}

func (a leafArgs) intValue(key string, fallback int) (int, error) {
	raw, ok := a.stringValue(key)
	if !ok {
		return fallback, nil
	}
	value, err := strconv.Atoi(raw)
	if err != nil {
		return 0, fmt.Errorf("%s must be an integer", key)
	}
	return value, nil
}

func (a leafArgs) int64Ptr(key string) (*int64, error) {
	raw, ok := a.stringValue(key)
	if !ok {
		return nil, nil
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	if err != nil {
		return nil, fmt.Errorf("%s must be an integer", key)
	}
	return &value, nil
}

func (a leafArgs) triBool(key string) (*bool, bool) {
	if !a.BoolSet[key] {
		return nil, false
	}
	value := a.Bools[key]
	return &value, true
}

func requirePositionals(values []string, min, max int, label string) error {
	if len(values) < min {
		return fmt.Errorf("missing argument: %s", label)
	}
	if max >= 0 && len(values) > max {
		return fmt.Errorf("too many arguments for %s", label)
	}
	return nil
}
