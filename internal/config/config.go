package config

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"bkx/internal/berror"
)

const (
	SourceDefault = "default"
	SourceFile    = "file"
	SourceEnv     = "env"
	SourceCLI     = "cli"
)

var envMap = map[string]string{
	"BKX_DESTINATION":           "default_destination",
	"BKX_VERIFY":                "verify_after_operation",
	"BKX_COMPRESSION_ALGORITHM": "compression_algorithm",
	"BKX_COMPRESSION_LEVEL":     "compression_level",
	"BKX_FRESH_RESTORE_DIR":     "fresh_restore_dir",
	"BKX_PREFERRED_ARCHIVER":    "preferred_archiver",
	"BKX_PREFERRED_COMPRESSOR":  "preferred_compressor",
	"BKX_PREFERRED_HASHER":      "preferred_hasher",
	"BKX_PREFERRED_TRANSFER":    "preferred_transfer",
	"BKX_HISTORY_ENABLED":       "run_history_enabled",
	"BKX_HISTORY_LIMIT":         "run_history_limit",
	"BKX_TRACE":                 "trace",
}

var publicFields = map[string]struct{}{
	"default_destination": {}, "verify_after_operation": {}, "compression_algorithm": {},
	"compression_level": {}, "fresh_restore_dir": {}, "preferred_archiver": {},
	"preferred_compressor": {}, "preferred_hasher": {}, "preferred_transfer": {},
	"run_history_enabled": {}, "run_history_limit": {}, "trace": {},
}

type Config struct {
	DefaultDestination   *string `json:"default_destination"`
	VerifyAfterOperation bool    `json:"verify_after_operation"`
	CompressionAlgorithm string  `json:"compression_algorithm"`
	CompressionLevel     int     `json:"compression_level"`
	FreshRestoreDir      bool    `json:"fresh_restore_dir"`
	PreferredArchiver    *string `json:"preferred_archiver"`
	PreferredCompressor  *string `json:"preferred_compressor"`
	PreferredHasher      *string `json:"preferred_hasher"`
	PreferredTransfer    *string `json:"preferred_transfer"`
	RunHistoryEnabled    bool    `json:"run_history_enabled"`
	RunHistoryLimit      int     `json:"run_history_limit"`
	Trace                bool    `json:"trace"`
	provenance           map[string]string
	explicit             map[string]bool
	configPath           string
}

type ResolvedValue struct {
	Value  any    `json:"value"`
	Source string `json:"source"`
}

func Defaults() Config {
	c := Config{
		VerifyAfterOperation: true,
		CompressionAlgorithm: "zstd",
		CompressionLevel:     3,
		FreshRestoreDir:      true,
		RunHistoryEnabled:    true,
		RunHistoryLimit:      50,
		provenance:           map[string]string{},
		explicit:             map[string]bool{},
	}
	for name := range publicFields {
		c.provenance[name] = SourceDefault
	}
	return c
}

func FromMap(data map[string]any, baseDir string) (Config, error) {
	home, _ := os.UserHomeDir()
	return fromMapWithHome(data, baseDir, home)
}

func fromMapWithHome(data map[string]any, baseDir, home string) (Config, error) {
	c := Defaults()
	unknown := make([]string, 0)
	keys := make([]string, 0, len(data))
	for key := range data {
		keys = append(keys, key)
		if _, ok := publicFields[key]; !ok {
			unknown = append(unknown, key)
		}
	}
	sort.Strings(keys)
	if len(unknown) > 0 {
		sort.Strings(unknown)
		return Config{}, berror.Validation("Unknown BKX configuration key(s)", map[string]any{"keys": unknown})
	}
	for _, key := range keys {
		value := data[key]
		if err := c.setFileValueWithHome(key, value, baseDir, home); err != nil {
			return Config{}, err
		}
		c.provenance[key] = SourceFile
		c.explicit[key] = true
	}
	return c, nil
}

func FromEnv(environ map[string]string, cwd string) (Config, error) {
	c := Defaults()
	envNames := make([]string, 0, len(envMap))
	for envName := range envMap {
		envNames = append(envNames, envName)
	}
	sort.Strings(envNames)
	for _, envName := range envNames {
		field := envMap[envName]
		raw, ok := environ[envName]
		if !ok {
			continue
		}
		if err := c.setEnvValue(field, raw, envName, cwd); err != nil {
			return Config{}, err
		}
		c.provenance[field] = SourceEnv
		c.explicit[field] = true
	}
	return c, nil
}

