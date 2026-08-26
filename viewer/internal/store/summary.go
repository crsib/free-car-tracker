package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"sync"
	"time"

	"crsib.me/fct-viewer/internal/model"
	"crsib.me/fct-viewer/internal/series"
	"crsib.me/fct-viewer/internal/webdav"
)

// TripSummary is everything about one trip that cross-trip analysis needs, reduced from the
// full time series so hundreds of trips can be compared without holding any of them in memory.
//
// It is derived data, versioned by the trip's syncedAtMillis: re-upload a trip and the summary
// is recomputed, but an unchanged trip is summarised exactly once, ever.
type TripSummary struct {
	TripSyncID    string    `json:"tripSyncId"`
	VehicleSyncID string    `json:"vehicleSyncId"`
	SourceKey     string    `json:"sourceKey"`
	ComputedAt    time.Time `json:"computedAt"`

	StartedAtMillis int64 `json:"startedAtMillis"`
	EndedAtMillis   int64 `json:"endedAtMillis"`

	// Stats is one entry per OBD column that had at least one reading.
	Stats []series.Stat `json:"stats"`

	// Byte sizes of the side files, learned when they were downloaded. The index no longer
	// spends a PROPFIND per trip to discover them, so this is where the UI gets them from.
	ObdSize int64 `json:"obdSize,omitempty"`
	GpxSize int64 `json:"gpxSize,omitempty"`

	// Headline values, pulled out because every analysis view wants them and digging them
	// back out of Stats at query time would be both slow and repetitive.
	GpsDistanceKm  *float64 `json:"gpsDistanceKm,omitempty"`
	ElevationGainM *float64 `json:"elevationGainM,omitempty"`
	AmbientTempC   *float64 `json:"ambientTempC,omitempty"`
	SocStartPct    *float64 `json:"socStartPct,omitempty"`
	SocEndPct      *float64 `json:"socEndPct,omitempty"`
	StartLat       *float64 `json:"startLat,omitempty"`
	StartLon       *float64 `json:"startLon,omitempty"`
	EndLat         *float64 `json:"endLat,omitempty"`
	EndLon         *float64 `json:"endLon,omitempty"`
}

// socKeys are the metric keys that have carried state of charge across the profiles this app
// has shipped. The first one present in a trip wins.
var socKeys = []string{"EV_SOC", "SOC", "hybridBatteryLifePct"}

// summaryPath keys a summary by car *and* trip. Trip sync ids are UUIDs and unique on their
// own, but scoping by car costs nothing and means a duplicated id can never make one trip's
// numbers show up under another.
func (ix *Index) summaryPath(vehicleSyncID, tripSyncID string) string {
	if vehicleSyncID == "" {
		vehicleSyncID = "_"
	}
	return filepath.Join(ix.cache.Dir(), "summaries", vehicleSyncID, tripSyncID+".json")
}

// sourceKey identifies the version of a trip a summary was computed from.
//
// It uses the app's own upload clock rather than the side files' ETags, because the index no
// longer lists each trip folder (that cost a request per trip against a throttling host). A
// finished trip is immutable, so the only thing that can change its contents is a re-upload —
// and that always moves syncedAtMillis.
func sourceKey(t TripRecord) string {
	return fmt.Sprintf("v2|%s|%d", t.TripSyncID, t.SyncedAtMillis)
}

// LoadSummary returns a cached summary if it matches the trip's current source files.
func (ix *Index) LoadSummary(t TripRecord) (*TripSummary, bool) {
	b, err := os.ReadFile(ix.summaryPath(t.VehicleSyncID, t.TripSyncID))
	if err != nil {
		return nil, false
	}
	var s TripSummary
	if err := json.Unmarshal(b, &s); err != nil {
		return nil, false
	}
	if s.SourceKey != sourceKey(t) {
		return nil, false
	}
	return &s, true
}

// loadSummaryIgnoringVersion reports whether a summary exists on disk at all, whatever version
// it was computed from. Used to distinguish a first-ever summarise from a re-upload.
func (ix *Index) loadSummaryIgnoringVersion(t TripRecord) (*TripSummary, bool) {
	b, err := os.ReadFile(ix.summaryPath(t.VehicleSyncID, t.TripSyncID))
	if err != nil {
		return nil, false
	}
	var s TripSummary
	if err := json.Unmarshal(b, &s); err != nil {
		return nil, false
	}
	return &s, true
}

