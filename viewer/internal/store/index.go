package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"crsib.me/fct-viewer/internal/model"
	"crsib.me/fct-viewer/internal/webdav"
)

// Remote layout constants, mirroring WebDavPaths.kt.
const (
	VehicleJSON = "vehicle.json"
	LedgerJSON  = "ledger.json"
	TripJSON    = "trip.json"
	ObdCsvGz    = "obd.csv.gz"
	TrackGpxGz  = "track.gpx.gz"
	TombstoneD  = "tombstones"
	DidScansD   = "did-scans"
	TripsD      = "trips"
)

// VehicleRecord is one car: its vehicle.json plus its whole ledger.
type VehicleRecord struct {
	SyncID      string               `json:"syncId"`
	Vehicle     model.Vehicle        `json:"vehicle"`
	Ledger      model.LedgerDocument `json:"ledger"`
	LedgerFound bool                 `json:"ledgerFound"`
	TripCount   int                  `json:"tripCount"`
}

// TripRecord is one synced trip: its manifest plus what is known about its side files.
type TripRecord struct {
	TripSyncID    string                 `json:"tripSyncId"`
	VehicleSyncID string                 `json:"vehicleSyncId"`
	Dir           string                 `json:"dir"`
	Trip          model.Trip             `json:"trip"`
	Dtcs          []model.DtcRecord      `json:"dtcs,omitempty"`
	Files         model.TripFileManifest `json:"files"`
	// Sizes are learned when a side file is first downloaded, not from a listing; zero means
	// "not known yet", not "empty". Use Files to decide whether a side file exists at all.
	ObdSize int64 `json:"obdSize"`
	GpxSize int64 `json:"gpxSize"`
	// SyncedAtMillis is the app's own upload clock for this trip. It is the version signal for
	// cached payloads and derived summaries: a re-uploaded trip gets a new one.
	SyncedAtMillis int64 `json:"syncedAtMillis,omitempty"`
}

// ObdPath, GpxPath and JSONPath are the remote paths of this trip's files.
func (t TripRecord) ObdPath() string  { return t.Dir + ObdCsvGz }
func (t TripRecord) GpxPath() string  { return t.Dir + TrackGpxGz }
func (t TripRecord) JSONPath() string { return t.Dir + TripJSON }

// HasObd and HasTrack read the manifest rather than a directory listing, so they are correct
// without spending a request.
func (t TripRecord) HasObd() bool   { return t.Files.ObdCsvGz != nil }
func (t TripRecord) HasTrack() bool { return t.Files.GpxGz != nil }

// Catalog is the whole remote tree as the viewer understands it.
type Catalog struct {
	BuiltAt  time.Time       `json:"builtAt"`
	Vehicles []VehicleRecord `json:"vehicles"`
	Trips    []TripRecord    `json:"trips"`
	// Warnings records what the walk could not read, so a partial index says so out loud
	// instead of quietly presenting itself as complete.
	Warnings []string `json:"warnings,omitempty"`
}

// Progress is the live state of an index or prefetch run, polled by the UI.
type Progress struct {
	Running bool   `json:"running"`
	Phase   string `json:"phase"`
	// Full marks a run that ignores the byte cache, so the UI can say why it is slow.
	Full       bool      `json:"full,omitempty"`
	Done       int       `json:"done"`
	Total      int       `json:"total"`
	Message    string    `json:"message"`
	Error      string    `json:"error,omitempty"`
	StartedAt  time.Time `json:"startedAt,omitempty"`
	FinishedAt time.Time `json:"finishedAt,omitempty"`
}

// Index owns the catalog and the single background job that rebuilds it.
type Index struct {
	cache  *Cache
	client webdav.Backend

	mu       sync.RWMutex
	catalog  *Catalog
	progress Progress

	jobMu  sync.Mutex
	cancel context.CancelFunc

	concurrency int
}

func NewIndex(cache *Cache, client webdav.Backend) *Index {
	return &Index{cache: cache, client: client}
}

// SetClient swaps the storage target, for when the user reconfigures the server at runtime.
func (ix *Index) SetClient(c webdav.Backend) {
	ix.mu.Lock()
	ix.client = c
	ix.mu.Unlock()
}

func (ix *Index) Client() webdav.Backend {
	ix.mu.RLock()
	defer ix.mu.RUnlock()
	return ix.client
}

func (ix *Index) Catalog() *Catalog {
	ix.mu.RLock()
	defer ix.mu.RUnlock()
	return ix.catalog
}

func (ix *Index) Progress() Progress {
	ix.mu.RLock()
	defer ix.mu.RUnlock()
	return ix.progress
}

