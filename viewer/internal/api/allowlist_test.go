package api

import (
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestParseAllowList(t *testing.T) {
	a, err := ParseAllowList("127.0.0.1, ::1 ,192.0.2.22,10.0.0.0/8")
	if err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		ip   string
		want bool
	}{
		{"127.0.0.1", true},
		{"::1", true},
		{"192.0.2.22", true},
		{"10.4.5.6", true},      // inside the CIDR
		{"192.0.2.21", false}, // the server host is deliberately not allowed
		{"11.0.0.1", false},
	}
	for _, tc := range cases {
		if got := a.Allows(net.ParseIP(tc.ip)); got != tc.want {
			t.Errorf("Allows(%s) = %v, want %v", tc.ip, got, tc.want)
		}
	}
}

// An empty spec must mean "no restriction" rather than "deny everything", so the default
// desktop run (loopback, no flag) is not silently broken by the fence.
func TestEmptyAllowListPermitsEveryone(t *testing.T) {
	a, err := ParseAllowList("   ")
	if err != nil {
		t.Fatal(err)
	}
	if a != nil {
		t.Fatal("an empty spec should produce no allow list")
	}
	if !a.Allows(net.ParseIP("8.8.8.8")) {
		t.Error("a nil allow list must permit everyone")
	}
}

func TestParseAllowListRejectsGarbage(t *testing.T) {
	for _, spec := range []string{"not-an-ip", "192.0.2.0/99", "192.0.2.999"} {
		if _, err := ParseAllowList(spec); err == nil {
			t.Errorf("%q should be rejected rather than silently ignored", spec)
		}
	}
}

func allowHandler(t *testing.T, spec string) http.Handler {
	t.Helper()
	a, err := ParseAllowList(spec)
	if err != nil {
		t.Fatal(err)
	}
	s := &Server{allow: a}
	return s.withAllowList(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("reached"))
	}))
}

func TestAllowListBlocksDisallowedPeer(t *testing.T) {
	h := allowHandler(t, "192.0.2.22")

	req := httptest.NewRequest(http.MethodGet, "/api/status", nil)
	req.RemoteAddr = "192.0.2.21:5555"
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Errorf("disallowed peer got %d, want 403", rec.Code)
	}

	req = httptest.NewRequest(http.MethodGet, "/api/status", nil)
	req.RemoteAddr = "192.0.2.22:5555"
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Errorf("allowed peer got %d, want 200", rec.Code)
	}
}

// The whole point of filtering on the socket peer is that it cannot be spoofed. X-Forwarded-For
// is set by whoever is talking to us, so honouring it would let any LAN host walk straight
// through the fence by claiming to be the reverse proxy.
func TestAllowListIgnoresForwardedForHeaders(t *testing.T) {
	h := allowHandler(t, "192.0.2.22")
	req := httptest.NewRequest(http.MethodGet, "/api/status", nil)
	req.RemoteAddr = "192.0.2.99:5555"
	req.Header.Set("X-Forwarded-For", "192.0.2.22")
	req.Header.Set("X-Real-IP", "192.0.2.22")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Errorf("a forged X-Forwarded-For got %d, want 403", rec.Code)
	}
}

// A v4 peer arriving over a dual-stack listener is reported as ::ffff:a.b.c.d; a plain v4
// entry in the list must still match it, or the fence would reject the proxy on some hosts.
func TestAllowListMatchesV4MappedPeers(t *testing.T) {
	h := allowHandler(t, "192.0.2.22")
	req := httptest.NewRequest(http.MethodGet, "/api/status", nil)
	req.RemoteAddr = "[::ffff:192.0.2.22]:5555"
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Errorf("v4-mapped peer got %d, want 200", rec.Code)
	}
}
