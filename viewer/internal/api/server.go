// Package api serves the JSON endpoints the browser UI reads, plus the raw-file downloads.
// Everything here is read-only with respect to the WebDAV server: the viewer never issues a
// PUT, MKCOL or DELETE, so pointing it at a live sync folder cannot damage it.
package api

import (
	"crypto/subtle"
	"encoding/json"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"crsib.me/fct-viewer/internal/config"
	"crsib.me/fct-viewer/internal/store"
	"crsib.me/fct-viewer/internal/webdav"
)

// Server wires the index and cache to HTTP handlers.
type Server struct {
	mu      sync.RWMutex
	cfg     config.Config
	cfgPath string

	index *store.Index
	cache *store.Cache

	// auth, when set, gates every request behind HTTP Basic. It exists for the case where
	// the viewer is bound to something other than localhost: the WebDAV credentials live in
	// this process, so an unauthenticated listener on a LAN would hand them out by proxy.
	authUser, authPass string

	// allow, when set, restricts callers by peer IP. Behind an SSO reverse proxy this is the
	// primary fence - the proxy authenticates users, and this makes sure nothing else on the
	// network can simply bypass it by addressing the backend port directly.
	allow *AllowList

	// startedAt and version are surfaced by /api/status so a stale browser tab is obvious.
	startedAt time.Time
	version   string
}

type Options struct {
	Config     config.Config
	ConfigPath string
	Index      *store.Index
	Cache      *store.Cache
	AuthUser   string
	AuthPass   string
	Allow      *AllowList
	Version    string
}

func NewServer(o Options) *Server {
	return &Server{
		cfg: o.Config, cfgPath: o.ConfigPath,
		index: o.Index, cache: o.Cache,
		authUser: o.AuthUser, authPass: o.AuthPass, allow: o.Allow,
		startedAt: time.Now(), version: o.Version,
	}
}

func (s *Server) Config() config.Config {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.cfg
}

// Routes builds the API mux. ui is mounted at the root and handles everything not under /api.
func (s *Server) Routes(ui http.Handler) http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /api/status", s.handleStatus)
	mux.HandleFunc("POST /api/setup", s.handleSetup)
	mux.HandleFunc("POST /api/test", s.handleTest)
	mux.HandleFunc("POST /api/refresh", s.handleRefresh)
	mux.HandleFunc("POST /api/prefetch", s.handlePrefetch)
	mux.HandleFunc("POST /api/cancel", s.handleCancel)
	mux.HandleFunc("POST /api/cache/clear", s.handleCacheClear)

	mux.HandleFunc("GET /api/vehicles", s.handleVehicles)
	mux.HandleFunc("GET /api/vehicles/{vehicle}/dashboard", s.handleDashboard)
	mux.HandleFunc("GET /api/ledger", s.handleLedger)
	mux.HandleFunc("GET /api/ledger.csv", s.handleLedgerCSV)

	mux.HandleFunc("GET /api/trips", s.handleTrips)
	mux.HandleFunc("GET /api/trips/{trip}", s.handleTrip)
	mux.HandleFunc("GET /api/trips/{trip}/track", s.handleTrack)
	mux.HandleFunc("GET /api/trips/{trip}/series", s.handleSeries)
	mux.HandleFunc("GET /api/trips/{trip}/file/{name}", s.handleTripFile)

	mux.HandleFunc("GET /api/analysis/trips", s.handleAnalysisTrips)
	mux.HandleFunc("GET /api/analysis/trips.csv", s.handleAnalysisTripsCSV)
	mux.HandleFunc("GET /api/export/trips.zip", s.handleExportZip)

	mux.Handle("/", ui)
	// Peer-IP filtering runs outermost: a caller that is not allowed should never reach
	// the auth prompt, the logger, or any handler.
	return s.withAllowList(s.withAuth(s.withLogging(mux)))
}

func (s *Server) withAuth(next http.Handler) http.Handler {
	if s.authUser == "" && s.authPass == "" {
		return next
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		user, pass, ok := r.BasicAuth()
		userOK := subtle.ConstantTimeCompare([]byte(user), []byte(s.authUser)) == 1
		passOK := subtle.ConstantTimeCompare([]byte(pass), []byte(s.authPass)) == 1
		if !ok || !userOK || !passOK {
			w.Header().Set("WWW-Authenticate", `Basic realm="car tracker viewer"`)
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}

type statusRecorder struct {
	http.ResponseWriter
	code int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.code = code
	r.ResponseWriter.WriteHeader(code)
}

func (s *Server) withLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rec := &statusRecorder{ResponseWriter: w, code: http.StatusOK}
		start := time.Now()
		next.ServeHTTP(rec, r)
		// Only failures and slow requests are worth a line; a healthy UI polls often enough
		// that logging every 200 would bury anything that actually went wrong.
		if d := time.Since(start); rec.code >= 400 || d > 2*time.Second {
			log.Printf("%s %s -> %d (%s)", r.Method, r.URL.Path, rec.code, d.Round(time.Millisecond))
		}
	})
}

// --- helpers ---

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	// The UI is served from the same origin, and the data is the user's own; still, deny
	// framing and sniffing so a stray page cannot embed or reinterpret these responses.
	w.Header().Set("X-Content-Type-Options", "nosniff")
	enc := json.NewEncoder(w)
	if err := enc.Encode(v); err != nil {
		log.Printf("write json: %v", err)
	}
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

func intParam(r *http.Request, name string, def int) int {
	v := r.URL.Query().Get(name)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}