func (ix *Index) setProgress(f func(*Progress)) {
	ix.mu.Lock()
	f(&ix.progress)
	ix.mu.Unlock()
}

func (ix *Index) catalogPath() string { return filepath.Join(ix.cache.Dir(), "catalog.json") }

// LoadPersisted restores the last catalog so a restart shows data before any network call.
func (ix *Index) LoadPersisted() error {
	b, err := os.ReadFile(ix.catalogPath())
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	var c Catalog
	if err := json.Unmarshal(b, &c); err != nil {
		return err
	}
	ix.mu.Lock()
	ix.catalog = &c
	ix.mu.Unlock()
	return nil
}

func (ix *Index) persist(c *Catalog) error {
	b, err := json.Marshal(c)
	if err != nil {
		return err
	}
	tmp := ix.catalogPath() + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, ix.catalogPath())
}

// Cancel stops a running job.
func (ix *Index) Cancel() {
	ix.jobMu.Lock()
	defer ix.jobMu.Unlock()
	if ix.cancel != nil {
		ix.cancel()
	}
}

// Refresh rebuilds the catalog in the background. A second call while one is running is a
// no-op rather than a queued duplicate: two concurrent walks would double the load on the
// server for an identical result.
func (ix *Index) Refresh() bool { return ix.refresh(false) }

// RefreshFull rebuilds the catalog and re-downloads every manifest it walks past, ignoring
// the byte cache. The ordinary refresh trusts a cached trip.json forever, because a finished
// trip is immutable and re-reading one per trip is exactly the traffic this viewer was tuned
// to avoid. That assumption is right until it is not - a manifest rewritten in place, or a
// cache that captured a half-written upload - and this is the escape hatch for it.
func (ix *Index) RefreshFull() bool { return ix.refresh(true) }

func (ix *Index) refresh(full bool) bool {
	ix.jobMu.Lock()
	defer ix.jobMu.Unlock()
	if ix.Progress().Running {
		return false
	}
	ctx, cancel := context.WithCancel(context.Background())
	ix.cancel = cancel
	message := "Listing cars"
	if full {
		message = "Listing cars (full re-read)"
	}
	ix.setProgress(func(p *Progress) {
		*p = Progress{
			Running: true, Phase: "index", Full: full,
			Message: message, StartedAt: time.Now(),
		}
	})
	go func() {
		defer cancel()
		cat, err := ix.build(ctx, full)
		ix.mu.Lock()
		if err == nil {
			ix.catalog = cat
		}
		ix.progress.Running = false
		ix.progress.FinishedAt = time.Now()
		if err != nil {
			ix.progress.Error = err.Error()
			ix.progress.Message = "Index failed"
		} else {
			ix.progress.Error = ""
			ix.progress.Message = fmt.Sprintf("%d cars, %d trips", len(cat.Vehicles), len(cat.Trips))
			ix.progress.Done, ix.progress.Total = len(cat.Trips), len(cat.Trips)
		}
		ix.mu.Unlock()
		if err == nil {
			_ = ix.persist(cat)
		}
	}()
	return true
}

// defaultIndexConcurrency caps parallel requests during a walk.
//
// This used to be 8, which is fine against your own Nextcloud and actively harmful against a
// hosted service: Yandex.Disk answered the burst with 429s and 131 trips silently vanished from
// the index. Two is deliberately timid — the rate limiter is the real control, and concurrency
// beyond it only buys latency hiding.
const defaultIndexConcurrency = 2

// Concurrency returns the configured parallelism, defaulting when unset.
func (ix *Index) Concurrency() int {
	ix.mu.RLock()
	defer ix.mu.RUnlock()
	if ix.concurrency > 0 {
		return ix.concurrency
	}
	return defaultIndexConcurrency
}

// SetConcurrency overrides the parallelism of index and prefetch walks.
func (ix *Index) SetConcurrency(n int) {
	ix.mu.Lock()
	ix.concurrency = n
	ix.mu.Unlock()
}

