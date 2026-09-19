package filelock

import (
	"errors"
	"os"
	"sync/atomic"
	"syscall"
	"testing"
	"time"
)

func withFakeFcntl(t *testing.T, fn func(uintptr, int, *syscall.Flock_t) error) {
	t.Helper()
	original := fcntlFlock
	fcntlFlock = fn
	t.Cleanup(func() { fcntlFlock = original })
}

func tempLockFile(t *testing.T) *os.File {
	t.Helper()
	f, err := os.CreateTemp(t.TempDir(), "lock")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = f.Close() })
	return f
}

func TestAcquireUsesBlockingFcntlAndUnlocks(t *testing.T) {
	calls := 0
	withFakeFcntl(t, func(_ uintptr, cmd int, lock *syscall.Flock_t) error {
		calls++
		if calls == 1 && (cmd != syscall.F_SETLKW || lock.Type != int16(syscall.F_WRLCK)) {
			t.Fatalf("unexpected acquire: cmd=%d type=%d", cmd, lock.Type)
		}
		if calls == 2 && (cmd != syscall.F_SETLK || lock.Type != int16(syscall.F_UNLCK)) {
			t.Fatalf("unexpected unlock: cmd=%d type=%d", cmd, lock.Type)
		}
		return nil
	})
	unlock, err := Acquire(tempLockFile(t), Exclusive)
	if err != nil {
		t.Fatalf("Acquire: %v", err)
	}
	unlock()
	unlock() // idempotent
	if calls != 2 {
		t.Fatalf("fcntl calls=%d want=2", calls)
	}
}

func TestAcquireFailureNeverContinuesUnlocked(t *testing.T) {
	denied := errors.New("denied")
	withFakeFcntl(t, func(uintptr, int, *syscall.Flock_t) error { return denied })
	if unlock, err := Acquire(tempLockFile(t), Exclusive); !errors.Is(err, denied) || unlock != nil {
		t.Fatalf("Acquire returned unlock=%t err=%v, want unlock=false err=%v", unlock != nil, err, denied)
	}
}

func TestExclusiveLocksSerializeGoroutinesInSameProcess(t *testing.T) {
	withFakeFcntl(t, func(uintptr, int, *syscall.Flock_t) error { return nil })
	dir := t.TempDir()
	path := dir + "/shared.lock"
	first, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	second, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()

	unlockFirst, err := Acquire(first, Exclusive)
	if err != nil {
		t.Fatal(err)
	}
	var acquired atomic.Bool
	done := make(chan struct{})
	go func() {
		unlockSecond, err := Acquire(second, Exclusive)
		if err == nil {
			acquired.Store(true)
			unlockSecond()
		}
		close(done)
	}()
	time.Sleep(30 * time.Millisecond)
	if acquired.Load() {
		t.Fatal("second same-process exclusive lock bypassed local serialization")
	}
	unlockFirst()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("second lock did not proceed after unlock")
	}
	if !acquired.Load() {
		t.Fatal("second lock never acquired")
	}
}
