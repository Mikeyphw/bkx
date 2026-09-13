package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"bkx/internal/bridge"
	"bkx/internal/contracts"
	"bkx/internal/executioncontrol"
	"bkx/internal/publiccli"
	"bkx/internal/runstore"
)

func main() { os.Exit(run(os.Args[1:])) }

func run(args []string) int {
	if publiccli.IsInternalInvocation(args) {
		return runInternal(args)
	}
	return publiccli.Run(args, os.Stdout, os.Stderr)
}

// The post-migration binary keeps only two hidden maintenance/runtime routes:
// contract check validates immutable compatibility evidence, and android-runtime
// bridge-session is the process transport used by the Android application.
func runInternal(args []string) int {
	if len(args) >= 2 && args[0] == "contract" && args[1] == "check" {
		dir := filepath.Join("migration", "contracts", "v1")
		if len(args) == 3 {
			dir = args[2]
		}
		if len(args) > 3 {
			fmt.Fprintln(os.Stderr, "usage: bkx contract check [contract-dir]")
			return 64
		}
		bundle, err := contracts.LoadDir(dir)
		if err == nil {
			err = bundle.Validate()
		}
		if err != nil {
			fmt.Fprintf(os.Stderr, "bkx: contract check failed: %v\n", err)
			return 1
		}
		fmt.Println("BKX GO00 contracts valid.")
		return 0
	}

	if len(args) >= 2 && args[0] == "android-runtime" {
		if args[1] != "bridge-session" || len(args) != 3 {
			fmt.Fprintln(os.Stderr, "usage: bkx android-runtime bridge-session RUNS_OR_DASH")
			return 64
		}
		store, err := optionalRunStore(args[2])
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		return runConcurrentBridgeSession(bridge.NewAndroid(store))
	}

	fmt.Fprintln(os.Stderr, "bkx: unsupported internal command")
	return 64
}

func optionalRunStore(path string) (*runstore.Store, error) {
	if path == "-" {
		return nil, nil
	}
	if legacy := strings.TrimSpace(os.Getenv(runstore.EnvLegacyRunHistory)); legacy != "" {
		if _, err := runstore.MergeLegacyHistory(path, legacy); err != nil {
			return nil, err
		}
	}
	store, err := runstore.New(path, 0, false)
	if err != nil {
		return nil, err
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return nil, err
	}
	if _, err := store.RecoverInterruptedWithControl(executioncontrol.Default(home)); err != nil {
		return nil, err
	}
	return store, nil
}

type concurrentBridgeCommand struct {
	ID         string          `json:"id"`
	Entrypoint string          `json:"entrypoint"`
	RequestID  string          `json:"request_id"`
	Request    json.RawMessage `json:"request"`
}

type concurrentBridgeReply struct {
	ID    string `json:"id"`
	Value any    `json:"value"`
}

const (
	maxBridgeSessionCommandBytes = 4 * 1024 * 1024
	maxBridgeSessionReplyBytes   = 4 * 1024 * 1024
	maxBridgeSessionConcurrency  = 16
)

// runConcurrentBridgeSession is the Android process transport. Execute runs in
// its own goroutine so progress and cancellation remain serviceable while a
// long operation is active.
func runConcurrentBridgeSession(rt *bridge.RuntimeAdapter) int {
	scanner := bufio.NewScanner(os.Stdin)
	buffer := make([]byte, 0, 64*1024)
	scanner.Buffer(buffer, maxBridgeSessionCommandBytes)
	var writeMu sync.Mutex
	var wg sync.WaitGroup
	semaphore := make(chan struct{}, maxBridgeSessionConcurrency)

	write := func(reply concurrentBridgeReply) bool {
		var buf bytes.Buffer
		if err := json.NewEncoder(&buf).Encode(reply); err != nil {
			reply.Value = map[string]any{"session_error": "could not encode bridge reply: " + err.Error()}
			buf.Reset()
			_ = json.NewEncoder(&buf).Encode(reply)
		}
		if buf.Len() > maxBridgeSessionReplyBytes {
			reply.Value = map[string]any{"session_error": "bridge reply exceeds 4 MiB output limit"}
			buf.Reset()
			_ = json.NewEncoder(&buf).Encode(reply)
		}
		writeMu.Lock()
		defer writeMu.Unlock()
		_, err := os.Stdout.Write(buf.Bytes())
		return err == nil
	}

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		var command concurrentBridgeCommand
		if err := json.Unmarshal([]byte(line), &command); err != nil {
			write(concurrentBridgeReply{ID: "unknown", Value: map[string]any{"session_error": err.Error()}})
			continue
		}
		if strings.TrimSpace(command.ID) == "" {
			write(concurrentBridgeReply{ID: "unknown", Value: map[string]any{"session_error": "missing command id"}})
			continue
		}
		semaphore <- struct{}{}
		wg.Add(1)
		go func(command concurrentBridgeCommand) {
			defer wg.Done()
			defer func() { <-semaphore }()
			var value any
			switch command.Entrypoint {
			case "readiness":
				value = rt.Readiness()
			case "execute":
				value = rt.Execute(string(command.Request))
			case "progress":
				progress, err := rt.Progress(command.RequestID)
				if err != nil {
					value = map[string]any{"session_error": err.Error()}
				} else {
					value = progress
				}
			case "cancel":
				value = rt.Cancel(command.RequestID)
			default:
				value = map[string]any{"session_error": "unknown bridge session entrypoint"}
			}
			write(concurrentBridgeReply{ID: command.ID, Value: value})
		}(command)
	}
	if err := scanner.Err(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	wg.Wait()
	return 0
}