func (ix *Index) build(ctx context.Context, full bool) (*Catalog, error) {
	client := ix.Client()
	if client == nil {
		return nil, errors.New("storage is not configured")
	}
	cat := &Catalog{BuiltAt: time.Now()}
	var warnMu sync.Mutex
	warn := func(format string, args ...any) {
		warnMu.Lock()
		cat.Warnings = append(cat.Warnings, fmt.Sprintf(format, args...))
		warnMu.Unlock()
	}

	roots, err := client.List(ctx, "")
	if err != nil {
		return nil, fmt.Errorf("list root: %w", err)
	}

	type vehicleWork struct {
		syncID  string
		entries []webdav.Entry
	}
	var vehicles []vehicleWork
	for _, e := range roots {
		if !e.IsCollection || e.Name == DidScansD {
			continue
		}
		children, err := client.List(ctx, e.Name+"/")
		if err != nil {
			warn("list %s: %v", e.Name, err)
			continue
		}
		hasVehicle := false
		for _, c := range children {
			if c.Name == VehicleJSON {
				hasVehicle = true
			}
		}
		if !hasVehicle {
			continue // not a car folder - some other content sharing the same WebDAV directory
		}
		vehicles = append(vehicles, vehicleWork{syncID: e.Name, entries: children})
	}

	ix.setProgress(func(p *Progress) {
		p.Message = fmt.Sprintf("Reading %d cars", len(vehicles))
	})

	tripCounts := map[string]int{}
	var allTrips []TripRecord

	for _, v := range vehicles {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		rec := VehicleRecord{SyncID: v.syncID}
		byName := map[string]webdav.Entry{}
		for _, e := range v.entries {
			byName[e.Name] = e
		}

		if full {
			ix.cache.Forget(v.syncID + "/" + VehicleJSON)
			ix.cache.Forget(v.syncID + "/" + LedgerJSON)
		}
		vb, err := ix.cache.Fetch(ctx, client, v.syncID+"/"+VehicleJSON, byName[VehicleJSON])
		if err != nil {
			warn("read %s/%s: %v", v.syncID, VehicleJSON, err)
			continue
		}
		var vdoc model.VehicleDocument
		if err := json.Unmarshal(vb, &vdoc); err != nil {
			warn("parse %s/%s: %v", v.syncID, VehicleJSON, err)
			continue
		}
		rec.Vehicle = vdoc.Vehicle
		if rec.Vehicle.SyncID == "" {
			rec.Vehicle.SyncID = v.syncID
		}

		if e, ok := byName[LedgerJSON]; ok {
			lb, err := ix.cache.Fetch(ctx, client, v.syncID+"/"+LedgerJSON, e)
			if err != nil {
				warn("read %s/%s: %v", v.syncID, LedgerJSON, err)
			} else if err := json.Unmarshal(lb, &rec.Ledger); err != nil {
				warn("parse %s/%s: %v", v.syncID, LedgerJSON, err)
			} else {
				rec.LedgerFound = true
			}
		}

		deleted := map[string]bool{}
		if _, ok := byName[TombstoneD]; ok {
			stones, err := client.List(ctx, v.syncID+"/"+TombstoneD+"/")
			if err != nil {
				warn("list tombstones for %s: %v", v.syncID, err)
			}
			for _, s := range stones {
				deleted[s.Name] = true
			}
		}

		trips, err := ix.walkTrips(ctx, client, v.syncID, deleted, full, warn)
		if err != nil {
			return nil, err
		}
		tripCounts[v.syncID] = len(trips)
		rec.TripCount = len(trips)
		allTrips = append(allTrips, trips...)
		cat.Vehicles = append(cat.Vehicles, rec)
	}

	// Newest first: that is how both the trip list and the analysis views want them, and
	// sorting once here keeps every reader from re-sorting.
	sort.SliceStable(allTrips, func(i, j int) bool {
		return allTrips[i].Trip.StartedAtMillis > allTrips[j].Trip.StartedAtMillis
	})
	cat.Trips = allTrips
	sort.SliceStable(cat.Vehicles, func(i, j int) bool {
		return cat.Vehicles[i].Vehicle.Name < cat.Vehicles[j].Vehicle.Name
	})
	return cat, ctx.Err()
}

