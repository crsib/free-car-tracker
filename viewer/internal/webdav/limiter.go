package webdav

import (
	"context"
	"math/rand"
	"net/http"
	"strconv"
	"sync"
	"time"
)

// Limiter paces outbound requests to the WebDAV server.
//
// This exists because the server on the other end is not necessarily yours. Yandex.Disk in
// particular answers a burst of PROPFINDs with 429 and simply drops the rest — which showed up
// as an index that quietly listed 26 of 157 trips. A viewer that hammers a shared service until
// it is throttled is broken regardless of how fast it would otherwise be.
//
// The policy is a minimum gap between request starts, so the effective rate is capped no matter
// how many workers are running. A 429 (or 503) adds a penalty that widens that gap for a while,
// and Retry-After is honoured when the server sends one.
type Limiter struct {
	mu       sync.Mutex
	interval time.Duration // minimum gap between request starts
	next     time.Time     // earliest time the next request may start

	// penalty widens the gap after a throttle and shrinks again as requests succeed, so the
	// client converges on whatever rate this provider actually tolerates instead of either
	// hammering it or crawling for a fixed cool-off after a single 429.
	penalty time.Duration

	// Observability: what the UI reports so a slow index is explainable rather than mysterious.
	throttled int
	waited    time.Duration
}

// NewLimiter caps the request rate. rps <= 0 leaves the steady-state rate uncapped, but the
// limiter is still created: it keeps counting throttles (which is diagnostic regardless) and
// still backs off when the server pushes back, because a 429 means slow down whether or not
// anyone configured a rate.
func NewLimiter(rps float64) *Limiter {
	l := &Limiter{}
	if rps > 0 {
		l.interval = time.Duration(float64(time.Second) / rps)
	}
	return l
}

// Wait blocks until this request is allowed to start.
func (l *Limiter) Wait(ctx context.Context) error {
	if l == nil {
		return nil
	}
	l.mu.Lock()
	now := time.Now()
	gap := l.interval + l.penalty
	start := l.next
	if start.Before(now) {
		start = now
	}
	l.next = start.Add(gap)
	l.waited += start.Sub(now)
	l.mu.Unlock()

	delay := time.Until(start)
	if delay <= 0 {
		return nil
	}
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

// maxPenalty bounds how slow a sustained throttle can make us: at worst one request every
// couple of seconds, which still finishes a few-hundred-trip index rather than stalling.
const maxPenalty = 2 * time.Second

// Throttled records a 429/503 and widens the gap between requests (multiplicative decrease).
// cooloff is the server's own Retry-After when it sent one.
func (l *Limiter) Throttled(cooloff time.Duration) {
	if l == nil {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	l.throttled++

	// With no configured rate the base interval is zero, so seed from a floor — doubling zero
	// stays zero and the backoff would never engage.
	seed := l.interval
	if seed <= 0 {
		seed = 100 * time.Millisecond
	}
	if l.penalty == 0 {
		l.penalty = seed
	} else {
		l.penalty = min(l.penalty*2, maxPenalty)
	}
	if cooloff > 0 {
		// Push every queued request past the server's stated cool-off.
		if until := time.Now().Add(cooloff); until.After(l.next) {
			l.next = until
		}
	}
}

// Succeeded records a request the server accepted, easing the penalty back off (additive
// increase). Without this one transient 429 would hold the whole walk at the slow rate.
func (l *Limiter) Succeeded() {
	if l == nil {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.penalty == 0 {
		return
	}
	// Shed a fixed fraction per success, and snap to zero once it stops mattering.
	l.penalty = l.penalty * 3 / 4
	if l.penalty < time.Millisecond {
		l.penalty = 0
	}
}

// Stats reports how often the server pushed back and how long we spent waiting.
func (l *Limiter) Stats() (throttled int, waited time.Duration) {
	if l == nil {
		return 0, 0
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.throttled, l.waited
}

func min(a, b time.Duration) time.Duration {
	if a < b {
		return a
	}
	return b
}

// retryAfter reads the Retry-After header, which may be a delay in seconds or an HTTP date.
// The bool distinguishes "the server told us to wait 0" (retry now) from "the server said
// nothing", which is the difference between retrying immediately and falling back to backoff.
func retryAfter(resp *http.Response) (time.Duration, bool) {
	if resp == nil {
		return 0, false
	}
	v := resp.Header.Get("Retry-After")
	if v == "" {
		return 0, false
	}
	if secs, err := strconv.Atoi(v); err == nil {
		if secs < 0 {
			return 0, true
		}
		return time.Duration(secs) * time.Second, true
	}
	if when, err := http.ParseTime(v); err == nil {
		if d := time.Until(when); d > 0 {
			return d, true
		}
		return 0, true // a date already in the past means "go ahead now"
	}
	return 0, false
}

// backoff is the delay before retry attempt n (0-based), with jitter so a fleet of workers
// that were throttled together do not all come back at the same instant and trigger it again.
func backoff(attempt int, base time.Duration) time.Duration {
	d := base << attempt
	const ceiling = 8 * time.Second
	if d > ceiling {
		d = ceiling
	}
	// +/- 25% jitter.
	jitter := time.Duration(rand.Int63n(int64(d/2)+1)) - d/4
	return d + jitter
}
