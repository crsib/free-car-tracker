// Command fct-viewer serves a read-only web UI over a Car Tracker WebDAV sync folder.
//
// The browser cannot talk to WebDAV directly - PROPFIND triggers a CORS preflight that
// Nextcloud and friends do not answer - so this process does the WebDAV half server-side,
// caches what it downloads, and serves a plain same-origin JSON API to the page. That also
// makes it deployable: run it on your desktop and it is a local app; run it on a box with
// -addr and -auth and the same binary is a web app you can reach from your phone.
//
// It never writes to the WebDAV server.
package main

import (
	"context"
	"embed"
	"errors"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"

	"crsib.me/fct-viewer/internal/api"
	"crsib.me/fct-viewer/internal/config"
	"crsib.me/fct-viewer/internal/store"
	"crsib.me/fct-viewer/internal/webdav"
)

//go:embed all:web
var webFS embed.FS

// Build identity. buildVersion/buildCommit are set via -ldflags -X by scripts/build.*;
// a plain `go build` leaves the defaults, which is how you tell a dev binary apart from a
// released one in /api/status.
var (
	buildVersion = "dev"
	buildCommit  = "none"
)

func versionString() string {
	if buildCommit == "none" {
		return buildVersion
	}
	return buildVersion + " (" + buildCommit + ")"
}