func Load(cwd, home string, environ map[string]string) (Config, error) {
	base := Defaults()
	paths := []string{filepath.Join(cwd, ".bkx.yaml"), filepath.Join(cwd, "bkx.yaml"), filepath.Join(home, ".bkx.yaml")}
	for _, path := range paths {
		info, err := os.Stat(path)
		if err != nil {
			if os.IsNotExist(err) {
				continue
			}
			if os.IsPermission(err) {
				return Config{}, berror.Permission("Permission denied reading BKX configuration: "+path, map[string]any{"path": path, "reason": err.Error()})
			}
			return Config{}, berror.External("System error reading BKX configuration: "+path, map[string]any{"path": path, "reason": err.Error()})
		}
		if info.IsDir() {
			return Config{}, berror.Validation("BKX configuration path is a directory: "+path, map[string]any{"path": path})
		}
		data, err := parseFlatYAML(path)
		if err != nil {
			return Config{}, berror.Validation("Failed to parse BKX configuration: "+path, map[string]any{"path": path, "reason": err.Error()})
		}
		fileConfig, err := fromMapWithHome(data, filepath.Dir(path), home)
		if err != nil {
			return Config{}, err
		}
		fileConfig.configPath = path
		base = Merge(base, fileConfig)
		break
	}
	env, err := FromEnv(environ, cwd)
	if err != nil {
		return Config{}, err
	}
	return Merge(base, env), nil
}

func Merge(base, override Config) Config {
	out := base.clone()
	for field := range override.explicit {
		out.copyField(field, override)
		out.provenance[field] = override.provenance[field]
		out.explicit[field] = true
	}
	if override.configPath != "" {
		out.configPath = override.configPath
	}
	return out
}

func (c Config) SourceOf(field string) (string, error) {
	if _, ok := publicFields[field]; !ok {
		return "", fmt.Errorf("unknown config field %q", field)
	}
	if source, ok := c.provenance[field]; ok {
		return source, nil
	}
	return SourceDefault, nil
}

func (c Config) Resolve(field string, cliValue any, hasCLI bool) (ResolvedValue, error) {
	if _, ok := publicFields[field]; !ok {
		return ResolvedValue{}, fmt.Errorf("unknown config field %q", field)
	}
	if hasCLI {
		return ResolvedValue{Value: cliValue, Source: SourceCLI}, nil
	}
	source, _ := c.SourceOf(field)
	return ResolvedValue{Value: c.valueOf(field), Source: source}, nil
}

func (c Config) CompressionProfile() map[string]any {
	return map[string]any{"algorithm": c.CompressionAlgorithm, "level": c.CompressionLevel}
}

func (c Config) ToolPreferences() map[string]*string {
	return map[string]*string{
		"archiver": c.PreferredArchiver, "compressor": c.PreferredCompressor,
		"compression": c.PreferredCompressor, "hasher": c.PreferredHasher,
		"hashing": c.PreferredHasher, "transfer": c.PreferredTransfer,
	}
}

func (c *Config) setFileValue(field string, value any, baseDir string) error {
	home, _ := os.UserHomeDir()
	return c.setFileValueWithHome(field, value, baseDir, home)
}

func (c *Config) setFileValueWithHome(field string, value any, baseDir, home string) error {
	switch field {
	case "default_destination":
		if value == nil {
			c.DefaultDestination = nil
			return nil
		}
		s, ok := value.(string)
		if !ok {
			return typeError(field, "path string or null", value)
		}
		path, err := normalizePathWithHome(s, baseDir, home)
		if err != nil {
			return err
		}
		c.DefaultDestination = &path
	case "verify_after_operation", "fresh_restore_dir", "run_history_enabled", "trace":
		v, ok := value.(bool)
		if !ok {
			return typeError(field, "boolean", value)
		}
		c.setBool(field, v)
	case "compression_level", "run_history_limit":
		v, ok := asInt(value)
		if !ok {
			return typeError(field, "integer", value)
		}
		if field == "run_history_limit" && v <= 0 {
			return berror.Validation("run_history_limit must be greater than zero", map[string]any{"value": v})
		}
		if field == "compression_level" {
			c.CompressionLevel = v
		} else {
			c.RunHistoryLimit = v
		}
	case "compression_algorithm":
		s, ok := value.(string)
		if !ok || strings.TrimSpace(s) == "" {
			return typeError(field, "non-empty string", value)
		}
		c.CompressionAlgorithm = strings.TrimSpace(s)
	default:
		if value == nil {
			c.setOptional(field, nil)
			return nil
		}
		s, ok := value.(string)
		if !ok || strings.TrimSpace(s) == "" {
			return typeError(field, "non-empty string or null", value)
		}
		s = strings.TrimSpace(s)
		c.setOptional(field, &s)
	}
	return nil
}

