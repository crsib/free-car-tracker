package store

import (
	"bytes"
	"compress/gzip"
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path"
	"sort"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"crsib.me/fct-viewer/internal/webdav"
)

// davTree is an in-memory WebDAV server over a map of path -> content. Directories are
// implied by the paths, which is enough to exercise the index walk without a real server.
type davTree struct {
	mu        sync.Mutex
	files     map[string]string
	gets      []string
	propfinds []string
	// throttleFirst makes the first N requests answer 429, to exercise retry/backoff.
	throttleFirst int
	seen          int
}

func (d *davTree) handler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		rel := strings.Trim(r.URL.Path, "/")

		d.mu.Lock()
		d.seen++
		if d.seen <= d.throttleFirst {
			d.mu.Unlock()
			w.Header().Set("Retry-After", "0")
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		if r.Method == "PROPFIND" {
			d.propfinds = append(d.propfinds, rel)
		}
		d.mu.Unlock()

		switch r.Method {
		case "PROPFIND":
			children := map[string]bool{} // name -> isCollection
			exists := rel == ""
			prefix := rel
			if prefix != "" {
				prefix += "/"
			}
			for p := range d.files {
				if p == rel {
					exists = true
				}
				if !strings.HasPrefix(p, prefix) {
					continue
				}
				exists = true
				rest := strings.TrimPrefix(p, prefix)
				if rest == "" {
					continue
				}
				name, _, isDir := strings.Cut(rest, "/")
				children[name] = children[name] || isDir
			}
			if !exists {
				http.NotFound(w, r)
				return
			}
			var b strings.Builder
			b.WriteString(`<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">`)
			writeResp := func(href string, isDir bool, size int) {
				rt := ""
				if isDir {
					rt = "<d:collection/>"
					href += "/"
				}
				fmt.Fprintf(&b,
					`<d:response><d:href>/%s</d:href><d:propstat><d:status>HTTP/1.1 200 OK</d:status>`+
						`<d:prop><d:resourcetype>%s</d:resourcetype>`+
						`<d:getlastmodified>Mon, 03 Aug 2026 09:15:00 GMT</d:getlastmodified>`+
						`<d:getcontentlength>%d</d:getcontentlength><d:getetag>"e-%s"</d:getetag>`+
						`</d:prop></d:propstat></d:response>`,
					href, rt, size, strings.ReplaceAll(href, "/", "_"))
			}
			writeResp(rel, true, 0)
			if r.Header.Get("Depth") == "1" {
				names := make([]string, 0, len(children))
				for n := range children {
					names = append(names, n)
				}
				sort.Strings(names)
				for _, n := range names {
					writeResp(path.Join(rel, n), children[n], len(d.files[path.Join(rel, n)]))
				}
			}
			b.WriteString(`</d:multistatus>`)
			w.WriteHeader(http.StatusMultiStatus)
			_, _ = w.Write([]byte(b.String()))
		case http.MethodGet:
			body, ok := d.files[rel]
			if !ok {
				http.NotFound(w, r)
				return
			}
			d.mu.Lock()
			d.gets = append(d.gets, rel)
			d.mu.Unlock()
			_, _ = w.Write([]byte(body))
		default:
			http.Error(w, "read-only", http.StatusMethodNotAllowed)
		}
	}
}

func gz(s string) string {
	var buf bytes.Buffer
	zw := gzip.NewWriter(&buf)
	_, _ = zw.Write([]byte(s))
	_ = zw.Close()
	return buf.String()
}

func vehicleJSON(syncID, name string) string {
	return fmt.Sprintf(`{"schemaVersion":1,"exportedAt":1,"vehicle":{
		"id":1,"name":%q,"make":"Demo","model":"X","year":2024,"plate":"AA",
		"powertrain":"REV","fuelType":"PETROL","tankCapacityL":60,"batteryCapacityKwh":40,
		"unitSystem":"METRIC","currency":"EUR","initialOdometerKm":0,"colorArgb":null,
		"isArchived":false,"createdAt":1,"syncId":%q}}`, name, syncID)
}

