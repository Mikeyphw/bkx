package tools

import (
	"bytes"
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	"bkx/internal/berror"
)

type Info struct {
	Name         string   `json:"name"`
	Path         *string  `json:"path"`
	Version      *string  `json:"version"`
	Available    bool     `json:"available"`
	Capabilities []string `json:"capabilities"`
}

type record struct {
	info        Info
	fingerprint string
}

type Detector struct {
	mu          sync.Mutex
	cache       map[string]record
	preferences map[string]string
	environ     map[string]string
	timeout     time.Duration
}

var versionRE = regexp.MustCompile(`\d+(?:\.\d+)+(?:[-+._~A-Za-z0-9]*)?`)

var knownTools = map[string]string{
	"python": "runtime", "zstd": "compression", "gzip": "compression", "pigz": "compression", "pbzip2": "compression", "xz": "compression", "bzip2": "compression", "7z": "compression", "zip": "compression", "tar": "archiving", "rsync": "transfer", "rclone": "cloud", "restic": "backup", "scp": "transfer", "cp": "transfer", "xxh128": "hashing", "xxh64": "hashing", "sha256sum": "hashing", "sha512sum": "hashing", "md5sum": "hashing",
}
var preferredTools = map[string][]string{
	"archiver": {"tar"}, "compression": {"zstd", "pigz", "xz", "gzip"}, "transfer": {"rsync", "rclone", "scp", "cp"}, "hashing": {"xxh128", "xxh64", "sha256sum"},
}

func New(preferences map[string]string, environ map[string]string, timeout time.Duration) (*Detector, error) {
	if timeout <= 0 {
		return nil, berror.Validation("tool probe timeout must be positive", map[string]any{"timeout_seconds": timeout.Seconds()})
	}
	if environ == nil {
		environ = currentEnv()
	} else {
		environ = clone(environ)
	}
	prefs := map[string]string{}
	for k, v := range preferences {
		if strings.TrimSpace(v) != "" {
			prefs[canonicalCategory(k)] = v
		}
	}
	return &Detector{cache: map[string]record{}, preferences: prefs, environ: environ, timeout: timeout}, nil
}

func (d *Detector) ClearCache() { d.mu.Lock(); d.cache = map[string]record{}; d.mu.Unlock() }

func (d *Detector) SetEnv(key, value string) { d.mu.Lock(); d.environ[key] = value; d.mu.Unlock() }

func (d *Detector) Detect(toolName string) (Info, error) {
	name := strings.TrimSpace(toolName)
	if name == "" {
		return Info{}, berror.Validation("tool_name must be a non-empty string", nil)
	}
	d.mu.Lock()
	pathEnv := d.environ["PATH"]
	found := which(name, pathEnv)
	fp := pathEnv + "\x00" + found
	if cached, ok := d.cache[name]; ok && cached.fingerprint == fp {
		d.mu.Unlock()
		return cached.info, nil
	}
	env := clone(d.environ)
	timeout := d.timeout
	d.mu.Unlock()
	var info Info
	if found == "" {
		info = Info{Name: name, Available: false, Capabilities: []string{}}
	} else {
		p := found
		version := probeVersion(name, found, env, timeout)
		info = Info{Name: name, Path: &p, Version: version, Available: true, Capabilities: capabilities(name)}
	}
	d.mu.Lock()
	d.cache[name] = record{info: info, fingerprint: fp}
	d.mu.Unlock()
	return info, nil
}

func (d *Detector) DetectAll(names []string) (map[string]Info, error) {
	out := make(map[string]Info, len(names))
	for _, name := range names {
		info, err := d.Detect(name)
		if err != nil {
			return nil, err
		}
		out[name] = info
	}
	return out, nil
}

func (d *Detector) ListAvailable() ([]string, error) {
	names := make([]string, 0, len(knownTools))
	for name := range knownTools {
		names = append(names, name)
	}
	sort.Strings(names)
	out := make([]string, 0)
	for _, name := range names {
		info, err := d.Detect(name)
		if err != nil {
			return nil, err
		}
		if info.Available {
			out = append(out, name)
		}
	}
	return out, nil
}

