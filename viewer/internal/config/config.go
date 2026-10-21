// Package config resolves where the viewer looks for its sync folder (a WebDAV server or an
// S3 bucket) and its cache.
// Precedence is flags > environment > config file, so a one-off run can point at a second
// server without disturbing the saved setup.
package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
)

// Config is the persisted setup. The password is stored in plaintext in a 0600 file, the
// same trade-off every desktop sync client makes: use a WebDAV app password (Nextcloud:
// Settings > Security > Create new app password), never your account password, so revoking
// it costs nothing.
type Config struct {
	// Backend selects where the sync folder lives: BackendWebDAV (the default, and what a
	// config written before S3 support means) or BackendS3.
	Backend string `json:"backend,omitempty"`
	// S3 is the bucket, used when Backend is BackendS3. The secret key is stored like the
	// WebDAV password: give the viewer a read-only key scoped to this one bucket.
	S3 S3 `json:"s3,omitempty"`

	URL      string `json:"url"`
	Username string `json:"username"`
	Password string `json:"password"`
	Insecure bool   `json:"insecure,omitempty"`
	// CacheDir holds downloaded WebDAV payloads. Empty means the OS default location.
	CacheDir string `json:"cacheDir,omitempty"`

	// RequestsPerSecond caps the outbound WebDAV request rate. Hosted providers throttle:
	// Yandex.Disk answers a burst of PROPFINDs with 429 and drops them, which shows up as
	// trips silently missing from the index. Zero means "use the default".
	RequestsPerSecond float64 `json:"requestsPerSecond,omitempty"`
	// MaxRetries is how many times a throttled request is retried before giving up.
	MaxRetries int `json:"maxRetries,omitempty"`
	// Concurrency is how many WebDAV requests may be in flight at once.
	Concurrency int `json:"concurrency,omitempty"`

	// AutoRefreshMinutes is how often the viewer re-reads the WebDAV folder by itself.
	// It is a pointer because zero is a meaningful value here - it means "never, only when
	// I press Refresh" - and has to be distinguishable from a config written before this
	// setting existed, which should get the default.
	AutoRefreshMinutes *int `json:"autoRefreshMinutes,omitempty"`
}

// S3 is an S3 or S3-compatible bucket holding the sync layout under Prefix.
type S3 struct {
	// Endpoint is the service URL; empty means AWS for Region.
	Endpoint  string `json:"endpoint,omitempty"`
	Region    string `json:"region,omitempty"`
	Bucket    string `json:"bucket,omitempty"`
	Prefix    string `json:"prefix,omitempty"`
	AccessKey string `json:"accessKey,omitempty"`
	SecretKey string `json:"secretKey,omitempty"`
	// VirtualHost addresses bucket.host instead of host/bucket. Off by default: path style
	// works everywhere and needs no wildcard DNS.
	VirtualHost bool `json:"virtualHost,omitempty"`
}

const (
	BackendWebDAV = "webdav"
	BackendS3     = "s3"
)

// EffectiveBackend is Backend, defaulting to WebDAV.
func (c Config) EffectiveBackend() string {
	if strings.EqualFold(strings.TrimSpace(c.Backend), BackendS3) {
		return BackendS3
	}
	return BackendWebDAV
}

// Location names the configured folder for logs and messages.
func (c Config) Location() string {
	if c.EffectiveBackend() == BackendS3 {
		loc := "s3://" + c.S3.Bucket
		if p := strings.Trim(c.S3.Prefix, "/"); p != "" {
			loc += "/" + p
		}
		if c.S3.Endpoint != "" {
			loc += " at " + c.S3.Endpoint
		}
		return loc
	}
	return c.URL
}

// Conservative defaults, chosen for a hosted provider rather than a home server: it is far
// better for a first index to take a minute than to lose trips to throttling.
const (
	DefaultRequestsPerSecond = 3.0
	DefaultMaxRetries        = 5
	DefaultConcurrency       = 2
	// DefaultAutoRefreshMinutes picks up new drives without anyone pressing anything. It is
	// cheap: an incremental walk only re-lists directories, and trip payloads already on
	// disk are not downloaded again.
	DefaultAutoRefreshMinutes = 15
	// MinAutoRefreshMinutes floors the interval. Anything faster is a directory walk that
	// has not finished before the next one starts, against a host that throttles.
	MinAutoRefreshMinutes = 1
)

func (c Config) EffectiveRPS() float64 {
	if c.RequestsPerSecond > 0 {
		return c.RequestsPerSecond
	}
	return DefaultRequestsPerSecond
}

func (c Config) EffectiveRetries() int {
	if c.MaxRetries > 0 {
		return c.MaxRetries
	}
	return DefaultMaxRetries
}

func (c Config) EffectiveConcurrency() int {
	if c.Concurrency > 0 {
		return c.Concurrency
	}
	return DefaultConcurrency
}

// EffectiveAutoRefreshMinutes returns the automatic re-index interval in minutes, 0 when
// automatic updates are off.
func (c Config) EffectiveAutoRefreshMinutes() int {
	if c.AutoRefreshMinutes == nil {
		return DefaultAutoRefreshMinutes
	}
	n := *c.AutoRefreshMinutes
	if n <= 0 {
		return 0
	}
	if n < MinAutoRefreshMinutes {
		return MinAutoRefreshMinutes
	}
	return n
}

