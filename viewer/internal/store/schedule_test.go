package store

import (
	"testing"
	"time"
)

// schedulerFixture is a scheduler over an index whose catalog was built at builtAt, with the
// last client request at lastActive.
func schedulerFixture(t *testing.T, interval time.Duration, builtAt, lastActive time.Time) *Scheduler {
	t.Helper()
	ix := newTestIndex(t, &davTree{files: map[string]string{}})
	if !builtAt.IsZero() {
		ix.mu.Lock()
		ix.catalog = &Catalog{BuiltAt: builtAt}
		ix.mu.Unlock()
	}
	s := NewScheduler(ix, interval)
	s.lastActive = lastActive
	// A tick that fires walks the tree in the background; let it finish before the temp cache
	// directory is removed underneath it.
	t.Cleanup(func() {
		deadline := time.Now().Add(10 * time.Second)
		for ix.Progress().Running {
			if time.Now().After(deadline) {
				t.Error("a scheduled refresh never finished")
				return
			}
			time.Sleep(10 * time.Millisecond)
		}
	})
	return s
}

func TestSchedulerFiresOnceTheIntervalHasPassed(t *testing.T) {
	now := time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)
	s := schedulerFixture(t, 15*time.Minute, now.Add(-16*time.Minute), now)

	if !s.Tick(now) {
		t.Fatal("a catalog older than the interval should trigger a refresh")
	}
	// The claim must hold immediately, before the asynchronous walk sets Running: a second
	// tick arriving in that window must not start a second walk.
	if s.Tick(now.Add(time.Second)) {
		t.Error("a second tick started an overlapping refresh")
	}
}

func TestSchedulerWaitsWhileTheCatalogIsFresh(t *testing.T) {
	now := time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)
	s := schedulerFixture(t, 15*time.Minute, now.Add(-5*time.Minute), now)

	if s.Tick(now) {
		t.Error("a catalog younger than the interval should not be rebuilt")
	}
	later := now.Add(11 * time.Minute)
	s.MarkActive(later)
	if !s.Tick(later) {
		t.Error("the refresh should become due one interval after the last build")
	}
}

func TestSchedulerIsOffAtZeroInterval(t *testing.T) {
	now := time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)
	s := schedulerFixture(t, 0, now.Add(-24*time.Hour), now)

	if s.Tick(now) {
		t.Error("automatic updates are off at a zero interval")
	}
	if st := s.State(now); st.Enabled || st.NextAt != nil {
		t.Errorf("state = %+v, want disabled with no next run", st)
	}
}

func TestSchedulerSkipsWhileNobodyIsWatching(t *testing.T) {
	now := time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)
	s := schedulerFixture(t, 15*time.Minute, now.Add(-2*time.Hour), now.Add(-2*time.Hour))

	if s.Tick(now) {
		t.Fatal("nothing should be indexed for a viewer no client has called in hours")
	}
	if st := s.State(now); !st.Idle {
		t.Error("the state should say it is idle, so the UI can explain the pause")
	}

	// A page opens: the very next tick catches up rather than waiting a whole interval.
	s.MarkActive(now)
	if !s.Tick(now) {
		t.Error("a returning client should get a refresh at once")
	}
}

func TestSchedulerIndexesImmediatelyWhenNothingHasBeenIndexed(t *testing.T) {
	now := time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)
	s := schedulerFixture(t, 15*time.Minute, time.Time{}, now)

	if !s.Tick(now) {
		t.Error("an empty catalog is due immediately")
	}
}

func TestSchedulerReportsNextRunFromTheLastBuild(t *testing.T) {
	now := time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)
	built := now.Add(-4 * time.Minute)
	s := schedulerFixture(t, 15*time.Minute, built, now)

	st := s.State(now)
	if !st.Enabled || st.Minutes != 15 {
		t.Fatalf("state = %+v, want enabled at 15 minutes", st)
	}
	if want := built.Add(15 * time.Minute); st.NextAt == nil || !st.NextAt.Equal(want) {
		t.Errorf("nextAt = %s, want %s", st.NextAt, want)
	}
}

func TestSchedulerSetIntervalTurnsItOffAndOn(t *testing.T) {
	now := time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)
	s := schedulerFixture(t, 15*time.Minute, now.Add(-time.Hour), now)

	s.SetInterval(0)
	if s.Tick(now) {
		t.Fatal("turning the interval off should stop the ticks from firing")
	}
	s.SetInterval(30 * time.Minute)
	if !s.Tick(now) {
		t.Error("turning it back on should make an hour-old catalog due")
	}
}
