package bridge

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"bkx/internal/berror"
	"bkx/internal/rcloneops"
	"bkx/internal/runstore"
	"bkx/internal/services"
	"bkx/internal/version"
)

const (
	ProtocolVersion = 1
	AndroidRuntime  = "go-native-android"
	maxRuntimeDocs  = 128
)

var frozenSupportedOperations = []string{
	"archive.browse",
	"archive.info",
	"archive.list",
	"archive.verify",
	"backup.create",
	"backup.plan",
	"filesystem.list",
	"filesystem.stat",
	"restore.extract",
	"restore.preview",
	"runs.list",
	"runs.show",
	"storage.locations",
	"transfer.plan",
	"transfer.run",
}

var frozenHandlerOperations = append(append([]string(nil), frozenSupportedOperations...), "run.cancel")

var frozenCapabilities = []string{
	"archive.browse",
	"archive.info",
	"archive.list",
	"archive.verify",
	"backup.create",
	"backup.plan",
	"filesystem.list",
	"filesystem.stat",
	"readiness.probe",
	"restore.extract",
	"restore.preview",
	"run.cancel.cooperative",
	"run.progress",
	"runs.list",
	"runs.show",
	"storage.locations",
	"transfer.plan",
	"transfer.run",
}

type Request struct {
	ProtocolVersion int             `json:"protocol_version"`
	RequestID       string          `json:"request_id"`
	Operation       string          `json:"operation"`
	Payload         json.RawMessage `json:"payload"`
}

type ErrorDocument struct {
	Code    string `json:"code"`
	Message string `json:"message"`
	Action  string `json:"action"`
}

type Metrics struct {
	DurationMS int64  `json:"duration_ms"`
	FilesTotal *int64 `json:"files_total,omitempty"`
	BytesTotal *int64 `json:"bytes_total,omitempty"`
}

type Result struct {
	ProtocolVersion int                 `json:"protocol_version"`
	RequestID       string              `json:"request_id"`
	Status          string              `json:"status"`
	Summary         string              `json:"summary"`
	Artifacts       []services.Artifact `json:"artifacts"`
	Metrics         *Metrics            `json:"metrics,omitempty"`
	Errors          []ErrorDocument     `json:"errors"`
}

type Progress struct {
	ProtocolVersion int      `json:"protocol_version"`
	RequestID       string   `json:"request_id"`
	Seq             int64    `json:"seq"`
	Phase           string   `json:"phase"`
	Message         string   `json:"message"`
	Progress        *float64 `json:"progress,omitempty"`
	FilesDone       *int64   `json:"files_done,omitempty"`
	FilesTotal      *int64   `json:"files_total,omitempty"`
	BytesDone       *int64   `json:"bytes_done,omitempty"`
	BytesTotal      *int64   `json:"bytes_total,omitempty"`
}

type Readiness struct {
	ProtocolVersion int      `json:"protocol_version"`
	BackendVersion  string   `json:"backend_version"`
	Runtime         string   `json:"runtime"`
	PythonVersion   string   `json:"python_version"`
	Capabilities    []string `json:"capabilities"`
}

type RuntimeAdapter struct {
	services *services.Dispatcher
	runtime  string

	mu        sync.Mutex
	cancelled map[string]struct{}
	progress  map[string]Progress
	seq       map[string]int64
	order     []string
}

func NewAndroid(store *runstore.Store) *RuntimeAdapter {
	return &RuntimeAdapter{
		services:  services.New(store),
		runtime:   AndroidRuntime,
		cancelled: map[string]struct{}{},
		progress:  map[string]Progress{},
		seq:       map[string]int64{},
	}
}

func (r *RuntimeAdapter) Readiness() Readiness {
	caps := append([]string(nil), frozenCapabilities...)
	caps = append(caps,
		"archive.compression.none",
		"archive.compression.gzip",
		"archive.compression.zstd",
		"archive.split",
		"filesystem.direct",
		"backup.snapshot",
		"snapshot.lifecycle",
		"snapshot.restore.safe",
		"repository.named",
		"repository.check",
		"repository.prune.explicit",
		"retention.policy",
	)
	if rcloneops.Available() {
		caps = append(caps,
			"remote.rclone",
			"transfer.remote",
			"transfer.sync",
			"backup.remote",
			"archive.remote",
			"restore.remote",
			"backup.snapshot.remote.rclone",
			"repository.managed_google_drive",
			"cloud.google_drive",
		)
		if rcloneops.Configured() {
			caps = append(caps, "remote.rclone.configured")
		}
	}
	if os.Geteuid() == 0 {
		caps = append(caps, "root.filesystem", "storage.termux.private", "storage.app_private")
	}
	sort.Strings(caps)
	return Readiness{
		ProtocolVersion: ProtocolVersion,
		BackendVersion:  version.Version,
		Runtime:         r.runtime,
		PythonVersion:   "none",
		Capabilities:    caps,
	}
}