func int64Param(r *http.Request, name string, def int64) int64 {
	v := r.URL.Query().Get(name)
	if v == "" {
		return def
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil {
		return def
	}
	return n
}

func listParam(r *http.Request, name string) []string {
	raw := r.URL.Query().Get(name)
	if strings.TrimSpace(raw) == "" {
		return nil
	}
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// catalog returns the current index, or writes a 503 explaining that nothing is indexed yet.
func (s *Server) catalog(w http.ResponseWriter) (*store.Catalog, bool) {
	cat := s.index.Catalog()
	if cat == nil {
		writeErr(w, http.StatusServiceUnavailable, "no index yet - run a refresh")
		return nil, false
	}
	return cat, true
}

// --- control endpoints ---

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	cfg := s.Config()
	cat := s.index.Catalog()
	status := map[string]any{
		"version":   s.version,
		"startedAt": s.startedAt,
		"config":    cfg.Redacted(),
		"progress":  s.index.Progress(),
	}
	if cat != nil {
		status["builtAt"] = cat.BuiltAt
		status["vehicleCount"] = len(cat.Vehicles)
		status["tripCount"] = len(cat.Trips)
		status["warnings"] = cat.Warnings
		cached := 0
		for _, t := range cat.Trips {
			if _, ok := s.index.LoadSummary(t); ok {
				cached++
			}
		}
		status["summarisedTripCount"] = cached
	} else {
		status["vehicleCount"] = 0
		status["tripCount"] = 0
	}
	if size, files, err := s.cache.Size(); err == nil {
		status["cacheBytes"] = size
		status["cacheFiles"] = files
	}
	// Surface throttling: a slow index should be explainable, and repeated 429s are the
	// signal that the configured rate is still too high for this provider.
	if c := s.index.Client(); c != nil {
		throttled, waited := c.Limiter().Stats()
		status["throttled"] = throttled
		status["throttleWaitSeconds"] = waited.Seconds()
	}
	writeJSON(w, status)
}

type setupRequest struct {
	URL      string `json:"url"`
	Username string `json:"username"`
	Password string `json:"password"`
	Insecure bool   `json:"insecure"`
	// Pacing. Zero on any of these means "leave the saved value alone", so the connection
	// form does not have to resend them.
	RequestsPerSecond float64 `json:"requestsPerSecond"`
	MaxRetries        int     `json:"maxRetries"`
	Concurrency       int     `json:"concurrency"`
}

// merged applies a setup request onto the current config. An empty password means "keep the
// saved one", so re-saving the URL does not force the user to retype their app password.
func (s *Server) merged(req setupRequest) config.Config {
	cfg := s.Config()
	cfg.URL = strings.TrimSpace(req.URL)
	cfg.Username = strings.TrimSpace(req.Username)
	cfg.Insecure = req.Insecure
	if req.Password != "" {
		cfg.Password = req.Password
	}
	if req.RequestsPerSecond > 0 {
		cfg.RequestsPerSecond = req.RequestsPerSecond
	}
	if req.MaxRetries > 0 {
		cfg.MaxRetries = req.MaxRetries
	}
	if req.Concurrency > 0 {
		cfg.Concurrency = req.Concurrency
	}
	return cfg
}

func (s *Server) handleTest(w http.ResponseWriter, r *http.Request) {
	var req setupRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad request body")
		return
	}
	cfg := s.merged(req)
	if !cfg.Configured() {
		writeErr(w, http.StatusBadRequest, "url and username are required")
		return
	}
	client := webdav.New(webdav.Config{
		URL: cfg.URL, Username: cfg.Username, Password: cfg.Password, Insecure: cfg.Insecure,
	}, Transport(cfg.Insecure))
	if err := client.Ping(r.Context()); err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	writeJSON(w, map[string]any{"ok": true})
}

func (s *Server) handleSetup(w http.ResponseWriter, r *http.Request) {
	var req setupRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad request body")
		return
	}
	cfg := s.merged(req)
	if !cfg.Configured() {
		writeErr(w, http.StatusBadRequest, "url and username are required")
		return
	}
	client := webdav.New(webdav.Config{
		URL: cfg.URL, Username: cfg.Username, Password: cfg.Password, Insecure: cfg.Insecure,
	}, Transport(cfg.Insecure))
	if err := client.Ping(r.Context()); err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	if err := config.Save(s.cfgPath, cfg); err != nil {
		writeErr(w, http.StatusInternalServerError, "could not save config: "+err.Error())
		return
	}
	s.mu.Lock()
	s.cfg = cfg
	s.mu.Unlock()
	s.index.SetClient(client)
	s.index.SetConcurrency(cfg.EffectiveConcurrency())
	s.index.Refresh()
	writeJSON(w, map[string]any{"ok": true, "config": cfg.Redacted()})
}

func (s *Server) handleRefresh(w http.ResponseWriter, r *http.Request) {
	if !s.Config().Configured() {
		writeErr(w, http.StatusBadRequest, "webdav is not configured")
		return
	}
	started := s.index.Refresh()
	writeJSON(w, map[string]any{"started": started, "progress": s.index.Progress()})
}

func (s *Server) handlePrefetch(w http.ResponseWriter, r *http.Request) {
	started := s.index.Prefetch(r.URL.Query().Get("vehicle"))
	writeJSON(w, map[string]any{"started": started, "progress": s.index.Progress()})
}

func (s *Server) handleCancel(w http.ResponseWriter, r *http.Request) {
	s.index.Cancel()
	writeJSON(w, map[string]any{"ok": true})
}

func (s *Server) handleCacheClear(w http.ResponseWriter, r *http.Request) {
	if err := s.cache.Clear(); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, map[string]any{"ok": true})
}
