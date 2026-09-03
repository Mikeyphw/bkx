package berror

import "fmt"

const (
	ExitOK         = 0
	ExitGeneric    = 1
	ExitUsage      = 2
	ExitNotFound   = 3
	ExitPermission = 4
	ExitExternal   = 5
)

type Kind string

const (
	KindGeneric    Kind = "BkxError"
	KindValidation Kind = "ValidationError"
	KindNotFound   Kind = "NotFoundError"
	KindPermission Kind = "PermissionDeniedError"
	KindExternal   Kind = "ExternalToolError"
	KindOptional   Kind = "OptionalFeatureError"
)

type Error struct {
	Kind       Kind           `json:"type"`
	Message    string         `json:"message"`
	Details    map[string]any `json:"details,omitempty"`
	ToolName   *string        `json:"tool_name,omitempty"`
	ExitStatus *int           `json:"exit_code,omitempty"`
	Stderr     *string        `json:"stderr,omitempty"`
	Feature    *string        `json:"feature,omitempty"`
	Extra      *string        `json:"extra,omitempty"`
	Dependency *string        `json:"dependency,omitempty"`
}

func (e *Error) Error() string {
	if e == nil {
		return ""
	}
	return e.Message
}

func New(kind Kind, message string, details map[string]any) *Error {
	if details == nil {
		details = map[string]any{}
	}
	return &Error{Kind: kind, Message: message, Details: cloneMap(details)}
}

func Validation(message string, details map[string]any) *Error {
	return New(KindValidation, message, details)
}

func NotFound(message string, details map[string]any) *Error {
	return New(KindNotFound, message, details)
}

func Permission(message string, details map[string]any) *Error {
	return New(KindPermission, message, details)
}

func External(message string, details map[string]any) *Error {
	return New(KindExternal, message, details)
}

func ExitCode(err error) int {
	if err == nil {
		return ExitOK
	}
	bkx, ok := err.(*Error)
	if !ok {
		return ExitGeneric
	}
	switch bkx.Kind {
	case KindValidation:
		return ExitUsage
	case KindNotFound:
		return ExitNotFound
	case KindPermission:
		return ExitPermission
	case KindExternal, KindOptional:
		return ExitExternal
	default:
		return ExitGeneric
	}
}

func WrapExternal(operation string, resource string, backend string, err error) *Error {
	details := map[string]any{"operation": operation}
	if resource != "" {
		details["resource"] = resource
	}
	if backend != "" {
		details["backend"] = backend
	}
	if err != nil {
		details["reason"] = err.Error()
	}
	return External(fmt.Sprintf("System operation failed while %s: %v", operation, err), details)
}

func cloneMap(src map[string]any) map[string]any {
	out := make(map[string]any, len(src))
	for key, value := range src {
		out[key] = value
	}
	return out
}
