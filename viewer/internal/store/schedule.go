package store

import (
	"context"
	"log"
	"sync"
	"time"
)

// Scheduler re-indexes the catalog on its own, so a drive that finished syncing while the
// page was open shows up without anyone pressing Refresh.
//
// Two things keep it from becoming a background crawler nobody asked for:
//
//   - It is paced off the catalog's own build time, not a fixed ticker, so a manual refresh
//     (or a slow walk) pushes the next automatic one out instead of stacking on top of it.
//   - It only runs while someone is actually looking. The UI polls /api/status every few
//     seconds, so "a browser tab is open" is a fact this process already knows; with no tab
//     open there is nothing to keep fresh, and re-walking a throttling host all night to
//     serve nobody is how a viewer earns a 429 budget it will need later.
type Scheduler struct {
	ix *Index

	mu         sync.Mutex
	interval   time.Duration
	lastActive time.Time
	// lastRun is when a run was last *started*, which is not the same as the catalog's build
	// time: a failed walk leaves the old catalog in place, and without this the scheduler
	// would retry it on every tick.
	lastRun time.Time
}

// tickInterval is how often the scheduler wakes up to decide. It is much shorter than any
// refresh interval on purpose: it is also what makes a page opened after a week of silence
// update within seconds rather than at the next multiple of the interval.
const tickInterval = 20 * time.Second

// idleAfter is how long after the last UI request the viewer considers itself unwatched.
// The UI polls every 15s while idle, and browsers throttle timers in background tabs to
// roughly once a minute, so this has to be comfortably above a minute to avoid treating a
// backgrounded tab as a closed one.
const idleAfter = 5 * time.Minute

func NewScheduler(ix *Index, interval time.Duration) *Scheduler {
	return &Scheduler{ix: ix, interval: interval, lastActive: time.Now()}
}

// SetInterval changes the cadence; zero or less turns automatic updates off.
func (s *Scheduler) SetInterval(d time.Duration) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if d < 0 {
		d = 0
	}
	s.interval = d
}

func (s *Scheduler) Interval() time.Duration {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.interval
}

// MarkActive records that a client is watching. Every API request counts, which in practice
// means the UI's own status poll.
func (s *Scheduler) MarkActive(now time.Time) {
	s.mu.Lock()
	if now.After(s.lastActive) {
		s.lastActive = now
	}
	s.mu.Unlock()
}

// AutoState is what /api/status reports about automatic updates.
type AutoState struct {
	Enabled bool    `json:"enabled"`
	Minutes float64 `json:"minutes"`
	// NextAt is a pointer so "no next run" is null rather than the year 1: a zero time.Time
	// is not empty as far as omitempty is concerned, and the UI would read it as a date.
	NextAt *time.Time `json:"nextAt"`
	// Idle says no client has called in recently, so the next tick will pass rather than
	// index. The UI says so out loud; otherwise "next update in -3 min" looks like a bug.
	Idle bool `json:"idle"`
}

func (s *Scheduler) State(now time.Time) AutoState {
	s.mu.Lock()
	interval, lastActive := s.interval, s.lastActive
	s.mu.Unlock()
	st := AutoState{
		Enabled: interval > 0,
		Minutes: interval.Minutes(),
		Idle:    now.Sub(lastActive) > idleAfter,
	}
	if st.Enabled {
		if next := s.nextAt(interval); !next.IsZero() {
			st.NextAt = &next
		}
	}
	return st
}

// nextAt is when the next automatic run becomes due: one interval after whichever came
// later, the last completed build or the last attempt.
func (s *Scheduler) nextAt(interval time.Duration) time.Time {
	s.mu.Lock()
	last := s.lastRun
	s.mu.Unlock()
	if cat := s.ix.Catalog(); cat != nil && cat.BuiltAt.After(last) {
		last = cat.BuiltAt
	}
	if last.IsZero() {
		// Nothing has ever been indexed: due as soon as there is a server to ask.
		return time.Time{}
	}
	return last.Add(interval)
}

// Tick runs one decision. It reports whether a refresh was started, which is what the tests
// assert on.
func (s *Scheduler) Tick(now time.Time) bool {
	s.mu.Lock()
	interval, lastActive := s.interval, s.lastActive
	s.mu.Unlock()

	switch {
	case interval <= 0:
		return false
	case s.ix.Client() == nil: // not configured yet
		return false
	case now.Sub(lastActive) > idleAfter:
		return false
	case s.ix.Progress().Running:
		return false // a manual run, or a previous automatic one still going
	case now.Before(s.nextAt(interval)):
		return false
	}

	// Claim the slot before starting: Refresh is asynchronous, so without this the next tick
	// could arrive before Running goes true and fire a second walk.
	s.mu.Lock()
	s.lastRun = now
	s.mu.Unlock()
	if !s.ix.Refresh() {
		return false
	}
	log.Printf("auto-refresh: re-reading the WebDAV folder (every %s)", interval)
	return true
}

// Run ticks until the context is cancelled.
func (s *Scheduler) Run(ctx context.Context) {
	t := time.NewTicker(tickInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-t.C:
			s.Tick(now)
		}
	}
}