func (ix *Index) storeSummary(s *TripSummary) error {
	path := ix.summaryPath(s.VehicleSyncID, s.TripSyncID)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	b, err := json.Marshal(s)
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// OBDFrame returns a trip's parsed OBD series, downloading and caching obd.csv.gz if needed.
// A trip recorded without an adapter has no such file; that yields (nil, nil), not an error.
func (ix *Index) OBDFrame(ctx context.Context, t TripRecord) (*series.Frame, error) {
	if !t.HasObd() && !ix.cache.Has(t.ObdPath()) {
		return nil, nil
	}
	gz, err := ix.cache.Fetch(ctx, ix.Client(), t.ObdPath(), webdav.Entry{})
	if err != nil {
		if errors.Is(err, webdav.ErrNotFound) {
			return nil, nil
		}
		return nil, err
	}
	raw, err := webdav.Gunzip(gz)
	if err != nil {
		return nil, fmt.Errorf("gunzip %s: %w", t.ObdPath(), err)
	}
	return series.ParseOBD(raw)
}

// Track returns a trip's GPS fixes, downloading and caching track.gpx.gz if needed.
func (ix *Index) Track(ctx context.Context, t TripRecord) ([]model.GpsPoint, error) {
	if !t.HasTrack() && !ix.cache.Has(t.GpxPath()) {
		return nil, nil
	}
	gz, err := ix.cache.Fetch(ctx, ix.Client(), t.GpxPath(), webdav.Entry{})
	if err != nil {
		if errors.Is(err, webdav.ErrNotFound) {
			return nil, nil
		}
		return nil, err
	}
	raw, err := webdav.Gunzip(gz)
	if err != nil {
		return nil, fmt.Errorf("gunzip %s: %w", t.GpxPath(), err)
	}
	return series.ParseGPX(raw)
}

// ErrUnknownFile means the caller asked for a file that is not part of a trip folder. It is a
// bad request rather than a missing resource, so it gets its own sentinel.
var ErrUnknownFile = errors.New("unknown trip file")

// RawFile returns one of a trip's files exactly as stored on the server, for download.
func (ix *Index) RawFile(ctx context.Context, t TripRecord, name string) ([]byte, error) {
	switch name {
	case TripJSON:
		return ix.cache.Fetch(ctx, ix.Client(), t.JSONPath(), webdav.Entry{})
	case ObdCsvGz:
		return ix.cache.Fetch(ctx, ix.Client(), t.ObdPath(), webdav.Entry{})
	case TrackGpxGz:
		return ix.cache.Fetch(ctx, ix.Client(), t.GpxPath(), webdav.Entry{})
	default:
		return nil, fmt.Errorf("%w: %q", ErrUnknownFile, name)
	}
}

// Summarise computes (or reuses) a trip's summary. It is the only place that needs the full
// time series, so it is also where the expensive download happens during a prefetch.
func (ix *Index) Summarise(ctx context.Context, t TripRecord) (*TripSummary, error) {
	if s, ok := ix.LoadSummary(t); ok {
		return s, nil
	}
	// Getting here with a summary on disk means the trip was re-uploaded (its syncedAtMillis
	// moved). Cached side files are keyed by path with no ETag, so they would otherwise be
	// served forever; drop them explicitly at the one moment we know they are stale.
	if _, stale := ix.loadSummaryIgnoringVersion(t); stale {
		ix.cache.Forget(t.ObdPath())
		ix.cache.Forget(t.GpxPath())
	}

	s := &TripSummary{
		TripSyncID:      t.TripSyncID,
		VehicleSyncID:   t.VehicleSyncID,
		SourceKey:       sourceKey(t),
		ComputedAt:      time.Now(),
		StartedAtMillis: t.Trip.StartedAtMillis,
	}
	if t.Trip.EndedAtMillis != nil {
		s.EndedAtMillis = *t.Trip.EndedAtMillis
	}

	frame, err := ix.OBDFrame(ctx, t)
	if err != nil {
		return nil, err
	}
	if frame != nil {
		s.Stats = frame.Stats()
		byName := map[string]series.Stat{}
		for _, st := range s.Stats {
			byName[st.Name] = st
		}
		if st, ok := byName["ambientAirTempC"]; ok {
			v := st.Mean
			s.AmbientTempC = &v
		}
		for _, key := range socKeys {
			if st, ok := byName[key]; ok {
				first, last := st.First, st.Last
				s.SocStartPct, s.SocEndPct = &first, &last
				break
			}
		}
	}

	pts, err := ix.Track(ctx, t)
	if err != nil {
		return nil, err
	}
	if len(pts) > 0 {
		cum := series.CumulativeKm(pts)
		d := cum[len(cum)-1]
		s.GpsDistanceKm = &d
		lat, lon := pts[0].Latitude, pts[0].Longitude
		s.StartLat, s.StartLon = &lat, &lon
		elat, elon := pts[len(pts)-1].Latitude, pts[len(pts)-1].Longitude
		s.EndLat, s.EndLon = &elat, &elon
		gain := 0.0
		for i := 1; i < len(pts); i++ {
			if pts[i].AltitudeM == nil || pts[i-1].AltitudeM == nil {
				continue
			}
			// Only positive deltas: total climb, the number that explains consumption.
			if d := *pts[i].AltitudeM - *pts[i-1].AltitudeM; d > 0 {
				gain += d
			}
		}
		if !math.IsNaN(gain) {
			s.ElevationGainM = &gain
		}
	}

	// The index no longer learns side-file sizes from a listing, so record them here now that
	// the bytes are on disk. The trip list shows them once known and omits them before that.
	if b, ok := ix.cache.Cached(t.ObdPath()); ok {
		s.ObdSize = int64(len(b))
	}
	if b, ok := ix.cache.Cached(t.GpxPath()); ok {
		s.GpxSize = int64(len(b))
	}

	if err := ix.storeSummary(s); err != nil {
		return s, nil // usable even if it could not be cached
	}
	return s, nil
}

// Prefetch downloads and summarises every trip in scope, so the analysis views have complete
// data and every trip opens instantly afterwards. Only trips not already summarised are
// touched, which makes a repeat run nearly free.
//
// vehicleSyncID narrows it to one car; empty means all.
func (ix *Index) Prefetch(vehicleSyncID string) bool {
	ix.jobMu.Lock()
	defer ix.jobMu.Unlock()
	if ix.Progress().Running {
		return false
	}
	cat := ix.Catalog()
	if cat == nil {
		return false
	}
	var todo []TripRecord
	for _, t := range cat.Trips {
		if vehicleSyncID != "" && t.VehicleSyncID != vehicleSyncID {
			continue
		}
		todo = append(todo, t)
	}

	ctx, cancel := context.WithCancel(context.Background())
	ix.cancel = cancel
	ix.setProgress(func(p *Progress) {
		*p = Progress{
			Running: true, Phase: "prefetch", Total: len(todo),
			Message: "Downloading trip data", StartedAt: time.Now(),
		}
	})

	go func() {
		defer cancel()
		// Downloads are large and the rate limiter already paces them; keep the worker count
		// at or below the index concurrency so a background prefetch cannot crowd out an
		// interactive view or push a throttling host into 429s.
		sem := make(chan struct{}, ix.Concurrency())
		var wg sync.WaitGroup
		var failMu sync.Mutex
		var failures []string

		for _, t := range todo {
			if ctx.Err() != nil {
				break
			}
			wg.Add(1)
			go func(t TripRecord) {
				defer wg.Done()
				sem <- struct{}{}
				defer func() { <-sem }()
				if ctx.Err() != nil {
					return
				}
				if _, err := ix.Summarise(ctx, t); err != nil {
					failMu.Lock()
					failures = append(failures, fmt.Sprintf("%s: %v", t.TripSyncID, err))
					failMu.Unlock()
				}
				ix.setProgress(func(p *Progress) {
					p.Done++
					p.Message = fmt.Sprintf("Downloaded %d/%d trips", p.Done, p.Total)
				})
			}(t)
		}
		wg.Wait()

		ix.setProgress(func(p *Progress) {
			p.Running = false
			p.FinishedAt = time.Now()
			if len(failures) > 0 {
				p.Error = fmt.Sprintf("%d of %d trips failed: %s", len(failures), len(todo), failures[0])
				p.Message = "Finished with errors"
			} else if ctx.Err() != nil {
				p.Message = "Cancelled"
			} else {
				p.Message = fmt.Sprintf("Ready: %d trips cached", len(todo))
			}
		})
	}()
	return true
}
