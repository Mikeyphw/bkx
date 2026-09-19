package executioncontrol

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"bkx/internal/berror"
	"bkx/internal/filelock"
	"bkx/internal/statepaths"
)

const schemaVersion = 1

type Lease struct {
	RequestID         string `json:"request_id"`
	Operation         string `json:"operation"`
	JobID             string `json:"job_id,omitempty"`
	RepositoryID      string `json:"repository_id,omitempty"`
	Owner             string `json:"owner,omitempty"`
	PID               int    `json:"pid"`
	BootID            string `json:"boot_id,omitempty"`
	StartedAt         string `json:"started_at"`
	HeartbeatAt       string `json:"heartbeat_at"`
	ProcessStartTicks string `json:"process_start_ticks,omitempty"`
}

type document struct {
	SchemaVersion int               `json:"schema_version"`
	Leases        map[string]Lease  `json:"leases"`
	Cancellations map[string]string `json:"cancellations"`
}

type Store struct{ Path string }

func Default(home string) *Store  { return &Store{Path: statepaths.ExecutionControlPath(home)} }
func (s *Store) LockPath() string { return s.Path + ".lock" }

func (s *Store) Acquire(requestID, operation, jobID, repositoryID, owner string) (Lease, error) {
	requestID = strings.TrimSpace(requestID)
	if requestID == "" {
		return Lease{}, berror.Validation("request_id is required for execution control", nil)
	}
	now := time.Now().UTC()
	lease := Lease{
		RequestID: requestID, Operation: strings.TrimSpace(operation), JobID: strings.TrimSpace(jobID),
		RepositoryID: strings.TrimSpace(repositoryID), Owner: strings.TrimSpace(owner), PID: os.Getpid(),
		BootID: currentBootID(), ProcessStartTicks: processStartTicks(os.Getpid()), StartedAt: now.Format(time.RFC3339Nano), HeartbeatAt: now.Format(time.RFC3339Nano),
	}
	err := s.withDocument(func(doc *document) error {
		cleanupStale(doc)
		if existing, ok := doc.Leases[requestID]; ok {
			return busy(existing)
		}
		for _, existing := range doc.Leases {
			if conflicts(existing, lease) {
				return busy(existing)
			}
		}
		doc.Leases[requestID] = lease
		return nil
	})
	return lease, err
}

func (s *Store) Heartbeat(requestID string) error {
	return s.withDocument(func(doc *document) error {
		lease, ok := doc.Leases[strings.TrimSpace(requestID)]
		if !ok {
			return nil
		}
		lease.HeartbeatAt = time.Now().UTC().Format(time.RFC3339Nano)
		doc.Leases[lease.RequestID] = lease
		return nil
	})
}

func (s *Store) Release(requestID string) error {
	return s.withDocument(func(doc *document) error { delete(doc.Leases, strings.TrimSpace(requestID)); return nil })
}

func (s *Store) RequestCancel(requestID string) error {
	requestID = strings.TrimSpace(requestID)
	if requestID == "" {
		return berror.Validation("target request id is required", nil)
	}
	return s.withDocument(func(doc *document) error {
		doc.Cancellations[requestID] = time.Now().UTC().Format(time.RFC3339Nano)
		return nil
	})
}

func (s *Store) CancelRequested(requestID string) bool {
	data, err := os.ReadFile(s.Path)
	if err != nil {
		return false
	}
	var doc document
	if json.Unmarshal(data, &doc) != nil {
		return false
	}
	_, found := doc.Cancellations[strings.TrimSpace(requestID)]
	return found
}

func (s *Store) ClearCancel(requestID string) error {
	return s.withDocument(func(doc *document) error { delete(doc.Cancellations, strings.TrimSpace(requestID)); return nil })
}

func (s *Store) RecoverStale() ([]Lease, error) {
	var stale []Lease
	err := s.withDocument(func(doc *document) error {
		for id, lease := range doc.Leases {
			if !processAlive(lease) {
				stale = append(stale, lease)
				delete(doc.Leases, id)
			}
		}
		return nil
	})
	return stale, err
}

func (s *Store) Active() ([]Lease, error) {
	var out []Lease
	err := s.withDocument(func(doc *document) error {
		cleanupStale(doc)
		for _, lease := range doc.Leases {
			out = append(out, lease)
		}
		return nil
	})
	return out, err
}

func conflicts(a, b Lease) bool {
	if a.RequestID == b.RequestID {
		return true
	}
	if a.JobID != "" && b.JobID != "" && a.JobID == b.JobID {
		return true
	}
	if a.RepositoryID != "" && b.RepositoryID != "" && a.RepositoryID == b.RepositoryID {
		return true
	}
	return false
}

func busy(existing Lease) error {
	return berror.Validation("BKX operation is already running for this job or repository", map[string]any{
		"active_request_id": existing.RequestID, "operation": existing.Operation,
		"job_id": existing.JobID, "repository_id": existing.RepositoryID, "owner": existing.Owner,
	})
}

func cleanupStale(doc *document) {
	for id, lease := range doc.Leases {
		if !processAlive(lease) {
			delete(doc.Leases, id)
		}
	}
}

func processAlive(lease Lease) bool {
	if lease.PID <= 0 {
		return false
	}
	current := currentBootID()
	if current != "" && lease.BootID != "" && lease.BootID != current {
		return false
	}
	err := syscall.Kill(lease.PID, 0)
	if err != nil && !errors.Is(err, syscall.EPERM) {
		return false
	}
	if lease.ProcessStartTicks != "" {
		return processStartTicks(lease.PID) == lease.ProcessStartTicks
	}
	return true
}

func processStartTicks(pid int) string {
	data, err := os.ReadFile(filepath.Join("/proc", strconv.Itoa(pid), "stat"))
	if err != nil {
		return ""
	}
	text := string(data)
	closeParen := strings.LastIndex(text, ")")
	if closeParen < 0 || closeParen+2 >= len(text) {
		return ""
	}
	fields := strings.Fields(text[closeParen+2:])
	// /proc/<pid>/stat fields after the comm value start at field 3.
	// starttime is field 22, therefore index 19 in this slice.
	if len(fields) <= 19 {
		return ""
	}
	return fields[19]
}

func currentBootID() string {
	data, err := os.ReadFile("/proc/sys/kernel/random/boot_id")
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(data))
}

func (s *Store) withDocument(fn func(*document) error) error {
	if err := os.MkdirAll(filepath.Dir(s.Path), 0o700); err != nil {
		return err
	}
	lock, err := os.OpenFile(s.LockPath(), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return err
	}
	defer lock.Close()
	unlock, err := filelock.Acquire(lock, filelock.Exclusive)
	if err != nil {
		return err
	}
	defer unlock()

	doc := document{SchemaVersion: schemaVersion, Leases: map[string]Lease{}, Cancellations: map[string]string{}}
	if data, err := os.ReadFile(s.Path); err == nil {
		if err := json.Unmarshal(data, &doc); err != nil {
			return fmt.Errorf("invalid BKX execution-control document: %w", err)
		}
		if doc.SchemaVersion != schemaVersion {
			return fmt.Errorf("unsupported BKX execution-control schema %d", doc.SchemaVersion)
		}
		if doc.Leases == nil {
			doc.Leases = map[string]Lease{}
		}
		if doc.Cancellations == nil {
			doc.Cancellations = map[string]string{}
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err := fn(&doc); err != nil {
		return err
	}
	data, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	return statepaths.AtomicWriteFile(s.Path, data, 0o600)
}

func DebugString(lease Lease) string {
	return lease.RequestID + ":" + lease.Operation + ":pid=" + strconv.Itoa(lease.PID)
}