func (c *Config) setEnvValue(field, raw, envName, cwd string) error {
	if field == "default_destination" {
		if strings.TrimSpace(raw) == "" {
			return berror.Validation(envName+" must not be empty", map[string]any{"environment": envName})
		}
		path, err := normalizePath(raw, cwd)
		if err != nil {
			return err
		}
		c.DefaultDestination = &path
		return nil
	}
	if field == "verify_after_operation" || field == "fresh_restore_dir" || field == "run_history_enabled" || field == "trace" {
		v, err := parseBool(raw, envName)
		if err != nil {
			return err
		}
		c.setBool(field, v)
		return nil
	}
	if field == "compression_level" || field == "run_history_limit" {
		v, err := strconv.Atoi(raw)
		if err != nil {
			return berror.Validation(envName+" must be an integer", map[string]any{"environment": envName, "value": raw})
		}
		if field == "run_history_limit" && v <= 0 {
			return berror.Validation(envName+" must be greater than zero", map[string]any{"environment": envName, "value": raw})
		}
		if field == "compression_level" {
			c.CompressionLevel = v
		} else {
			c.RunHistoryLimit = v
		}
		return nil
	}
	if strings.TrimSpace(raw) == "" {
		return berror.Validation(envName+" must not be empty", map[string]any{"environment": envName})
	}
	s := strings.TrimSpace(raw)
	if field == "compression_algorithm" {
		c.CompressionAlgorithm = s
	} else {
		c.setOptional(field, &s)
	}
	return nil
}

func parseBool(raw, name string) (bool, error) {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "true", "1", "yes", "on":
		return true, nil
	case "false", "0", "no", "off":
		return false, nil
	default:
		return false, berror.Validation(name+" must be a boolean", map[string]any{"value": raw, "accepted": []string{"true", "false", "1", "0", "yes", "no", "on", "off"}})
	}
}

func normalizePath(raw, baseDir string) (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		home = ""
	}
	return normalizePathWithHome(raw, baseDir, home)
}

func normalizePathWithHome(raw, baseDir, home string) (string, error) {
	value := strings.TrimSpace(raw)
	if strings.HasPrefix(value, "~") {
		if strings.TrimSpace(home) == "" {
			return "", berror.Validation("home directory is required to expand ~ in BKX configuration path", map[string]any{"path": raw})
		}
		if value == "~" {
			value = home
		} else if strings.HasPrefix(value, "~/") {
			value = filepath.Join(home, value[2:])
		}
	}
	if !filepath.IsAbs(value) {
		value = filepath.Join(baseDir, value)
	}
	return filepath.Abs(value)
}