func SupportedOperations() []string {
	ops := append([]string(nil), frozenSupportedOperations...)
	sort.Strings(ops)
	return ops
}

func HandlerOperations() []string {
	ops := append([]string(nil), frozenHandlerOperations...)
	sort.Strings(ops)
	return ops
}

// ExtensionOperations are additive post-v1 operations. They are executable by
// the embedded runtime but intentionally excluded from SupportedOperations so
// the frozen v1 migration/bridge contract remains byte-for-byte compatible.
func ExtensionOperations() []string {
	frozen := map[string]bool{}
	for _, op := range frozenSupportedOperations {
		frozen[op] = true
	}
	var out []string
	for _, op := range services.SupportedOperations() {
		if !frozen[op] {
			out = append(out, op)
		}
	}
	sort.Strings(out)
	return out
}

func ExecutableOperations() []string {
	seen := map[string]bool{}
	out := []string{}
	for _, group := range [][]string{HandlerOperations(), ExtensionOperations()} {
		for _, op := range group {
			if !seen[op] {
				seen[op] = true
				out = append(out, op)
			}
		}
	}
	sort.Strings(out)
	return out
}

func (r *RuntimeAdapter) Progress(requestID string) (*Progress, error) {
	id, err := requireRequestID(requestID)
	if err != nil {
		return nil, err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	p, ok := r.progress[id]
	if !ok {
		return nil, nil
	}
	copy := p
	return &copy, nil
}

func (r *RuntimeAdapter) Cancel(requestID string) Result {
	id, err := requireRequestID(requestID)
	if err != nil {
		return failure("unknown", err)
	}
	r.mu.Lock()
	r.cancelled[id] = struct{}{}
	r.mu.Unlock()
	return success(id, "Cancellation recorded for the embedded operation.", nil, nil)
}

func (r *RuntimeAdapter) Execute(raw string) Result {
	requestID := "unknown"
	started := time.Now()

	request, err := parseRequest(raw)
	if err != nil {
		result := failure(requestID, err)
		result.Metrics = &Metrics{DurationMS: durationMillis(started)}
		return result
	}
	requestID = request.RequestID

	r.emit(requestID, "preparing", "Preparing embedded BKX operation.", floatPtr(0))
	if r.isCancelled(requestID) {
		result := cancelled(requestID, "Operation cancelled during preparing.")
		result.Metrics = &Metrics{DurationMS: durationMillis(started)}
		r.finish(requestID, result)
		return result
	}

	var result Result
	if request.Operation == "run.cancel" {
		result = r.handleCancelOperation(request)
	} else if !contains(ExecutableOperations(), request.Operation) {
		result = failure(requestID, berror.Validation(
			"Unsupported embedded Android operation: "+request.Operation,
			map[string]any{"operation": request.Operation, "supported": ExecutableOperations()},
		))
	} else {
		ctx := services.OperationContext{
			RequestID: requestID,
			Cancelled: func() bool { return r.isCancelled(requestID) },
			Progress: func(p services.Progress) {
				r.emitService(requestID, p)
			},
		}
		core, dispatchErr := r.services.Dispatch(ctx, request.Operation, request.Payload)
		switch {
		case errors.Is(dispatchErr, services.ErrCancelled):
			result = cancelled(requestID, "Operation cancelled.")
		case dispatchErr != nil:
			result = failure(requestID, dispatchErr)
		default:
			result = fromService(requestID, core)
		}
	}

	if result.Metrics == nil {
		result.Metrics = &Metrics{}
	}
	result.Metrics.DurationMS = durationMillis(started)
	r.finish(requestID, result)
	return result
}

func (r *RuntimeAdapter) finish(requestID string, result Result) {
	var final *float64
	if result.Status == "success" {
		final = floatPtr(1)
	}
	r.emit(requestID, result.Status, result.Summary, final)
	r.mu.Lock()
	delete(r.cancelled, requestID)
	r.trimLocked()
	r.mu.Unlock()
}

func (r *RuntimeAdapter) handleCancelOperation(request Request) Result {
	payload := map[string]any{}
	if err := decodeObject(request.Payload, &payload); err != nil {
		return failure(request.RequestID, err)
	}
	target := request.RequestID
	if raw, ok := payload["target_request_id"]; ok && raw != nil {
		text, ok := raw.(string)
		if !ok {
			return failure(request.RequestID, berror.Validation("Expected string or null", nil))
		}
		if strings.TrimSpace(text) != "" {
			target = strings.TrimSpace(text)
		}
	}
	r.mu.Lock()
	r.cancelled[target] = struct{}{}
	r.mu.Unlock()
	return success(request.RequestID, fmt.Sprintf("Cancellation recorded for %s.", target), nil, nil)
}

func (r *RuntimeAdapter) emitService(requestID string, p services.Progress) {
	r.emitDetailed(requestID, p.Phase, p.Message, p.Progress, p.FilesDone, p.FilesTotal, p.BytesDone, p.BytesTotal)
}

func (r *RuntimeAdapter) emit(requestID, phase, message string, progress *float64) {
	r.emitDetailed(requestID, phase, message, progress, nil, nil, nil, nil)
}

func (r *RuntimeAdapter) emitDetailed(requestID, phase, message string, progress *float64, filesDone, filesTotal, bytesDone, bytesTotal *int64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	seq := r.seq[requestID] + 1
	r.seq[requestID] = seq
	if progress != nil {
		v := *progress
		if v < 0 {
			v = 0
		}
		if v > 1 {
			v = 1
		}
		progress = &v
	}
	if _, exists := r.progress[requestID]; !exists {
		r.order = append(r.order, requestID)
	}
	r.progress[requestID] = Progress{
		ProtocolVersion: ProtocolVersion,
		RequestID:       requestID,
		Seq:             seq,
		Phase:           phase,
		Message:         message,
		Progress:        progress,
		FilesDone:       filesDone,
		FilesTotal:      filesTotal,
		BytesDone:       bytesDone,
		BytesTotal:      bytesTotal,
	}
	r.trimLocked()
}

func (r *RuntimeAdapter) isCancelled(requestID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	_, ok := r.cancelled[requestID]
	return ok
}

func (r *RuntimeAdapter) trimLocked() {
	for len(r.progress) > maxRuntimeDocs && len(r.order) > 0 {
		oldest := r.order[0]
		r.order = r.order[1:]
		delete(r.progress, oldest)
		delete(r.seq, oldest)
	}
}

func parseRequest(raw string) (Request, error) {
	if strings.TrimSpace(raw) == "" {
		return Request{}, berror.Validation("Embedded request JSON must be a non-empty string", nil)
	}
	dec := json.NewDecoder(strings.NewReader(raw))
	dec.UseNumber()
	var root any
	if err := dec.Decode(&root); err != nil {
		return Request{}, berror.Validation("Invalid embedded Android request: "+err.Error(), nil)
	}
	var trailing any
	if err := dec.Decode(&trailing); !errors.Is(err, io.EOF) {
		if err == nil {
			return Request{}, berror.Validation("Invalid embedded Android request: multiple JSON documents", nil)
		}
		return Request{}, berror.Validation("Invalid embedded Android request: "+err.Error(), nil)
	}
	document, ok := root.(map[string]any)
	if !ok {
		return Request{}, berror.Validation("Embedded request root must be an object", nil)
	}
	protocol := document["protocol_version"]
	if !protocolOne(protocol) {
		return Request{}, berror.Validation(fmt.Sprintf("Unsupported bridge protocol version %s; expected 1", pythonish(protocol)), map[string]any{"expected": 1, "actual": protocol})
	}
	requestID, err := requireRequestID(document["request_id"])
	if err != nil {
		return Request{}, err
	}
	operationRaw, ok := document["operation"].(string)
	if !ok || strings.TrimSpace(operationRaw) == "" {
		return Request{}, berror.Validation("operation must be a non-empty string", nil)
	}
	payloadValue, exists := document["payload"]
	if !exists {
		payloadValue = map[string]any{}
	}
	payloadMap, ok := payloadValue.(map[string]any)
	if !ok {
		return Request{}, berror.Validation("payload must be an object", nil)
	}
	payload, err := json.Marshal(payloadMap)
	if err != nil {
		return Request{}, berror.Validation("Invalid embedded Android request: "+err.Error(), nil)
	}
	return Request{ProtocolVersion: ProtocolVersion, RequestID: requestID, Operation: strings.TrimSpace(operationRaw), Payload: payload}, nil
}

func decodeObject(raw json.RawMessage, out *map[string]any) error {
	if len(raw) == 0 {
		*out = map[string]any{}
		return nil
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	if err := dec.Decode(out); err != nil {
		return berror.Validation("Service payload must be a JSON object", map[string]any{"reason": err.Error()})
	}
	if *out == nil {
		return berror.Validation("Service payload must be a JSON object", nil)
	}
	return nil
}

func requireRequestID(value any) (string, error) {
	text, ok := value.(string)
	if !ok || strings.TrimSpace(text) == "" {
		return "", berror.Validation("request_id must be a non-empty string", nil)
	}
	id := strings.TrimSpace(text)
	for _, ch := range id {
		if !((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-') {
			return "", berror.Validation("request_id must be file-name safe", nil)
		}
	}
	return id, nil
}

func protocolOne(value any) bool {
	switch v := value.(type) {
	case json.Number:
		f, err := v.Float64()
		return err == nil && f == 1
	case float64:
		return v == 1
	case int:
		return v == 1
	default:
		return false
	}
}

func pythonish(value any) string {
	if value == nil {
		return "None"
	}
	switch v := value.(type) {
	case string:
		return v
	case json.Number:
		return v.String()
	default:
		return fmt.Sprint(v)
	}
}

func fromService(requestID string, core services.Result) Result {
	errorsOut := make([]ErrorDocument, 0, len(core.Errors))
	for _, issue := range core.Errors {
		errorsOut = append(errorsOut, ErrorDocument{Code: issue.Code, Message: issue.Message, Action: issue.Action})
	}
	arts := core.Artifacts
	if arts == nil {
		arts = []services.Artifact{}
	}
	var metrics *Metrics
	if core.Metrics != nil {
		metrics = &Metrics{FilesTotal: core.Metrics.FilesTotal, BytesTotal: core.Metrics.BytesTotal}
	}
	return Result{
		ProtocolVersion: ProtocolVersion,
		RequestID:       requestID,
		Status:          core.Status,
		Summary:         core.Summary,
		Artifacts:       arts,
		Metrics:         metrics,
		Errors:          errorsOut,
	}
}

func success(requestID, summary string, issues []ErrorDocument, artifacts []services.Artifact) Result {
	if issues == nil {
		issues = []ErrorDocument{}
	}
	if artifacts == nil {
		artifacts = []services.Artifact{}
	}
	return Result{ProtocolVersion: ProtocolVersion, RequestID: requestID, Status: "success", Summary: summary, Artifacts: artifacts, Errors: issues}
}

func cancelled(requestID, summary string) Result {
	return Result{ProtocolVersion: ProtocolVersion, RequestID: requestID, Status: "cancelled", Summary: summary, Artifacts: []services.Artifact{}, Errors: []ErrorDocument{}}
}

func failure(requestID string, err error) Result {
	code := "bkx_error"
	message := err.Error()
	action := "Review the operation details and retry."
	var typed *berror.Error
	if errors.As(err, &typed) {
		switch typed.Kind {
		case berror.KindValidation:
			code = "validation_error"
			action = "Review the selected files and operation options."
		case berror.KindNotFound:
			code = "not_found"
			action = "Choose an existing source, archive, or destination."
		case berror.KindPermission:
			code = "permission_denied"
			action = "Grant access to the selected location and retry."
		case berror.KindExternal, berror.KindOptional:
			code = "external_tool_error"
			action = "This operation needs a backend capability unavailable in the embedded lane."
		}
	} else {
		code = "embedded_runtime_error"
		action = "Review diagnostics and retry the operation."
		message = fmt.Sprintf("Embedded BKX runtime failed: %T: %v", err, err)
	}
	return Result{
		ProtocolVersion: ProtocolVersion,
		RequestID:       requestID,
		Status:          "failure",
		Summary:         message,
		Artifacts:       []services.Artifact{},
		Errors:          []ErrorDocument{{Code: code, Message: message, Action: action}},
	}
}

func durationMillis(start time.Time) int64 {
	ms := time.Since(start).Milliseconds()
	if ms < 0 {
		return 0
	}
	return ms
}
func floatPtr(v float64) *float64 { return &v }
func contains(items []string, needle string) bool {
	for _, item := range items {
		if item == needle {
			return true
		}
	}
	return false
}