// SetAutoRefreshMinutes records an explicit interval, including 0 for "off".
func (c *Config) SetAutoRefreshMinutes(n int) {
	if n < 0 {
		n = 0
	}
	c.AutoRefreshMinutes = &n
}

func (c Config) Configured() bool {
	if c.EffectiveBackend() == BackendS3 {
		return strings.TrimSpace(c.S3.Bucket) != "" &&
			strings.TrimSpace(c.S3.AccessKey) != "" && c.S3.SecretKey != ""
	}
	return strings.TrimSpace(c.URL) != "" && strings.TrimSpace(c.Username) != ""
}

// DefaultDir is the per-user config directory: %APPDATA%\fct-viewer on Windows,
// ~/.config/fct-viewer elsewhere.
func DefaultDir() string {
	if dir, err := os.UserConfigDir(); err == nil {
		return filepath.Join(dir, "fct-viewer")
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".fct-viewer")
}

func DefaultPath() string { return filepath.Join(DefaultDir(), "config.json") }

// DefaultCacheDir is where downloaded trip payloads land when none is configured.
func DefaultCacheDir() string {
	if dir, err := os.UserCacheDir(); err == nil {
		return filepath.Join(dir, "fct-viewer")
	}
	return filepath.Join(DefaultDir(), "cache")
}

// Load reads a config file. A missing file is not an error - it means "not set up yet",
// which the UI handles with its setup screen.
func Load(path string) (Config, error) {
	var c Config
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return c, nil
	}
	if err != nil {
		return c, err
	}
	if err := json.Unmarshal(b, &c); err != nil {
		return c, fmt.Errorf("parse %s: %w", path, err)
	}
	return c, nil
}

// Save writes the config with owner-only permissions, creating the directory if needed.
func Save(path string, c Config) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	// Write to a temp file and rename, so an interrupted save cannot leave a half-written
	// config that would lock the user out of their own setup on next start.
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		return err
	}
	// Rename preserves the temp file mode on POSIX; on Windows ACLs govern instead and the
	// user profile directory is already private, so this is a no-op there.
	if runtime.GOOS != "windows" {
		_ = os.Chmod(path, 0o600)
	}
	return nil
}

// ApplyEnv overlays FCT_WEBDAV_* / FCT_S3_* environment variables onto a config. Useful for Docker,
// where baking credentials into an image or a bind-mounted file is worse than an env var
// supplied by the orchestrator.
func ApplyEnv(c Config) Config {
	if v := os.Getenv("FCT_WEBDAV_URL"); v != "" {
		c.URL = v
	}
	if v := os.Getenv("FCT_WEBDAV_USERNAME"); v != "" {
		c.Username = v
	}
	if v := os.Getenv("FCT_WEBDAV_PASSWORD"); v != "" {
		c.Password = v
	}
	if v := os.Getenv("FCT_BACKEND"); v != "" {
		c.Backend = strings.ToLower(strings.TrimSpace(v))
	}
	for env, dst := range map[string]*string{
		"FCT_S3_ENDPOINT":   &c.S3.Endpoint,
		"FCT_S3_REGION":     &c.S3.Region,
		"FCT_S3_BUCKET":     &c.S3.Bucket,
		"FCT_S3_PREFIX":     &c.S3.Prefix,
		"FCT_S3_ACCESS_KEY": &c.S3.AccessKey,
		"FCT_S3_SECRET_KEY": &c.S3.SecretKey,
	} {
		if v := os.Getenv(env); v != "" {
			*dst = v
		}
	}
	if os.Getenv("FCT_S3_VIRTUAL_HOST") == "1" {
		c.S3.VirtualHost = true
	}
	if v := os.Getenv("FCT_CACHE_DIR"); v != "" {
		c.CacheDir = v
	}
	if os.Getenv("FCT_WEBDAV_INSECURE") == "1" {
		c.Insecure = true
	}
	if v := os.Getenv("FCT_AUTO_REFRESH_MINUTES"); v != "" {
		if n, err := strconv.Atoi(strings.TrimSpace(v)); err == nil {
			c.SetAutoRefreshMinutes(n)
		}
	}
	return c
}

// ResolveCacheDir returns the effective cache directory.
func (c Config) ResolveCacheDir() string {
	if strings.TrimSpace(c.CacheDir) != "" {
		return c.CacheDir
	}
	return DefaultCacheDir()
}

// Redacted is the config as the UI may see it: everything except the password and the S3
// secret key, which never leave the process once saved.
func (c Config) Redacted() map[string]any {
	return map[string]any{
		"backend": c.EffectiveBackend(),
		"s3": map[string]any{
			"endpoint":    c.S3.Endpoint,
			"region":      c.S3.Region,
			"bucket":      c.S3.Bucket,
			"prefix":      c.S3.Prefix,
			"accessKey":   c.S3.AccessKey,
			"virtualHost": c.S3.VirtualHost,
			"hasSecret":   c.S3.SecretKey != "",
		},
		"url":         c.URL,
		"username":    c.Username,
		"insecure":    c.Insecure,
		"cacheDir":    c.ResolveCacheDir(),
		"hasPassword": c.Password != "",
		"configured":  c.Configured(),
		"rps":         c.EffectiveRPS(),
		"maxRetries":  c.EffectiveRetries(),
		"concurrency": c.EffectiveConcurrency(),
		// Minutes, 0 meaning "only when asked". The UI shows this as a picker rather than a
		// free number, so it is fine to hand it over as-is.
		"autoRefreshMinutes": c.EffectiveAutoRefreshMinutes(),
	}
}