func tripJSON(tripID, vehicleID string, startedAt int64) string {
	return fmt.Sprintf(`{"schemaVersion":1,"tripSyncId":%q,"vehicleSyncId":%q,"trip":{
		"id":1,"vehicleId":1,"startedAtMillis":%d,"endedAtMillis":%d,"status":"COMPLETED",
		"distanceKm":12.5,"durationSec":900,"avgSpeedKmh":50,"maxSpeedKmh":90,"movingSec":800,
		"fuelUsedL":1.1,"energyUsedKwh":3.2,"avgConsumptionL100":8.8,"obdSampleCount":3,
		"gpsPointCount":2,"transport":"BT_CLASSIC","profileKey":"GENERIC","note":null,
		"syncId":%q,"syncedAtMillis":%d},
		"dtcs":[{"id":1,"tripId":1,"timestampMillis":%d,"code":"P0420","pending":false}],
		"files":{"obdCsvGz":"obd.csv.gz","gpxGz":"track.gpx.gz","obdSampleCount":3,"gpsPointCount":2}}`,
		tripID, vehicleID, startedAt, startedAt+900000, tripID, startedAt+3600000, startedAt+1000)
}

const obdCSV = `timestampMillis,speedKmh,ambientAirTempC,EV_SOC[PCT]
1767348000000,0,7,80
1767348001000,40,7,79
1767348002000,80,7,78
`

const trackGPX = `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="free-car-tracker" xmlns="http://www.topografix.com/GPX/1/1" xmlns:fct="https://crsib.me/freecartracker/gpx/1">
<trk><name>t</name><trkseg>
<trkpt lat="52.20" lon="21.00"><ele>100</ele><time>2026-01-02T10:00:00Z</time><extensions><fct:speedKmh>0</fct:speedKmh><fct:provider>fused</fct:provider></extensions></trkpt>
<trkpt lat="52.21" lon="21.00"><ele>140</ele><time>2026-01-02T10:00:30Z</time><extensions><fct:speedKmh>80</fct:speedKmh><fct:provider>fused</fct:provider></extensions></trkpt>
</trkseg></trk></gpx>`

const (
	carA = "aaaaaaaa-0000-4000-8000-000000000001"
	carB = "bbbbbbbb-0000-4000-8000-000000000002"
)

func newTestIndex(t *testing.T, tree *davTree) *Index {
	t.Helper()
	return newTestIndexWithRetries(t, tree, 0)
}

func newTestIndexWithRetries(t *testing.T, tree *davTree, retries int) *Index {
	t.Helper()
	srv := httptest.NewServer(tree.handler())
	t.Cleanup(srv.Close)
	cache, err := NewCache(t.TempDir(), 0)
	if err != nil {
		t.Fatal(err)
	}
	client := webdav.New(webdav.Config{
		URL: srv.URL + "/", Username: "u", Password: "p", MaxRetries: retries,
	}, nil)
	return NewIndex(cache, client)
}

func buildNow(t *testing.T, ix *Index) *Catalog {
	t.Helper()
	if !ix.Refresh() {
		t.Fatal("refresh did not start")
	}
	deadline := time.Now().Add(10 * time.Second)
	for ix.Progress().Running {
		if time.Now().After(deadline) {
			t.Fatal("index did not finish")
		}
		time.Sleep(10 * time.Millisecond)
	}
	if p := ix.Progress(); p.Error != "" {
		t.Fatalf("index failed: %s", p.Error)
	}
	cat := ix.Catalog()
	if cat == nil {
		t.Fatal("no catalog after refresh")
	}
	return cat
}

