package publiccli

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"

	"bkx/internal/berror"
	"bkx/internal/schedulestore"
)

func scheduleAdd(c *appContext, args []string) int { return schedulePut(c, args, false) }
func scheduleSet(c *appContext, args []string) int { return schedulePut(c, args, true) }
func schedulePut(c *appContext, args []string, update bool) int {
	specs := map[string]optionSpec{
		"--job":               {Key: "job", Kind: optionValue},
		"--every":             {Key: "every", Kind: optionValue},
		"--network":           {Key: "network", Kind: optionValue},
		"--charging":          {Key: "charging", Kind: optionBoolTrue},
		"--disabled":          {Key: "disabled", Kind: optionBoolTrue},
		"--expected-revision": {Key: "expected_revision", Kind: optionValue},
	}
	command := "schedule add"
	if update {
		command = "schedule set"
	}
	a, ok := parseFor(c, command, args, specs)
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "SCHEDULE_ID"); err != nil {
		return cliUsageError(c, err)
	}
	existing, err := c.schedules.Get(a.Positionals[0])
	if err != nil {
		return commandErr(c, err)
	}
	if !update && existing != nil {
		return commandErr(c, berror.Validation("Schedule already exists: "+a.Positionals[0], map[string]any{"schedule_id": a.Positionals[0]}))
	}
	if update && existing == nil {
		return commandErr(c, berror.NotFound("Schedule not found: "+a.Positionals[0], map[string]any{"schedule_id": a.Positionals[0]}))
	}
	jobID, set := a.stringValue("job")
	if !set || strings.TrimSpace(jobID) == "" {
		if update {
			existing, err := c.schedules.Get(a.Positionals[0])
			if err != nil {
				return commandErr(c, err)
			}
			if existing != nil {
				jobID = existing.JobID
				set = true
			}
		}
	}
	if !set || strings.TrimSpace(jobID) == "" {
		return cliUsageError(c, fmt.Errorf("option --job is required"))
	}
	job, err := c.jobs.Get(strings.TrimSpace(jobID))
	if err != nil {
		return commandErr(c, err)
	}
	if job == nil {
		return commandErr(c, berror.NotFound("Saved job not found: "+jobID, map[string]any{"job_id": jobID}))
	}
	everyText, set := a.stringValue("every")
	if !set {
		if update {
			if existing, err := c.schedules.Get(a.Positionals[0]); err == nil && existing != nil {
				everyText = fmt.Sprintf("%d", existing.IntervalMinutes)
				set = true
			} else if err != nil {
				return commandErr(c, err)
			}
		}
	}
	if !set {
		return cliUsageError(c, fmt.Errorf("option --every is required"))
	}
	every, err := strconv.ParseInt(strings.TrimSpace(everyText), 10, 64)
	if err != nil {
		return commandErr(c, berror.Validation("--every must be an integer number of minutes", nil))
	}
	network, _ := a.stringValue("network")
	if strings.TrimSpace(network) == "" {
		if update && existing != nil {
			network = existing.Network
		} else {
			network = "none"
		}
	}
	charging, _ := a.triBool("charging")
	disabled, _ := a.triBool("disabled")
	enabled := true
	if update && existing != nil {
		enabled = existing.Enabled
	}
	if disabled != nil {
		enabled = !*disabled
	}
	if enabled && !job.Enabled {
		return commandErr(c, berror.Validation("cannot enable a schedule for a disabled saved job", map[string]any{"job_id": job.JobID, "schedule_id": a.Positionals[0]}))
	}
	requireCharging := charging != nil && *charging
	if update && existing != nil && charging == nil {
		requireCharging = existing.RequireCharging
	}
	var expected *int64
	if raw, set := a.stringValue("expected_revision"); set {
		value, err := strconv.ParseInt(strings.TrimSpace(raw), 10, 64)
		if err != nil || value < 1 {
			return commandErr(c, berror.Validation("--expected-revision must be a positive integer", nil))
		}
		expected = &value
	}
	value, err := c.schedules.PutExpected(schedulestore.Schedule{ScheduleID: a.Positionals[0], JobID: job.JobID, Enabled: enabled, IntervalMinutes: every, RequireCharging: requireCharging, Network: network}, expected)
	if err != nil {
		return commandErr(c, err)
	}
	notifyAndroidScheduler()
	return renderResult(c, value, "schedule")
}
func scheduleList(c *appContext, args []string) int {
	a, ok := parseFor(c, "schedule list", args, map[string]optionSpec{})
	if !ok {
		return berror.ExitOK
	}
	if len(a.Positionals) > 0 {
		return cliUsageError(c, fmt.Errorf("schedule list takes no arguments"))
	}
	v, err := c.schedules.List()
	if err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, v, "schedules")
}
func scheduleShow(c *appContext, args []string) int {
	a, ok := parseFor(c, "schedule show", args, map[string]optionSpec{})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "SCHEDULE_ID"); err != nil {
		return cliUsageError(c, err)
	}
	v, err := c.schedules.Get(a.Positionals[0])
	if err != nil {
		return commandErr(c, err)
	}
	if v == nil {
		return commandErr(c, berror.NotFound("Schedule not found: "+a.Positionals[0], nil))
	}
	return renderResult(c, *v, "schedule")
}
func scheduleEnable(c *appContext, args []string) int  { return scheduleSetEnabled(c, args, true) }
func scheduleDisable(c *appContext, args []string) int { return scheduleSetEnabled(c, args, false) }
func scheduleSetEnabled(c *appContext, args []string, enabled bool) int {
	command := "schedule disable"
	if enabled {
		command = "schedule enable"
	}
	a, ok := parseFor(c, command, args, map[string]optionSpec{"--expected-revision": {Key: "expected_revision", Kind: optionValue}})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "SCHEDULE_ID"); err != nil {
		return cliUsageError(c, err)
	}
	var expected *int64
	if raw, set := a.stringValue("expected_revision"); set {
		value, err := strconv.ParseInt(strings.TrimSpace(raw), 10, 64)
		if err != nil || value < 1 {
			return commandErr(c, berror.Validation("--expected-revision must be a positive integer", nil))
		}
		expected = &value
	}
	if enabled {
		current, err := c.schedules.Get(a.Positionals[0])
		if err != nil {
			return commandErr(c, err)
		}
		if current != nil {
			job, err := c.jobs.Get(current.JobID)
			if err != nil {
				return commandErr(c, err)
			}
			if job == nil || !job.Enabled {
				return commandErr(c, berror.Validation("cannot enable a schedule for a disabled or missing saved job", map[string]any{"job_id": current.JobID, "schedule_id": current.ScheduleID}))
			}
		}
	}
	v, err := c.schedules.SetEnabledExpected(a.Positionals[0], enabled, expected)
	if err != nil {
		return commandErr(c, err)
	}
	notifyAndroidScheduler()
	return renderResult(c, *v, "schedule")
}
func scheduleRemove(c *appContext, args []string) int {
	a, ok := parseFor(c, "schedule remove", args, map[string]optionSpec{})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "SCHEDULE_ID"); err != nil {
		return cliUsageError(c, err)
	}
	deleted, err := c.schedules.Delete(a.Positionals[0])
	if err != nil {
		return commandErr(c, err)
	}
	if !deleted {
		return commandErr(c, berror.NotFound("Schedule not found: "+a.Positionals[0], nil))
	}
	notifyAndroidScheduler()
	return renderResult(c, map[string]any{"schedule_id": a.Positionals[0], "removed": true}, "schedule")
}

func notifyAndroidScheduler() {
	const am = "/system/bin/am"
	if _, err := os.Stat(am); err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_ = exec.CommandContext(ctx, am, "broadcast", "-a", "com.mikeyphw.bkx.android.action.RECONCILE_SCHEDULES", "-p", "com.mikeyphw.bkx.android").Run()
}

func runCancel(c *appContext, args []string) int {
	a, ok := parseFor(c, "run cancel", args, map[string]optionSpec{})
	if !ok {
		return berror.ExitOK
	}
	if err := requirePositionals(a.Positionals, 1, 1, "REQUEST_ID"); err != nil {
		return cliUsageError(c, err)
	}
	target := strings.TrimSpace(a.Positionals[0])
	if err := c.control.RequestCancel(target); err != nil {
		return commandErr(c, err)
	}
	return renderResult(c, map[string]any{"request_id": target, "status": "cancellation_requested"}, "run_cancel")
}
