package filelock

import (
	"os"
	"path/filepath"
	"sync"
	"syscall"
)

// Mode describes whether callers need shared or exclusive access.
type Mode int

const (
	Shared Mode = iota
	Exclusive
)

// POSIX record locks are process-scoped, so they do not serialize goroutines
// inside one BKX process. Keep a process-local RWMutex for each lock path and
// pair it with the same fcntl lock protocol in every BKX process.
var localLocks sync.Map // map[string]*sync.RWMutex
var fcntlFlock = syscall.FcntlFlock

func localLockFor(file *os.File) *sync.RWMutex {
	name := file.Name()
	if abs, err := filepath.Abs(name); err == nil {
		name = filepath.Clean(abs)
	} else {
		name = filepath.Clean(name)
	}
	lock, _ := localLocks.LoadOrStore(name, &sync.RWMutex{})
	return lock.(*sync.RWMutex)
}

// Acquire obtains a blocking advisory lock on file.
//
// BKX intentionally uses POSIX fcntl locks on every supported Unix process,
// rather than mixing flock and fcntl. Linux does not make those two lock
// families mutually visible, so a conditional flock->fcntl fallback could let
// an Android process and a CLI process enter the same critical section.
//
// fcntl locks are process-scoped, therefore a per-path RWMutex also serializes
// concurrent goroutines in this process. Acquisition never succeeds unlocked.
func Acquire(file *os.File, mode Mode) (func(), error) {
	local := localLockFor(file)
	lockType := int16(syscall.F_RDLCK)
	if mode == Exclusive {
		local.Lock()
		lockType = int16(syscall.F_WRLCK)
	} else {
		local.RLock()
	}

	releaseLocal := func() {
		if mode == Exclusive {
			local.Unlock()
		} else {
			local.RUnlock()
		}
	}

	lock := syscall.Flock_t{Type: lockType, Whence: int16(0), Start: 0, Len: 0}
	if err := fcntlFlock(uintptr(file.Fd()), syscall.F_SETLKW, &lock); err != nil {
		releaseLocal()
		return nil, err
	}

	var once sync.Once
	return func() {
		once.Do(func() {
			unlock := syscall.Flock_t{Type: int16(syscall.F_UNLCK), Whence: int16(0), Start: 0, Len: 0}
			_ = fcntlFlock(uintptr(file.Fd()), syscall.F_SETLK, &unlock)
			releaseLocal()
		})
	}, nil
}