func TestIndexWalksTheSyncLayout(t *testing.T) {
	start := time.Date(2026, 1, 2, 10, 0, 0, 0, time.UTC).UnixMilli()
	tree := &davTree{files: map[string]string{
		carA + "/vehicle.json": vehicleJSON(carA, "Li Auto L9"),
		carA + "/ledger.json": `{"schemaVersion":3,"exportedAt":1,"vehicleSyncId":"` + carA + `",
			"fuelEntries":[{"id":1,"vehicleId":1,"dateMillis":100,"odometerKm":1000,"volumeL":40,
			"totalCostMinor":6000,"currency":"EUR","isFullTank":true,"fuelType":"PETROL",
			"station":"Shell","note":null,"syncId":"f1","updatedAtMillis":100}],
			"chargeEntries":[],"expenses":[],"deletedEntries":[]}`,
		carA + "/trips/2026/01/trip-1/trip.json":    tripJSON("trip-1", carA, start),
		carA + "/trips/2026/01/trip-1/obd.csv.gz":   gz(obdCSV),
		carA + "/trips/2026/01/trip-1/track.gpx.gz": gz(trackGPX),
		carA + "/trips/2026/02/trip-2/trip.json":    tripJSON("trip-2", carA, start+30*86400000),
		carA + "/trips/2026/02/trip-2/obd.csv.gz":   gz(obdCSV),

		// A second car, to prove the walk is per-vehicle.
		carB + "/vehicle.json":                   vehicleJSON(carB, "Golf"),
		carB + "/trips/2026/01/trip-3/trip.json": tripJSON("trip-3", carB, start),

		// Not a car: no vehicle.json, so it must be ignored rather than half-parsed.
		"did-scans/scan.csv":     "did,value\n",
		"someone-elses/notes.md": "hello",
	}}

	cat := buildNow(t, newTestIndex(t, tree))

	if len(cat.Vehicles) != 2 {
		t.Fatalf("got %d cars, want 2: %+v", len(cat.Vehicles), cat.Vehicles)
	}
	if len(cat.Trips) != 3 {
		t.Fatalf("got %d trips, want 3", len(cat.Trips))
	}

	// Newest first, so the trip list and analysis need no re-sort.
	for i := 1; i < len(cat.Trips); i++ {
		if cat.Trips[i-1].Trip.StartedAtMillis < cat.Trips[i].Trip.StartedAtMillis {
			t.Error("trips are not sorted newest first")
		}
	}

	var a VehicleRecord
	for _, v := range cat.Vehicles {
		if v.SyncID == carA {
			a = v
		}
	}
	if a.Vehicle.Name != "Li Auto L9" {
		t.Errorf("car A name = %q", a.Vehicle.Name)
	}
	if !a.LedgerFound || len(a.Ledger.FuelEntries) != 1 {
		t.Errorf("car A ledger not read: found=%v entries=%d", a.LedgerFound, len(a.Ledger.FuelEntries))
	}
	if a.TripCount != 2 {
		t.Errorf("car A trip count = %d, want 2", a.TripCount)
	}

	trip, ok := cat.FindTrip("trip-1")
	if !ok {
		t.Fatal("trip-1 missing")
	}
	if trip.VehicleSyncID != carA {
		t.Errorf("trip-1 vehicle = %q", trip.VehicleSyncID)
	}
	if len(trip.Dtcs) != 1 || trip.Dtcs[0].Code != "P0420" {
		t.Errorf("DTCs not carried through: %+v", trip.Dtcs)
	}
	// Side-file presence comes from the manifest, not from a directory listing.
	if !trip.HasObd() || !trip.HasTrack() {
		t.Errorf("side files not detected from the manifest: %+v", trip.Files)
	}
	if trip.SyncedAtMillis == 0 {
		t.Error("syncedAtMillis not carried through - summaries would never invalidate")
	}
}

// The walk must not list each trip folder. That listing cost one extra request per trip, and
// against a throttling host (Yandex.Disk) the burst came back as 429s that silently dropped
// 131 of 157 trips from the index. One GET of trip.json per trip is all it may spend.
func TestIndexDoesNotListEachTripFolder(t *testing.T) {
	start := time.Date(2026, 1, 2, 10, 0, 0, 0, time.UTC).UnixMilli()
	files := map[string]string{carA + "/vehicle.json": vehicleJSON(carA, "Car")}
	const tripCount = 12
	for i := 0; i < tripCount; i++ {
		id := "trip-" + strconv.Itoa(i)
		files[carA+"/trips/2026/01/"+id+"/trip.json"] = tripJSON(id, carA, start)
		files[carA+"/trips/2026/01/"+id+"/obd.csv.gz"] = gz(obdCSV)
	}
	tree := &davTree{files: files}
	cat := buildNow(t, newTestIndex(t, tree))

	if len(cat.Trips) != tripCount {
		t.Fatalf("got %d trips, want %d", len(cat.Trips), tripCount)
	}
	for _, p := range tree.propfinds {
		if strings.Contains(p, "/trip-") {
			t.Errorf("walk issued a PROPFIND on a trip folder: %s", p)
		}
	}
	// Expected listings: root, the car, trips/, the year, the month. Nothing per trip.
	if len(tree.propfinds) > 6 {
		t.Errorf("walk issued %d PROPFINDs for %d trips: %v", len(tree.propfinds), tripCount, tree.propfinds)
	}
}