func main() {
	var (
		addr      = flag.String("addr", "127.0.0.1:8420", "listen address; use 0.0.0.0:8420 to expose on the LAN")
		cfgPath   = flag.String("config", config.DefaultPath(), "path to the config file")
		cacheDir  = flag.String("cache", "", "cache directory (default: OS cache dir)")
		url       = flag.String("url", "", "WebDAV directory URL (overrides the config file)")
		user      = flag.String("user", "", "WebDAV username (overrides the config file)")
		password  = flag.String("password", "", "WebDAV password; prefer FCT_WEBDAV_PASSWORD or the saved config")
		insecure  = flag.Bool("insecure", false, "skip TLS verification (self-signed certificates only)")
		auth      = flag.String("auth", "", "require HTTP Basic auth as user:pass (use when not bound to localhost)")
		allowFrom = flag.String("allow-from", "", "comma-separated IPs/CIDRs allowed to connect; empty allows everyone")
		cacheMax  = flag.String("cache-max", "", "cap the payload cache, e.g. 2GB or 800MB; empty means no limit")
		rps       = flag.Float64("webdav-rps", 0, "cap WebDAV requests per second (default 3; hosted providers throttle hard)")
		retries   = flag.Int("webdav-retries", 0, "retries for a throttled (429/503) request (default 5)")
		conc      = flag.Int("webdav-concurrency", 0, "WebDAV requests in flight at once (default 2)")
		open      = flag.Bool("open", true, "open a browser window on start (loopback listeners only)")
		refresh   = flag.Bool("refresh", true, "refresh the index from the server on start")
		autoEvery = flag.Int("auto-refresh", -1, "minutes between automatic re-indexes; 0 turns them off (default 15)")
		showVer   = flag.Bool("version", false, "print the version and exit")
	)
	flag.Parse()

	if *showVer {
		fmt.Println("fct-viewer", versionString())
		return
	}

	log.SetFlags(log.Ltime)

	cfg, err := config.Load(*cfgPath)
	if err != nil {
		log.Fatalf("config: %v", err)
	}
	cfg = config.ApplyEnv(cfg)
	if *url != "" {
		cfg.URL = *url
	}
	if *user != "" {
		cfg.Username = *user
	}
	if *password != "" {
		cfg.Password = *password
	}
	if *cacheDir != "" {
		cfg.CacheDir = *cacheDir
	}
	if *insecure {
		cfg.Insecure = true
	}
	if *rps > 0 {
		cfg.RequestsPerSecond = *rps
	}
	if *retries > 0 {
		cfg.MaxRetries = *retries
	}
	if *conc > 0 {
		cfg.Concurrency = *conc
	}
	if *autoEvery >= 0 {
		cfg.SetAutoRefreshMinutes(*autoEvery)
	}

	maxBytes, err := parseSize(*cacheMax)
	if err != nil {
		log.Fatalf("-cache-max: %v", err)
	}
	cache, err := store.NewCache(cfg.ResolveCacheDir(), maxBytes)
	if err != nil {
		log.Fatalf("cache: %v", err)
	}

	var client *webdav.Client
	if cfg.Configured() {
		client = webdav.New(api.ClientConfig(cfg), api.Transport(cfg.Insecure))
	}

	index := store.NewIndex(cache, client)
	index.SetConcurrency(cfg.EffectiveConcurrency())
	// Show the last known catalog immediately; the refresh below then updates it in place,
	// so starting the viewer offline still gives you every trip you have already seen.
	if err := index.LoadPersisted(); err != nil {
		log.Printf("could not read the saved index: %v", err)
	}

	// Automatic updates run on the server rather than in the page: the browser tab is only
	// the trigger, so a second tab does not mean a second walk, and a refresh started before
	// you navigated away still finishes.
	sched := store.NewScheduler(index, time.Duration(cfg.EffectiveAutoRefreshMinutes())*time.Minute)
	schedCtx, stopSched := context.WithCancel(context.Background())
	defer stopSched()
	go sched.Run(schedCtx)

	authUser, authPass := splitAuth(*auth)
	allow, err := api.ParseAllowList(*allowFrom)
	if err != nil {
		log.Fatal(err)
	}
	// Either fence is enough on its own: an SSO reverse proxy in front (peer-IP allowlist) or
	// Basic auth. Having neither on a non-loopback listener hands the data - and, by proxy,
	// the WebDAV credentials - to anything that can route to the port.
	if authUser == "" && allow == nil && !isLoopback(*addr) {
		log.Printf("WARNING: listening on %s with neither -auth nor -allow-from. Anyone who can reach "+
			"this port can read your car data through it.", *addr)
	}

	srv := api.NewServer(api.Options{
		Config: cfg, ConfigPath: *cfgPath, Index: index, Cache: cache, Scheduler: sched,
		AuthUser: authUser, AuthPass: authPass, Allow: allow, Version: versionString(),
	})

	ui, err := fs.Sub(webFS, "web")
	if err != nil {
		log.Fatalf("embedded ui: %v", err)
	}
	handler := srv.Routes(spaHandler(ui))

	httpSrv := &http.Server{
		Addr:              *addr,
		Handler:           handler,
		ReadHeaderTimeout: 15 * time.Second,
		// No WriteTimeout: a zip export of several hundred trips legitimately streams for
		// minutes, and a deadline here would truncate it mid-archive.
	}

	ln, err := net.Listen("tcp", *addr)
	if err != nil {
		log.Fatalf("listen on %s: %v", *addr, err)
	}

	shown := displayURL(ln.Addr().String(), *addr)
	if cfg.Configured() {
		log.Printf("Car Tracker viewer on %s  (WebDAV %s)", shown, cfg.URL)
		if *refresh {
			index.Refresh()
		}
	} else {
		log.Printf("Car Tracker viewer on %s  (not configured yet - set up your WebDAV server in the UI)", shown)
	}
	if maxBytes > 0 {
		log.Printf("cache: %s (capped at %s, least-recently-used evicted)", cfg.ResolveCacheDir(), *cacheMax)
	} else {
		log.Printf("cache: %s (uncapped)", cfg.ResolveCacheDir())
	}
	if allow != nil {
		log.Printf("allowing connections from: %s", allow)
	}
	log.Printf("webdav pacing: %.3g req/s, %d retries on 429, %d in flight",
		cfg.EffectiveRPS(), cfg.EffectiveRetries(), cfg.EffectiveConcurrency())
	if n := cfg.EffectiveAutoRefreshMinutes(); n > 0 {
		log.Printf("auto-refresh: every %d min while the UI is open", n)
	} else {
		log.Printf("auto-refresh: off (refresh from the UI or set -auto-refresh)")
	}

	if *open && isLoopback(*addr) {
		go openBrowser(shown)
	}

	// Serve until interrupted, then let in-flight downloads finish before exiting.
	errCh := make(chan error, 1)
	go func() { errCh <- httpSrv.Serve(ln) }()

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	select {
	case err := <-errCh:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("serve: %v", err)
		}
	case <-sig:
		log.Println("shutting down")
		stopSched()
		index.Cancel()
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = httpSrv.Shutdown(ctx)
	}
}