func (d *Detector) Preferred(category string) (string, error) {
	cat := canonicalCategory(category)
	choices, ok := preferredTools[cat]
	if !ok {
		return "", berror.Validation("Unknown tool category", map[string]any{"category": category})
	}
	if configured := d.preferences[cat]; configured != "" {
		info, err := d.Detect(configured)
		if err != nil {
			return "", err
		}
		if info.Available && toolMatchesCategory(configured, cat) {
			return configured, nil
		}
		return "", nil
	}
	for _, name := range choices {
		info, err := d.Detect(name)
		if err != nil {
			return "", err
		}
		if info.Available && toolMatchesCategory(name, cat) {
			return name, nil
		}
	}
	return "", nil
}

func currentEnv() map[string]string {
	out := map[string]string{}
	for _, item := range os.Environ() {
		if k, v, ok := strings.Cut(item, "="); ok {
			out[k] = v
		}
	}
	return out
}
func clone(in map[string]string) map[string]string {
	out := map[string]string{}
	for k, v := range in {
		out[k] = v
	}
	return out
}
func canonicalCategory(c string) string {
	if c == "hasher" {
		return "hashing"
	}
	if c == "compressor" {
		return "compression"
	}
	return c
}

func which(name, pathEnv string) string {
	if strings.ContainsRune(name, filepath.Separator) {
		if executable(name) {
			if abs, err := filepath.Abs(name); err == nil {
				return abs
			}
			return name
		}
		return ""
	}
	for _, dir := range filepath.SplitList(pathEnv) {
		if dir == "" {
			dir = "."
		}
		candidate := filepath.Join(dir, name)
		if executable(candidate) {
			if abs, err := filepath.Abs(candidate); err == nil {
				return abs
			}
			return candidate
		}
	}
	return ""
}
func executable(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir() && info.Mode().Perm()&0o111 != 0
}

func probeVersion(name, path string, env map[string]string, timeout time.Duration) *string {
	for _, flag := range []string{"--version", "-V", "version"} {
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		cmd := exec.CommandContext(ctx, path, flag)
		cmd.Env = envSlice(env)
		var stdout, stderr bytes.Buffer
		cmd.Stdout = &stdout
		cmd.Stderr = &stderr
		_ = cmd.Run()
		cancel()
		output := strings.TrimSpace(stdout.String() + "\n" + stderr.String())
		if output == "" {
			continue
		}
		if match := versionRE.FindString(output); match != "" {
			v := match
			return &v
		}
		first := strings.TrimSpace(strings.Split(output, "\n")[0])
		if first != "" {
			v := first
			return &v
		}
	}
	return nil
}
func envSlice(env map[string]string) []string {
	keys := make([]string, 0, len(env))
	for k := range env {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	out := make([]string, 0, len(keys))
	for _, k := range keys {
		out = append(out, k+"="+env[k])
	}
	return out
}

func capabilities(name string) []string {
	var out []string
	add := func(values ...string) {
		for _, v := range values {
			found := false
			for _, x := range out {
				if x == v {
					found = true
					break
				}
			}
			if !found {
				out = append(out, v)
			}
		}
	}
	switch name {
	case "zstd":
		add("compression", "zstd")
	case "gzip", "pigz":
		add("compression", "gzip")
	case "bzip2", "pbzip2":
		add("compression", "bzip2")
	case "xz":
		add("compression", "xz")
	case "tar":
		add("archiver", "tar")
	case "7z", "7za":
		add("archiver", "7z")
	case "zip":
		add("archiver", "zip")
	case "rsync", "rclone", "scp", "cp":
		add("transfer", name)
	case "restic":
		add("backup", "snapshot", "deduplication", "encryption")
	}
	if strings.HasPrefix(name, "sha") || strings.HasPrefix(name, "md5") || strings.HasPrefix(name, "xxh") {
		add("hashing")
		if strings.Contains(name, "256") {
			add("sha256")
		} else if strings.Contains(name, "512") {
			add("sha512")
		} else if strings.HasPrefix(name, "md5") {
			add("md5")
		} else {
			add(name)
		}
	}
	if name == "python" {
		add("runtime")
	}
	return out
}
func toolMatchesCategory(name, category string) bool {
	known, ok := knownTools[name]
	if !ok {
		return true
	}
	if known == category {
		return true
	}
	for _, cap := range capabilities(name) {
		if cap == category || (category == "archiver" && cap == "archiver") {
			return true
		}
	}
	return false
}
