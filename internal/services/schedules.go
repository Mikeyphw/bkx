package services

import (
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"strings"

	"bkx/internal/berror"
	"bkx/internal/schedulestore"
)

func (d *Dispatcher) schedulesList() (Result, error) {
	values, err := d.scheduleStore().List()
	if err != nil {
		return Result{}, err
	}
	artifacts := make([]Artifact, 0, len(values))
	for _, value := range values {
		artifacts = append(artifacts, scheduleArtifact(value))
	}
	return Result{Status: StatusSuccess, Summary: fmt.Sprintf("%d saved-job schedule(s)", len(values)), Artifacts: artifacts, Errors: []Issue{}}, nil
}

func (d *Dispatcher) schedulesPut(p map[string]any) (Result, error) {
	scheduleID, err := requiredString(p, "schedule_id")
	if err != nil {
		return Result{}, err
	}
	jobID, err := requiredString(p, "job_id")
	if err != nil {
		return Result{}, err
	}
	var jobEnabled = true
	if d.Jobs != nil {
		job, err := d.Jobs.Get(jobID)
		if err != nil {
			return Result{}, err
		}
		if job == nil {
			return Result{}, berror.NotFound("Saved job not found: "+jobID, map[string]any{"job_id": jobID})
		}
		jobEnabled = job.Enabled
	}
	rawEvery, ok := p["interval_minutes"]
	if !ok {
		return Result{}, berror.Validation("interval_minutes is required", nil)
	}
	every, err := int64Value(rawEvery)
	if err != nil {
		return Result{}, err
	}
	enabled, err := boolDefault(p, "enabled", true)
	if err != nil {
		return Result{}, err
	}
	if enabled && !jobEnabled {
		return Result{}, berror.Validation("cannot enable a schedule for a disabled saved job", map[string]any{"job_id": jobID})
	}
	charging, err := boolDefault(p, "require_charging", false)
	if err != nil {
		return Result{}, err
	}
	network := strings.ToLower(optionalString(p["network"]))
	if network == "" {
		network = "none"
	}
	value, err := d.scheduleStore().Put(schedulestore.Schedule{ScheduleID: scheduleID, JobID: jobID, Enabled: enabled, IntervalMinutes: every, RequireCharging: charging, Network: network})
	if err != nil {
		return Result{}, err
	}
	return Result{Status: StatusSuccess, Summary: "Saved-job schedule stored", Artifacts: []Artifact{scheduleArtifact(value)}, Errors: []Issue{}}, nil
}

func (d *Dispatcher) schedulesSetEnabled(p map[string]any) (Result, error) {
	id, err := requiredString(p, "schedule_id")
	if err != nil {
		return Result{}, err
	}
	enabled, err := boolDefault(p, "enabled", true)
	if err != nil {
		return Result{}, err
	}
	if enabled && d.Jobs != nil {
		current, err := d.scheduleStore().Get(id)
		if err != nil {
			return Result{}, err
		}
		if current == nil {
			return Result{}, berror.NotFound("Schedule not found: "+id, map[string]any{"schedule_id": id})
		}
		job, err := d.Jobs.Get(current.JobID)
		if err != nil {
			return Result{}, err
		}
		if job == nil {
			return Result{}, berror.NotFound("Saved job not found: "+current.JobID, map[string]any{"job_id": current.JobID})
		}
		if !job.Enabled {
			return Result{}, berror.Validation("cannot enable a schedule for a disabled saved job", map[string]any{"job_id": current.JobID, "schedule_id": id})
		}
	}
	value, err := d.scheduleStore().SetEnabled(id, enabled)
	if err != nil {
		return Result{}, err
	}
	return Result{Status: StatusSuccess, Summary: "Saved-job schedule updated", Artifacts: []Artifact{scheduleArtifact(*value)}, Errors: []Issue{}}, nil
}

func (d *Dispatcher) schedulesDelete(p map[string]any) (Result, error) {
	id, err := requiredString(p, "schedule_id")
	if err != nil {
		return Result{}, err
	}
	deleted, err := d.scheduleStore().Delete(id)
	if err != nil {
		return Result{}, err
	}
	if !deleted {
		return Result{}, berror.NotFound("Schedule not found: "+id, map[string]any{"schedule_id": id})
	}
	return Result{Status: StatusSuccess, Summary: "Saved-job schedule removed", Artifacts: []Artifact{}, Errors: []Issue{}}, nil
}

func (d *Dispatcher) scheduleStore() *schedulestore.Store {
	if d.Schedules != nil {
		return d.Schedules
	}
	// NewWithStores initializes this; retaining a defensive fallback keeps older
	// direct Dispatcher literals source-compatible in tests/integrations.
	home, _ := os.UserHomeDir()
	return schedulestore.Default(home)
}

func scheduleArtifact(v schedulestore.Schedule) Artifact {
	raw, _ := json.Marshal(v)
	return Artifact{Label: "saved_job_schedule", Path: v.ScheduleID, Metadata: map[string]string{
		"schedule_json": string(raw), "schedule_id": v.ScheduleID, "job_id": v.JobID,
		"enabled": strconv.FormatBool(v.Enabled), "interval_minutes": strconv.FormatInt(v.IntervalMinutes, 10),
		"require_charging": strconv.FormatBool(v.RequireCharging), "network": v.Network,
	}}
}