func parseFlatYAML(path string) (map[string]any, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	out := map[string]any{}
	s := bufio.NewScanner(f)
	for lineNo := 1; s.Scan(); lineNo++ {
		rawLine := strings.TrimRightFunc(s.Text(), unicode.IsSpace)
		line := strings.TrimSpace(rawLine)
		if line == "" || strings.HasPrefix(line, "#") || line == "---" || line == "..." {
			continue
		}
		if strings.HasPrefix(rawLine, " ") || strings.HasPrefix(rawLine, "\t") {
			return nil, fmt.Errorf("line %d uses nested YAML; BKX config accepts a top-level scalar mapping", lineNo)
		}
		parts := strings.SplitN(line, ":", 2)
		if len(parts) != 2 {
			return nil, fmt.Errorf("line %d is not a key/value mapping", lineNo)
		}
		key := strings.TrimSpace(parts[0])
		raw := stripYAMLComment(strings.TrimSpace(parts[1]))
		if key == "" {
			return nil, fmt.Errorf("line %d has empty key", lineNo)
		}
		if strings.HasPrefix(key, "-") || strings.ContainsAny(key, "[]{}") {
			return nil, fmt.Errorf("line %d has unsupported YAML key syntax", lineNo)
		}
		value, err := parseYAMLScalar(raw)
		if err != nil {
			return nil, fmt.Errorf("line %d: %w", lineNo, err)
		}
		out[key] = value
	}
	if err := s.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

func stripYAMLComment(raw string) string {
	inSingle := false
	inDouble := false
	escaped := false
	for i, r := range raw {
		if escaped {
			escaped = false
			continue
		}
		if inDouble && r == '\\' {
			escaped = true
			continue
		}
		switch r {
		case '\'':
			if !inDouble {
				inSingle = !inSingle
			}
		case '"':
			if !inSingle {
				inDouble = !inDouble
			}
		case '#':
			if !inSingle && !inDouble && (i == 0 || previousRuneIsSpace(raw[:i])) {
				return strings.TrimSpace(raw[:i])
			}
		}
	}
	return strings.TrimSpace(raw)
}

func previousRuneIsSpace(prefix string) bool {
	for i := len(prefix); i > 0; {
		r, size := rune(prefix[i-1]), 1
		if r >= 0x80 {
			r, size = utf8.DecodeLastRuneInString(prefix[:i])
		}
		_ = size
		return unicode.IsSpace(r)
	}
	return true
}

func parseYAMLScalar(raw string) (any, error) {
	if raw == "" {
		return nil, nil
	}
	lower := strings.ToLower(raw)
	switch lower {
	case "null", "~":
		return nil, nil
	case "true", "yes", "on":
		return true, nil
	case "false", "no", "off":
		return false, nil
	}
	if len(raw) >= 2 && raw[0] == '"' && raw[len(raw)-1] == '"' {
		value, err := strconv.Unquote(raw)
		if err != nil {
			return nil, err
		}
		return value, nil
	}
	if len(raw) >= 2 && raw[0] == '\'' && raw[len(raw)-1] == '\'' {
		return strings.ReplaceAll(raw[1:len(raw)-1], "''", "'"), nil
	}
	if strings.HasPrefix(raw, "[") || strings.HasPrefix(raw, "{") {
		return nil, fmt.Errorf("BKX config fields require scalar values")
	}
	if v, err := strconv.Atoi(raw); err == nil {
		return v, nil
	}
	return raw, nil
}

func typeError(field, expected string, value any) error {
	return berror.Validation("Invalid type for BKX config field '"+field+"'", map[string]any{"field": field, "expected": expected, "actual_type": fmt.Sprintf("%T", value)})
}

func asInt(value any) (int, bool) { v, ok := value.(int); return v, ok }
func (c *Config) setBool(field string, value bool) {
	switch field {
	case "verify_after_operation":
		c.VerifyAfterOperation = value
	case "fresh_restore_dir":
		c.FreshRestoreDir = value
	case "run_history_enabled":
		c.RunHistoryEnabled = value
	case "trace":
		c.Trace = value
	}
}
func (c *Config) setOptional(field string, value *string) {
	switch field {
	case "preferred_archiver":
		c.PreferredArchiver = value
	case "preferred_compressor":
		c.PreferredCompressor = value
	case "preferred_hasher":
		c.PreferredHasher = value
	case "preferred_transfer":
		c.PreferredTransfer = value
	}
}
func (c Config) clone() Config {
	out := c
	out.provenance = map[string]string{}
	for k, v := range c.provenance {
		out.provenance[k] = v
	}
	out.explicit = map[string]bool{}
	for k, v := range c.explicit {
		out.explicit[k] = v
	}
	return out
}
func (c *Config) copyField(field string, src Config) {
	switch field {
	case "default_destination":
		c.DefaultDestination = src.DefaultDestination
	case "verify_after_operation":
		c.VerifyAfterOperation = src.VerifyAfterOperation
	case "compression_algorithm":
		c.CompressionAlgorithm = src.CompressionAlgorithm
	case "compression_level":
		c.CompressionLevel = src.CompressionLevel
	case "fresh_restore_dir":
		c.FreshRestoreDir = src.FreshRestoreDir
	case "preferred_archiver":
		c.PreferredArchiver = src.PreferredArchiver
	case "preferred_compressor":
		c.PreferredCompressor = src.PreferredCompressor
	case "preferred_hasher":
		c.PreferredHasher = src.PreferredHasher
	case "preferred_transfer":
		c.PreferredTransfer = src.PreferredTransfer
	case "run_history_enabled":
		c.RunHistoryEnabled = src.RunHistoryEnabled
	case "run_history_limit":
		c.RunHistoryLimit = src.RunHistoryLimit
	case "trace":
		c.Trace = src.Trace
	}
}
func (c Config) valueOf(field string) any {
	switch field {
	case "default_destination":
		return c.DefaultDestination
	case "verify_after_operation":
		return c.VerifyAfterOperation
	case "compression_algorithm":
		return c.CompressionAlgorithm
	case "compression_level":
		return c.CompressionLevel
	case "fresh_restore_dir":
		return c.FreshRestoreDir
	case "preferred_archiver":
		return c.PreferredArchiver
	case "preferred_compressor":
		return c.PreferredCompressor
	case "preferred_hasher":
		return c.PreferredHasher
	case "preferred_transfer":
		return c.PreferredTransfer
	case "run_history_enabled":
		return c.RunHistoryEnabled
	case "run_history_limit":
		return c.RunHistoryLimit
	case "trace":
		return c.Trace
	}
	return nil
}
