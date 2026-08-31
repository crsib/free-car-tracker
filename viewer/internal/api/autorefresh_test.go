package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"crsib.me/fct-viewer/internal/config"
	"crsib.me/fct-viewer/internal/store"
)

// newAutoRefreshServer wires just enough of the server to exercise the update controls.
func newAutoRefreshServer(t *testing.T, minutes int) (*Server, *store.Scheduler, string) {
	t.Helper()
	dir := t.TempDir()
	cache, err := store.NewCache(filepath.Join(dir, "cache"), 0)
	if err != nil {
		t.Fatal(err)
	}
	index := store.NewIndex(cache, nil)
	sched := store.NewScheduler(index, time.Duration(minutes)*time.Minute)
	cfgPath := filepath.Join(dir, "config.json")
	cfg := config.Config{URL: "https://dav.example/CarTracker/", Username: "u", Password: "p"}
	cfg.SetAutoRefreshMinutes(minutes)
	srv := NewServer(Options{
		Config: cfg, ConfigPath: cfgPath, Index: index, Cache: cache, Scheduler: sched,
	})
	return srv, sched, cfgPath
}

func postAutoRefresh(t *testing.T, srv *Server, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/auto-refresh", strings.NewReader(body))
	rec := httptest.NewRecorder()
	srv.Routes(http.NotFoundHandler()).ServeHTTP(rec, req)
	return rec
}

func TestAutoRefreshIntervalIsAppliedAndPersisted(t *testing.T) {
	srv, sched, cfgPath := newAutoRefreshServer(t, 15)

	if rec := postAutoRefresh(t, srv, `{"minutes":30}`); rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body %s", rec.Code, rec.Body.String())
	}
	if got := sched.Interval(); got != 30*time.Minute {
		t.Errorf("scheduler interval = %s, want 30m", got)
	}

	// It has to survive a restart, which means it is in the file and not just in memory.
	saved, err := config.Load(cfgPath)
	if err != nil {
		t.Fatal(err)
	}
	if got := saved.EffectiveAutoRefreshMinutes(); got != 30 {
		t.Errorf("saved interval = %d, want 30", got)
	}
	if srv.Config().EffectiveAutoRefreshMinutes() != 30 {
		t.Error("the live config still reports the old interval")
	}
}

func TestAutoRefreshCanBeTurnedOff(t *testing.T) {
	srv, sched, cfgPath := newAutoRefreshServer(t, 15)

	if rec := postAutoRefresh(t, srv, `{"minutes":0}`); rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body %s", rec.Code, rec.Body.String())
	}
	if got := sched.Interval(); got != 0 {
		t.Errorf("scheduler interval = %s, want it off", got)
	}
	// Off is a choice, not an absent setting: a config written without the field would get
	// the default back on the next start.
	b, err := os.ReadFile(cfgPath)
	if err != nil {
		t.Fatal(err)
	}
	var raw map[string]any
	if err := json.Unmarshal(b, &raw); err != nil {
		t.Fatal(err)
	}
	if v, ok := raw["autoRefreshMinutes"]; !ok || v.(float64) != 0 {
		t.Errorf("config has autoRefreshMinutes = %v (present: %v), want an explicit 0", v, ok)
	}
}

func TestAutoRefreshRejectsNegativeIntervals(t *testing.T) {
	srv, sched, _ := newAutoRefreshServer(t, 15)

	rec := postAutoRefresh(t, srv, `{"minutes":-5}`)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", rec.Code)
	}
	if got := sched.Interval(); got != 15*time.Minute {
		t.Errorf("interval = %s, want the previous 15m to stand", got)
	}
}

func TestStatusReportsAutomaticUpdates(t *testing.T) {
	srv, _, _ := newAutoRefreshServer(t, 15)

	req := httptest.NewRequest(http.MethodGet, "/api/status", nil)
	rec := httptest.NewRecorder()
	srv.Routes(http.NotFoundHandler()).ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	var body struct {
		AutoRefresh store.AutoState `json:"autoRefresh"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if !body.AutoRefresh.Enabled || body.AutoRefresh.Minutes != 15 {
		t.Errorf("autoRefresh = %+v, want enabled at 15 minutes", body.AutoRefresh)
	}
	// The request just made counts as someone watching.
	if body.AutoRefresh.Idle {
		t.Error("a viewer that is being polled should not report itself idle")
	}
}