// spaHandler serves the embedded UI, falling back to index.html so a deep link the user
// bookmarked still loads the app rather than a 404.
func spaHandler(ui fs.FS) http.Handler {
	files := http.FileServer(http.FS(ui))
	assetETag := `"` + buildVersion + "-" + buildCommit + `"`
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path := strings.TrimPrefix(r.URL.Path, "/")
		if path == "" {
			path = "index.html"
		}
		if _, err := fs.Stat(ui, path); err != nil {
			r = r.Clone(r.Context())
			r.URL.Path = "/"
		}
		// The UI ships inside the binary, so "stale" means the browser is running a different
		// version of the app than the server it is talking to. Embedded files carry no
		// modification time, so without an explicit validator "no-cache" degrades to either a
		// full refetch or, worse, a memory-cache hit that survives an upgrade. Stamping the
		// build as the ETag makes revalidation exact: 304 while the build is unchanged, and a
		// guaranteed refetch the moment it is not.
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("ETag", assetETag)
		if match := r.Header.Get("If-None-Match"); match == assetETag {
			w.WriteHeader(http.StatusNotModified)
			return
		}
		// The UI is fully self-contained; the only outbound requests it may make are map
		// tiles, which this policy allows as images while blocking everything else.
		w.Header().Set("Content-Security-Policy",
			"default-src 'self'; img-src 'self' data: https:; style-src 'self' 'unsafe-inline'; "+
				"script-src 'self'; connect-src 'self'; frame-ancestors 'none'")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		files.ServeHTTP(w, r)
	})
}

func splitAuth(v string) (user, pass string) {
	if v == "" {
		return "", ""
	}
	user, pass, found := strings.Cut(v, ":")
	if !found {
		log.Fatal("-auth must look like user:pass")
	}
	return user, pass
}

func isLoopback(addr string) bool {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return false
	}
	if host == "" || host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// displayURL turns a listen address into something clickable, replacing a wildcard bind with
// localhost so the printed link actually works on the machine running it.
func displayURL(resolved, requested string) string {
	host, port, err := net.SplitHostPort(resolved)
	if err != nil {
		return "http://" + requested
	}
	if host == "" || host == "0.0.0.0" || host == "::" || host == "[::]" {
		host = "localhost"
	}
	if strings.Contains(host, ":") {
		host = "[" + host + "]"
	}
	return fmt.Sprintf("http://%s:%s", host, port)
}

func openBrowser(url string) {
	// Give the listener a moment so the first page load does not race the server.
	time.Sleep(300 * time.Millisecond)
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	if err := cmd.Start(); err != nil {
		log.Printf("could not open a browser (%v) - go to %s", err, url)
	}
}

// parseSize reads a human byte size like "2GB", "800MB" or a plain byte count. Empty means
// no limit. Sizes are binary (1 GB = 1024 MB), which is what df reports.
func parseSize(s string) (int64, error) {
	s = strings.TrimSpace(strings.ToUpper(s))
	if s == "" {
		return 0, nil
	}
	mult := int64(1)
	switch {
	case strings.HasSuffix(s, "TB"), strings.HasSuffix(s, "T"):
		mult, s = 1<<40, strings.TrimSuffix(strings.TrimSuffix(s, "TB"), "T")
	case strings.HasSuffix(s, "GB"), strings.HasSuffix(s, "G"):
		mult, s = 1<<30, strings.TrimSuffix(strings.TrimSuffix(s, "GB"), "G")
	case strings.HasSuffix(s, "MB"), strings.HasSuffix(s, "M"):
		mult, s = 1<<20, strings.TrimSuffix(strings.TrimSuffix(s, "MB"), "M")
	case strings.HasSuffix(s, "KB"), strings.HasSuffix(s, "K"):
		mult, s = 1<<10, strings.TrimSuffix(strings.TrimSuffix(s, "KB"), "K")
	case strings.HasSuffix(s, "B"):
		s = strings.TrimSuffix(s, "B")
	}
	n, err := strconv.ParseFloat(strings.TrimSpace(s), 64)
	if err != nil {
		return 0, fmt.Errorf("cannot read %q as a size (try 2GB)", s)
	}
	if n < 0 {
		return 0, fmt.Errorf("size cannot be negative")
	}
	return int64(n * float64(mult)), nil
}