// walkTrips descends {vehicle}/trips/YYYY/MM/{tripSyncId}/, reading each trip.json. Month
// buckets are walked serially but the trip folders inside them run in parallel, which is
// where the round-trips actually are.
func (ix *Index) walkTrips(
	ctx context.Context, client webdav.Backend, vehicleSyncID string,
	deleted map[string]bool, full bool, warn func(string, ...any),
) ([]TripRecord, error) {
	years, err := client.List(ctx, vehicleSyncID+"/"+TripsD+"/")
	if err != nil {
		warn("list trips for %s: %v", vehicleSyncID, err)
		return nil, nil
	}

	var monthDirs []string
	for _, y := range years {
		if !y.IsCollection {
			continue
		}
		months, err := client.List(ctx, fmt.Sprintf("%s/%s/%s/", vehicleSyncID, TripsD, y.Name))
		if err != nil {
			warn("list %s/%s/%s: %v", vehicleSyncID, TripsD, y.Name, err)
			continue
		}
		for _, m := range months {
			if m.IsCollection {
				monthDirs = append(monthDirs, fmt.Sprintf("%s/%s/%s/%s/", vehicleSyncID, TripsD, y.Name, m.Name))
			}
		}
	}

	type tripDir struct{ path, syncID string }
	var dirs []tripDir
	for _, md := range monthDirs {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		entries, err := client.List(ctx, md)
		if err != nil {
			warn("list %s: %v", md, err)
			continue
		}
		for _, e := range entries {
			if e.IsCollection && !deleted[e.Name] {
				dirs = append(dirs, tripDir{path: md + e.Name + "/", syncID: e.Name})
			}
		}
	}

	ix.setProgress(func(p *Progress) {
		p.Total += len(dirs)
		p.Message = fmt.Sprintf("Reading trips for %s", vehicleSyncID)
	})

	out := make([]TripRecord, len(dirs))
	found := make([]bool, len(dirs))
	sem := make(chan struct{}, ix.Concurrency())
	var wg sync.WaitGroup
	for i, d := range dirs {
		wg.Add(1)
		go func(i int, d tripDir) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			if ctx.Err() != nil {
				return
			}
			rec, ok := ix.readTrip(ctx, client, vehicleSyncID, d.path, d.syncID, full, warn)
			out[i], found[i] = rec, ok
			ix.setProgress(func(p *Progress) { p.Done++ })
		}(i, d)
	}
	wg.Wait()
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}

	trips := make([]TripRecord, 0, len(out))
	for i, ok := range found {
		if ok {
			trips = append(trips, out[i])
		}
	}
	return trips, nil
}

// readTrip reads one trip folder with a single GET of its trip.json.
//
// It deliberately does not PROPFIND the folder first. That listing only bought the side files'
// sizes and ETags, at the cost of doubling the request count for the whole walk — and against a
// throttling host like Yandex.Disk, requests are the scarce resource. The manifest already says
// which side files exist, and a finished trip is immutable (the app uploads folders that are not
// present remotely and never rewrites them), so there is nothing for an ETag to invalidate.
// Re-uploads are caught by the manifest's own syncedAtMillis instead — see sourceKey.
//
// A missing trip.json is an upload that never finished: it is written last, and the side files
// alone carry no aggregates or identity. That now surfaces as a 404 rather than an absent
// listing entry, which is the same conclusion for one fewer round trip.
func (ix *Index) readTrip(
	ctx context.Context, client webdav.Backend,
	vehicleSyncID, dir, tripSyncID string, full bool, warn func(string, ...any),
) (TripRecord, bool) {
	if full {
		ix.cache.Forget(dir + TripJSON)
	}
	b, err := ix.cache.Fetch(ctx, client, dir+TripJSON, webdav.Entry{})
	if err != nil {
		if errors.Is(err, webdav.ErrNotFound) {
			return TripRecord{}, false // incomplete upload
		}
		warn("read %s%s: %v", dir, TripJSON, err)
		return TripRecord{}, false
	}
	var man model.TripManifest
	if err := json.Unmarshal(b, &man); err != nil {
		warn("parse %s%s: %v", dir, TripJSON, err)
		return TripRecord{}, false
	}

	rec := TripRecord{
		TripSyncID:    firstNonEmpty(man.TripSyncID, tripSyncID),
		VehicleSyncID: firstNonEmpty(man.VehicleSyncID, vehicleSyncID),
		Dir:           dir,
		Trip:          man.Trip,
		Dtcs:          man.Dtcs,
		Files:         man.Files,
	}
	if man.Trip.SyncedAtMillis != nil {
		rec.SyncedAtMillis = *man.Trip.SyncedAtMillis
	}
	// Sizes are unknown until a side file is actually downloaded; they are filled in then and
	// carried in the summary, so the UI shows them once known and omits them before that.
	if sum, ok := ix.LoadSummary(rec); ok {
		rec.ObdSize, rec.GpxSize = sum.ObdSize, sum.GpxSize
	}
	return rec, true
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

// FindTrip looks a trip up by its sync id.
func (c *Catalog) FindTrip(syncID string) (TripRecord, bool) {
	if c == nil {
		return TripRecord{}, false
	}
	for _, t := range c.Trips {
		if t.TripSyncID == syncID {
			return t, true
		}
	}
	return TripRecord{}, false
}

// FindVehicle looks a car up by its sync id.
func (c *Catalog) FindVehicle(syncID string) (VehicleRecord, bool) {
	if c == nil {
		return VehicleRecord{}, false
	}
	for _, v := range c.Vehicles {
		if v.SyncID == syncID {
			return v, true
		}
	}
	return VehicleRecord{}, false
}