// A throttled server must not cost trips. Retry with backoff is what turns a 429 into a short
// delay instead of a silently missing drive.
func TestIndexSurvivesThrottling(t *testing.T) {
	start := time.Date(2026, 1, 2, 10, 0, 0, 0, time.UTC).UnixMilli()
	files := map[string]string{carA + "/vehicle.json": vehicleJSON(carA, "Car")}
	const tripCount = 6
	for i := 0; i < tripCount; i++ {
		id := "trip-" + strconv.Itoa(i)
		files[carA+"/trips/2026/01/"+id+"/trip.json"] = tripJSON(id, carA, start)
	}
	// Throttle the opening requests, including the root listing, so retry is exercised on
	// both PROPFIND and GET paths.
	tree := &davTree{files: files, throttleFirst: 5}
	cat := buildNow(t, newTestIndexWithRetries(t, tree, 5))

	if len(cat.Trips) != tripCount {
		t.Fatalf("got %d trips, want all %d despite the 429s: warnings=%v",
			len(cat.Trips), tripCount, cat.Warnings)
	}
	if len(cat.Warnings) != 0 {
		t.Errorf("retried throttles should not surface as warnings: %v", cat.Warnings)
	}
}

// A tombstone means the user deleted the trip on another device. Even if the folder is still
// present (the purge is a separate step), it must not come back in the viewer.
func TestIndexHonoursTombstones(t *testing.T) {
	start := time.Date(2026, 1, 2, 10, 0, 0, 0, time.UTC).UnixMilli()
	tree := &davTree{files: map[string]string{
		carA + "/vehicle.json":                      vehicleJSON(carA, "Car"),
		carA + "/trips/2026/01/trip-live/trip.json": tripJSON("trip-live", carA, start),
		carA + "/trips/2026/01/trip-dead/trip.json": tripJSON("trip-dead", carA, start),
		carA + "/tombstones/trip-dead":              "",
	}}
	cat := buildNow(t, newTestIndex(t, tree))
	if len(cat.Trips) != 1 {
		t.Fatalf("got %d trips, want 1", len(cat.Trips))
	}
	if cat.Trips[0].TripSyncID != "trip-live" {
		t.Errorf("kept %q, want trip-live", cat.Trips[0].TripSyncID)
	}
}

// trip.json is written last, so a folder without it is an upload that never finished. Its side
// files carry no aggregates or identity, so including it would put a blank row in the list.
func TestIndexSkipsIncompleteUploads(t *testing.T) {
	tree := &davTree{files: map[string]string{
		carA + "/vehicle.json":                       vehicleJSON(carA, "Car"),
		carA + "/trips/2026/01/half-done/obd.csv.gz": gz(obdCSV),
	}}
	cat := buildNow(t, newTestIndex(t, tree))
	if len(cat.Trips) != 0 {
		t.Errorf("got %d trips, want none", len(cat.Trips))
	}
}

func TestSummariseDerivesTripMetrics(t *testing.T) {
	start := time.Date(2026, 1, 2, 10, 0, 0, 0, time.UTC).UnixMilli()
	tree := &davTree{files: map[string]string{
		carA + "/vehicle.json":                      vehicleJSON(carA, "Car"),
		carA + "/trips/2026/01/trip-1/trip.json":    tripJSON("trip-1", carA, start),
		carA + "/trips/2026/01/trip-1/obd.csv.gz":   gz(obdCSV),
		carA + "/trips/2026/01/trip-1/track.gpx.gz": gz(trackGPX),
	}}
	ix := newTestIndex(t, tree)
	cat := buildNow(t, ix)
	trip := cat.Trips[0]

	sum, err := ix.Summarise(context.Background(), trip)
	if err != nil {
		t.Fatal(err)
	}
	if sum.AmbientTempC == nil || *sum.AmbientTempC != 7 {
		t.Errorf("ambient = %v, want 7", sum.AmbientTempC)
	}
	if sum.SocStartPct == nil || *sum.SocStartPct != 80 {
		t.Errorf("SoC start = %v, want 80", sum.SocStartPct)
	}
	if sum.SocEndPct == nil || *sum.SocEndPct != 78 {
		t.Errorf("SoC end = %v, want 78", sum.SocEndPct)
	}
	// 0.01 degrees of latitude is about 1.11 km.
	if sum.GpsDistanceKm == nil || *sum.GpsDistanceKm < 1.0 || *sum.GpsDistanceKm > 1.3 {
		t.Errorf("GPS distance = %v, want about 1.11 km", sum.GpsDistanceKm)
	}
	if sum.ElevationGainM == nil || *sum.ElevationGainM != 40 {
		t.Errorf("elevation gain = %v, want 40 (only the positive delta)", sum.ElevationGainM)
	}
	if len(sum.Stats) != 3 {
		t.Errorf("got %d stats, want 3", len(sum.Stats))
	}

	// A second call must come from the cache, not the network.
	before := len(tree.gets)
	if _, err := ix.Summarise(context.Background(), trip); err != nil {
		t.Fatal(err)
	}
	if len(tree.gets) != before {
		t.Errorf("re-summarising issued %d more GETs, want 0", len(tree.gets)-before)
	}
}

