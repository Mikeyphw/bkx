package publiccli

import (
	"fmt"
	"os"
	"strings"
	"time"
)

type cliExecutionLease struct {
	c         *appContext
	requestID string
}

func beginCLIExecution(c *appContext, operation, jobID, repositoryID string) (*cliExecutionLease, error) {
	requestID := fmt.Sprintf("cli-%s-%d-%d", strings.NewReplacer(".", "-", "_", "-").Replace(operation), os.Getpid(), time.Now().UnixNano())
	if _, err := c.control.Acquire(requestID, operation, jobID, repositoryID, "cli"); err != nil {
		return nil, err
	}
	if c.globals.Verbose {
		c.render.verbose("execution request id: " + requestID)
	}
	return &cliExecutionLease{c: c, requestID: requestID}, nil
}

func (l *cliExecutionLease) Cancelled() bool {
	return l != nil && l.c != nil && l.c.control.CancelRequested(l.requestID)
}

func (l *cliExecutionLease) Close() {
	if l == nil || l.c == nil {
		return
	}
	_ = l.c.control.Release(l.requestID)
	_ = l.c.control.ClearCancel(l.requestID)
}

func repositoryLeaseID(c *appContext, location string) string {
	location = strings.TrimSpace(location)
	if values, err := c.repositories.List(); err == nil {
		for _, value := range values {
			if strings.TrimSpace(value.Location) == location {
				return value.RepositoryID
			}
		}
	}
	return "location:" + location
}
