package webdav

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

func TestLimiterPacesRequests(t *testing.T) {
	l := NewLimiter(50) // 20ms apart
	ctx := context.Background()
	start := time.Now()
	for i := 0; i < 5; i++ {
		if err := l.Wait(ctx); err != nil {
			t.Fatal(err)
		}
	}
	// Five requests at 20ms spacing: the first is immediate, so ~80ms of enforced delay.
	if elapsed := time.Since(start); elapsed < 60*time.Millisecond {
		t.Errorf("5 requests took %v, want at least ~80ms of pacing", elapsed)
	}
}

// The cap has to hold across goroutines, or raising concurrency would defeat it entirely —
// which is exactly how the index managed to trigger 429s in the first place.
func TestLimiterCapsRateAcrossGoroutines(t *testing.T) {
	l := NewLimiter(100) // 10ms apart
	ctx := context.Background()
	start := time.Now()
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_ = l.Wait(ctx)
		}()
	}
	wg.Wait()
	if elapsed := time.Since(start); elapsed < 50*time.Millisecond {
		t.Errorf("8 concurrent requests took %v; the limiter is not serialising them", elapsed)
	}
}

func TestNilLimiterIsANoOp(t *testing.T) {
	var l *Limiter
	if err := l.Wait(context.Background()); err != nil {
		t.Fatal(err)
	}
	l.Throttled(time.Second) // must not panic
	if n, _ := l.Stats(); n != 0 {
		t.Errorf("nil limiter reported %d throttles", n)
	}
}

func TestLimiterWaitHonoursContext(t *testing.T) {
	l := NewLimiter(1) // one per second
	ctx := context.Background()
	_ = l.Wait(ctx) // consume the immediate slot

	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	if err := l.Wait(cancelled); !errors.Is(err, context.Canceled) {
		t.Errorf("Wait returned %v, want context.Canceled", err)
	}
}

func TestThrottledWidensTheGap(t *testing.T) {
	l := NewLimiter(1000)
	before := l.interval
	l.Throttled(0)
	if l.penalty <= 0 {
		t.Fatal("a 429 should add a penalty")
	}
	first := l.penalty
	l.Throttled(0)
	if l.penalty <= first {
		t.Error("repeated 429s should back off further")
	}
	if l.interval != before {
		t.Error("the base interval should not be mutated; the penalty is separate and decays")
	}
	if n, _ := l.Stats(); n != 2 {
		t.Errorf("throttle count = %d, want 2", n)
	}
}

// A single 429 must not hold the whole walk at the slow rate: as requests start succeeding the
// penalty has to shed, or one transient throttle costs minutes across hundreds of trips.
func TestPenaltyRecoversOnSuccess(t *testing.T) {
	l := NewLimiter(1000)
	l.Throttled(0)
	l.Throttled(0)
	peak := l.penalty
	if peak <= 0 {
		t.Fatal("expected a penalty after throttling")
	}
	for i := 0; i < 40; i++ {
		l.Succeeded()
	}
	if l.penalty >= peak {
		t.Errorf("penalty %v did not shrink from %v after sustained success", l.penalty, peak)
	}
	if l.penalty != 0 {
		t.Errorf("penalty should reach zero after enough successes, got %v", l.penalty)
	}
}

func TestRetryAfterParsing(t *testing.T) {
	mk := func(v string) *http.Response {
		return &http.Response{Header: http.Header{"Retry-After": []string{v}}}
	}
	if got, ok := retryAfter(mk("3")); got != 3*time.Second || !ok {
		t.Errorf("seconds form = %v/%v, want 3s/true", got, ok)
	}
	// "0" is an instruction to retry immediately, which must be distinguishable from the
	// server saying nothing at all - otherwise we would back off when told not to.
	if got, ok := retryAfter(mk("0")); got != 0 || !ok {
		t.Errorf("zero seconds = %v/%v, want 0/true", got, ok)
	}
	if _, ok := retryAfter(mk("not-a-date")); ok {
		t.Error("garbage should not count as an instruction")
	}
	if _, ok := retryAfter(&http.Response{Header: http.Header{}}); ok {
		t.Error("an absent header should not count as an instruction")
	}
	future := time.Now().Add(5 * time.Second).UTC().Format(http.TimeFormat)
	if got, ok := retryAfter(mk(future)); got <= 0 || !ok {
		t.Errorf("HTTP-date form = %v/%v, want a positive delay", got, ok)
	}
	past := time.Now().Add(-time.Hour).UTC().Format(http.TimeFormat)
	if got, ok := retryAfter(mk(past)); got != 0 || !ok {
		t.Errorf("past date = %v/%v, want 0/true", got, ok)
	}
}

// A 429 must be retried rather than surfaced as a failure: treating it as fatal is what made
// 131 trips disappear from the index.
func TestClientRetriesThrottledRequests(t *testing.T) {
	var attempts int
	var mu sync.Mutex
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		attempts++
		n := attempts
		mu.Unlock()
		if n < 3 {
			w.Header().Set("Retry-After", "0")
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		_, _ = w.Write([]byte("payload"))
	}))
	defer srv.Close()

	c := New(Config{URL: srv.URL + "/", MaxRetries: 4}, nil)
	body, err := c.Get(context.Background(), "vehicle.json")
	if err != nil {
		t.Fatalf("expected the retry to succeed, got %v", err)
	}
	if string(body) != "payload" {
		t.Errorf("body = %q", body)
	}
	if attempts != 3 {
		t.Errorf("server saw %d attempts, want 3", attempts)
	}
	if n, _ := c.Limiter().Stats(); n != 2 {
		t.Errorf("limiter recorded %d throttles, want 2", n)
	}
}

// When the budget runs out the error must say "rate limited", not look like a missing file.
func TestClientReportsExhaustedRetriesAsThrottled(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Retry-After", "0")
		w.WriteHeader(http.StatusTooManyRequests)
	}))
	defer srv.Close()

	c := New(Config{URL: srv.URL + "/", MaxRetries: 1}, nil)
	_, err := c.Get(context.Background(), "vehicle.json")
	if !errors.Is(err, ErrThrottled) {
		t.Errorf("err = %v, want it to wrap ErrThrottled", err)
	}
	if errors.Is(err, ErrNotFound) {
		t.Error("a throttle must never be mistaken for a missing resource")
	}
}

func TestClientSendsIdentifyingUserAgent(t *testing.T) {
	var ua string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ua = r.Header.Get("User-Agent")
		_, _ = w.Write([]byte("ok"))
	}))
	defer srv.Close()

	c := New(Config{URL: srv.URL + "/"}, nil)
	if _, err := c.Get(context.Background(), "x"); err != nil {
		t.Fatal(err)
	}
	if ua == "" || ua == "Go-http-client/1.1" {
		t.Errorf("User-Agent = %q; a shared service should be able to tell who is calling", ua)
	}
}