// Re-indexing an unchanged tree must not re-download payloads: that is what makes opening the
// viewer cheap once the data is local.
func TestCacheServesRepeatFetchesWithoutNetwork(t *testing.T) {
	tree := &davTree{files: map[string]string{
		carA + "/vehicle.json":               vehicleJSON(carA, "Car"),
		carA + "/trips/2026/01/t/trip.json":  tripJSON("t", carA, 1767348000000),
		carA + "/trips/2026/01/t/obd.csv.gz": gz(obdCSV),
	}}
	ix := newTestIndex(t, tree)
	cat := buildNow(t, ix)

	if _, err := ix.OBDFrame(context.Background(), cat.Trips[0]); err != nil {
		t.Fatal(err)
	}
	first := len(tree.gets)
	if _, err := ix.OBDFrame(context.Background(), cat.Trips[0]); err != nil {
		t.Fatal(err)
	}
	if len(tree.gets) != first {
		t.Errorf("second OBD read hit the network %d times, want 0", len(tree.gets)-first)
	}
}

// A car folder that cannot be read must be reported, not silently dropped: a partial index
// that presents itself as complete is the failure mode this project exists to avoid.
func TestIndexRecordsWarningsForUnreadableCars(t *testing.T) {
	tree := &davTree{files: map[string]string{
		carA + "/vehicle.json": `{ this is not json`,
	}}
	cat := buildNow(t, newTestIndex(t, tree))
	if len(cat.Vehicles) != 0 {
		t.Errorf("got %d cars, want none", len(cat.Vehicles))
	}
	if len(cat.Warnings) == 0 {
		t.Error("a car that failed to parse must produce a warning")
	}
}

// The index no longer learns side-file sizes from a listing, so a freshly-indexed trip reports
// size 0 until something downloads it. Existence must therefore never be inferred from size:
// doing so hid the GPX and OBD download buttons on every not-yet-downloaded trip.
func TestSideFilesExistIndependentlyOfKnownSize(t *testing.T) {
	start := time.Date(2026, 1, 2, 10, 0, 0, 0, time.UTC).UnixMilli()
	tree := &davTree{files: map[string]string{
		carA + "/vehicle.json":                      vehicleJSON(carA, "Car"),
		carA + "/trips/2026/01/trip-1/trip.json":    tripJSON("trip-1", carA, start),
		carA + "/trips/2026/01/trip-1/obd.csv.gz":   gz(obdCSV),
		carA + "/trips/2026/01/trip-1/track.gpx.gz": gz(trackGPX),
	}}
	ix := newTestIndex(t, tree)
	cat := buildNow(t, ix)
	trip := cat.Trips[0]

	if trip.ObdSize != 0 || trip.GpxSize != 0 {
		t.Errorf("sizes should be unknown before download, got obd=%d gpx=%d", trip.ObdSize, trip.GpxSize)
	}
	if !trip.HasObd() || !trip.HasTrack() {
		t.Fatal("side files must be reported as present from the manifest, with no size known")
	}

	// Once summarised, the sizes are recorded and become available to the UI.
	sum, err := ix.Summarise(context.Background(), trip)
	if err != nil {
		t.Fatal(err)
	}
	if sum.ObdSize == 0 || sum.GpxSize == 0 {
		t.Errorf("summary should record the downloaded sizes, got obd=%d gpx=%d", sum.ObdSize, sum.GpxSize)
	}
}
