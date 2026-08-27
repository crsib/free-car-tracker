package api

import (
	"archive/zip"
	"encoding/csv"
	"errors"
	"fmt"
	"math"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"crsib.me/fct-viewer/internal/domain"
	"crsib.me/fct-viewer/internal/model"
	"crsib.me/fct-viewer/internal/series"
	"crsib.me/fct-viewer/internal/store"
	"crsib.me/fct-viewer/internal/webdav"
)

type tripView struct {
	TripSyncID    string     `json:"tripSyncId"`
	VehicleSyncID string     `json:"vehicleSyncId"`
	VehicleName   string     `json:"vehicleName"`
	Trip          model.Trip `json:"trip"`
	DtcCount      int        `json:"dtcCount"`
	HasObd        bool       `json:"hasObd"`
	HasTrack      bool       `json:"hasTrack"`
	ObdSize       int64      `json:"obdSize"`
	GpxSize       int64      `json:"gpxSize"`
	Cached        bool       `json:"cached"`
}

// filterTrips applies the vehicle and date filters every trip-facing endpoint shares.
func (s *Server) filterTrips(r *http.Request, cat *store.Catalog) []store.TripRecord {
	vehicle := r.URL.Query().Get("vehicle")
	from := int64Param(r, "from", 0)
	to := int64Param(r, "to", 0)
	minKm := r.URL.Query().Get("minKm")
	var minDistance float64
	if minKm != "" {
		minDistance, _ = strconv.ParseFloat(minKm, 64)
	}

	out := make([]store.TripRecord, 0, len(cat.Trips))
	for _, t := range cat.Trips {
		if vehicle != "" && t.VehicleSyncID != vehicle {
			continue
		}
		if from != 0 && t.Trip.StartedAtMillis < from {
			continue
		}
		if to != 0 && t.Trip.StartedAtMillis > to {
			continue
		}
		if minDistance > 0 && (t.Trip.DistanceKm == nil || *t.Trip.DistanceKm < minDistance) {
			continue
		}
		out = append(out, t)
	}
	return out
}

// fuelEstimate combines the app's own aggregate, the cached OBD-derived signals, and the
// car's tank capacity into one answer. It is computed here rather than baked into the summary
// so that editing a car's tank capacity re-values every trip immediately.
func (s *Server) fuelEstimate(t store.TripRecord, v model.Vehicle, sum *store.TripSummary) domain.FuelEstimate {
	var sig domain.FuelSignals
	if sum != nil {
		sig = domain.FuelSignals{
			LitresFromRate: sum.FuelLitresFromRate,
			LitresFromMaf:  sum.FuelLitresFromMaf,
			TankStartPct:   sum.TankStartPct,
			TankEndPct:     sum.TankEndPct,
			EngineRunSec:   sum.EngineRunSec,
			EngineObserved: sum.EngineObserved,
		}
	}
	return domain.EstimateFuel(
		t.Trip.FuelUsedL,
		t.Trip.DistanceKm,
		sig,
		v.TankCapacityL,
		model.Powertrain(v.Powertrain).UsesFuel(),
	)
}

// withSizes fills in side-file byte sizes from the trip's summary when the catalog record does
// not have them yet. Size is display-only: whether a file *exists* always comes from the
// manifest, never from a size, so a not-yet-downloaded trip still offers its downloads.
func withSizes(t *store.TripRecord, ix *store.Index) {
	if t.ObdSize > 0 && t.GpxSize > 0 {
		return
	}
	sum, ok := ix.LoadSummary(*t)
	if !ok {
		return
	}
	if t.ObdSize == 0 {
		t.ObdSize = sum.ObdSize
	}
	if t.GpxSize == 0 {
		t.GpxSize = sum.GpxSize
	}
}

func (s *Server) vehicleNames(cat *store.Catalog) map[string]string {
	names := map[string]string{}
	for _, v := range cat.Vehicles {
		names[v.SyncID] = v.Vehicle.Name
	}
	return names
}

func (s *Server) handleTrips(w http.ResponseWriter, r *http.Request) {
	cat, ok := s.catalog(w)
	if !ok {
		return
	}
	names := s.vehicleNames(cat)
	trips := s.filterTrips(r, cat)

	limit := intParam(r, "limit", 0)
	offset := intParam(r, "offset", 0)
	total := len(trips)
	if offset > 0 && offset < len(trips) {
		trips = trips[offset:]
	} else if offset >= len(trips) {
		trips = nil
	}
	if limit > 0 && limit < len(trips) {
		trips = trips[:limit]
	}

	out := make([]tripView, 0, len(trips))
	for _, t := range trips {
		_, cached := s.index.LoadSummary(t)
		withSizes(&t, s.index)
		out = append(out, tripView{
			TripSyncID: t.TripSyncID, VehicleSyncID: t.VehicleSyncID,
			VehicleName: names[t.VehicleSyncID], Trip: t.Trip, DtcCount: len(t.Dtcs),
			HasObd:   t.HasObd(),
			HasTrack: t.HasTrack(),
			ObdSize:  t.ObdSize, GpxSize: t.GpxSize, Cached: cached,
		})
	}
	writeJSON(w, map[string]any{"total": total, "trips": out})
}

func (s *Server) findTrip(w http.ResponseWriter, r *http.Request) (store.TripRecord, *store.Catalog, bool) {
	cat, ok := s.catalog(w)
	if !ok {
		return store.TripRecord{}, nil, false
	}
	t, found := cat.FindTrip(r.PathValue("trip"))
	if !found {
		writeErr(w, http.StatusNotFound, "no such trip")
		return store.TripRecord{}, nil, false
	}
	return t, cat, true
}

func (s *Server) handleTrip(w http.ResponseWriter, r *http.Request) {
	t, cat, ok := s.findTrip(w, r)
	if !ok {
		return
	}
	// Sizes are learned when a side file is first downloaded, which usually happens *after*
	// the index built this record, so the catalog copy is routinely stale. The summary is the
	// authority once it exists.
	withSizes(&t, s.index)

	resp := map[string]any{
		"tripSyncId":    t.TripSyncID,
		"vehicleSyncId": t.VehicleSyncID,
		"vehicleName":   s.vehicleNames(cat)[t.VehicleSyncID],
		"trip":          t.Trip,
		"dtcs":          t.Dtcs,
		"files":         t.Files,
		"obdSize":       t.ObdSize,
		"gpxSize":       t.GpxSize,
		"remoteDir":     t.Dir,
	}
	vehicle, haveVehicle := cat.FindVehicle(t.VehicleSyncID)
	if haveVehicle {
		resp["units"] = unitsFor(vehicle.Vehicle)
		resp["vehicle"] = vehicle.Vehicle
	}
	// The metric list drives the chart's series picker, so it has to reflect what this trip
	// actually recorded - offering EV_SOC on a trip whose adapter never answered it is worse
	// than offering nothing. That means reading the OBD file, which is why it is summarised.
	if sum, err := s.index.Summarise(r.Context(), t); err == nil && sum != nil {
		resp["stats"] = sum.Stats
		resp["summary"] = sum
		if haveVehicle {
			resp["fuel"] = s.fuelEstimate(t, vehicle.Vehicle, sum)
		}
	} else if err != nil {
		resp["seriesError"] = err.Error()
	}
	writeJSON(w, resp)
}

// handleTrack returns the GPS track as flat parallel arrays. GeoJSON would be the obvious
// choice, but a 40k-fix track becomes megabytes of repeated key names; the arrays below are
// roughly a third of the size and map straight onto what the map renderer wants.
func (s *Server) handleTrack(w http.ResponseWriter, r *http.Request) {
	t, _, ok := s.findTrip(w, r)
	if !ok {
		return
	}
	pts, err := s.index.Track(r.Context(), t)
	if err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	full := len(pts)
	if max := intParam(r, "points", 4000); max > 0 {
		pts = series.DecimateTrack(pts, max)
	}

	lat := make([]float64, len(pts))
	lon := make([]float64, len(pts))
	ts := make([]int64, len(pts))
	speed := make([]*float64, len(pts))
	ele := make([]*float64, len(pts))
	for i, p := range pts {
		lat[i], lon[i], ts[i] = p.Latitude, p.Longitude, p.TimestampMillis
		speed[i], ele[i] = p.SpeedKmh, p.AltitudeM
	}
	resp := map[string]any{
		"pointCount": full, "returned": len(pts),
		"lat": lat, "lon": lon, "t": ts, "speedKmh": speed, "eleM": ele,
	}
	if b := series.TrackBounds(pts); b != nil {
		resp["bounds"] = b
	}
	if len(pts) > 1 {
		cum := series.CumulativeKm(pts)
		resp["distanceKm"] = cum[len(cum)-1]
		resp["cumKm"] = cum
	}
	writeJSON(w, resp)
}

// handleSeries returns selected OBD metrics on a shared timestamp axis, decimated to the
// requested budget. JSON cannot carry NaN, so gaps are encoded as null and the UI breaks the
// line there rather than drawing through a stretch the adapter never reported.
func (s *Server) handleSeries(w http.ResponseWriter, r *http.Request) {
	t, _, ok := s.findTrip(w, r)
	if !ok {
		return
	}
	frame, err := s.index.OBDFrame(r.Context(), t)
	if err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	if frame == nil {
		writeJSON(w, map[string]any{"t": []int64{}, "series": []any{}, "sampleCount": 0})
		return
	}
	total := len(frame.Timestamps)

	if metrics := listParam(r, "metrics"); len(metrics) > 0 {
		frame = frame.Select(metrics)
	}
	frame = frame.Window(int64Param(r, "from", 0), int64Param(r, "to", 0))
	windowed := len(frame.Timestamps)
	frame = frame.Decimate(intParam(r, "points", 3000))

	type seriesOut struct {
		Name     string     `json:"name"`
		Unit     string     `json:"unit,omitempty"`
		Extended bool       `json:"extended"`
		Values   []*float64 `json:"values"`
	}
	out := make([]seriesOut, 0, len(frame.Columns))
	for _, c := range frame.Columns {
		vals := make([]*float64, len(c.Values))
		for i, v := range c.Values {
			if !math.IsNaN(v) {
				val := v
				vals[i] = &val
			}
		}
		out = append(out, seriesOut{Name: c.Name, Unit: c.Unit, Extended: c.Extended, Values: vals})
	}
	writeJSON(w, map[string]any{
		"t": frame.Timestamps, "series": out,
		"sampleCount": total, "windowCount": windowed, "returned": len(frame.Timestamps),
	})
}

// handleTripFile serves one of a trip's files. By default the .gz files are decompressed, so
// a browser download gives you an obd.csv you can open in a spreadsheet and a track.gpx any
// mapping tool will read; ?raw=1 hands back the exact bytes stored on the server instead.
func (s *Server) handleTripFile(w http.ResponseWriter, r *http.Request) {
	t, _, ok := s.findTrip(w, r)
	if !ok {
		return
	}
	name := r.PathValue("name")
	raw := r.URL.Query().Get("raw") == "1"

	data, err := s.index.RawFile(r.Context(), t, name)
	if err != nil {
		code := http.StatusBadGateway
		switch {
		case errors.Is(err, store.ErrUnknownFile):
			code = http.StatusBadRequest
		case errors.Is(err, webdav.ErrNotFound):
			code = http.StatusNotFound
		}
		writeErr(w, code, err.Error())
		return
	}

	filename := tripFilename(t, name, raw)
	contentType := "application/octet-stream"
	if !raw && strings.HasSuffix(name, ".gz") {
		plain, err := webdav.Gunzip(data)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "decompress "+name+": "+err.Error())
			return
		}
		data = plain
	}
	switch {
	case strings.HasSuffix(filename, ".csv"):
		contentType = "text/csv; charset=utf-8"
	case strings.HasSuffix(filename, ".gpx"):
		contentType = "application/gpx+xml"
	case strings.HasSuffix(filename, ".json"):
		contentType = "application/json; charset=utf-8"
	case strings.HasSuffix(filename, ".gz"):
		contentType = "application/gzip"
	}
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.Header().Set("Content-Disposition", `attachment; filename="`+filename+`"`)
	_, _ = w.Write(data)
}

// tripFilename names a download after the trip's start time rather than after its UUID, so a
// folder of exports sorts chronologically and each file says what it is on sight.
func tripFilename(t store.TripRecord, name string, raw bool) string {
	stamp := time.UnixMilli(t.Trip.StartedAtMillis).Format("2006-01-02_1504")
	short := t.TripSyncID
	if len(short) > 8 {
		short = short[:8]
	}
	base := fmt.Sprintf("trip_%s_%s", stamp, short)
	switch {
	case name == store.TripJSON:
		return base + ".json"
	case name == store.ObdCsvGz && raw:
		return base + "_obd.csv.gz"
	case name == store.ObdCsvGz:
		return base + "_obd.csv"
	case name == store.TrackGpxGz && raw:
		return base + "_track.gpx.gz"
	case name == store.TrackGpxGz:
		return base + "_track.gpx"
	}
	return base + "_" + name
}

// --- Cross-trip analysis ---

// analysisRow is one trip reduced to the numbers a comparison view plots against each other.
// Everything here comes from the cached summary, so a query over 500 trips touches no network.
type analysisRow struct {
	TripSyncID    string   `json:"tripSyncId"`
	VehicleSyncID string   `json:"vehicleSyncId"`
	VehicleName   string   `json:"vehicleName"`
	StartedAt     int64    `json:"startedAtMillis"`
	DurationSec   *int64   `json:"durationSec"`
	DistanceKm    *float64 `json:"distanceKm"`
	AvgSpeedKmh   *float64 `json:"avgSpeedKmh"`
	MaxSpeedKmh   *float64 `json:"maxSpeedKmh"`
	FuelUsedL     *float64 `json:"fuelUsedL"`
	EnergyUsedKwh *float64 `json:"energyUsedKwh"`
	L100          *float64 `json:"avgConsumptionL100"`
	// Estimated fuel, from whatever the car reported. Distinct from FuelUsedL/L100 above,
	// which are only set when the app itself managed to integrate a fuel rate - true for a
	// small minority of trips.
	FuelLitres     *float64 `json:"fuelLitres"`
	FuelL100       *float64 `json:"fuelL100"`
	FuelMethod     string   `json:"fuelMethod,omitempty"`
	FuelConfidence string   `json:"fuelConfidence,omitempty"`
	Kwh100         *float64 `json:"kwhPer100Km"`
	AmbientTempC   *float64 `json:"ambientTempC"`
	ElevationGainM *float64 `json:"elevationGainM"`
	SocStartPct    *float64 `json:"socStartPct"`
	SocEndPct      *float64 `json:"socEndPct"`
	SocUsedPct     *float64 `json:"socUsedPct"`
	GpsDistanceKm  *float64 `json:"gpsDistanceKm"`
	DtcCount       int      `json:"dtcCount"`
	Summarised     bool     `json:"summarised"`
	// Metrics carries every OBD column mean, so the scatter view can offer any recorded
	// metric as an axis without the server needing to know what a given car reports.
	Metrics map[string]float64 `json:"metrics,omitempty"`
}

func (s *Server) analysisRows(r *http.Request, cat *store.Catalog) []analysisRow {
	names := s.vehicleNames(cat)
	vehicles := map[string]model.Vehicle{}
	for _, v := range cat.Vehicles {
		vehicles[v.SyncID] = v.Vehicle
	}
	trips := s.filterTrips(r, cat)
	rows := make([]analysisRow, 0, len(trips))

	for _, t := range trips {
		row := analysisRow{
			TripSyncID: t.TripSyncID, VehicleSyncID: t.VehicleSyncID,
			VehicleName: names[t.VehicleSyncID], StartedAt: t.Trip.StartedAtMillis,
			DurationSec: t.Trip.DurationSec, DistanceKm: t.Trip.DistanceKm,
			AvgSpeedKmh: t.Trip.AvgSpeedKmh, MaxSpeedKmh: t.Trip.MaxSpeedKmh,
			FuelUsedL: t.Trip.FuelUsedL, EnergyUsedKwh: t.Trip.EnergyUsedKwh,
			L100: t.Trip.AvgConsumptionL100, DtcCount: len(t.Dtcs),
		}
		if t.Trip.EnergyUsedKwh != nil && t.Trip.DistanceKm != nil && *t.Trip.DistanceKm > 0 {
			v := *t.Trip.EnergyUsedKwh / *t.Trip.DistanceKm * 100
			row.Kwh100 = &v
		}
		if v, ok := vehicles[t.VehicleSyncID]; ok {
			sum, _ := s.index.LoadSummary(t)
			fuel := s.fuelEstimate(t, v, sum)
			row.FuelLitres, row.FuelL100 = fuel.Litres, fuel.L100
			row.FuelMethod, row.FuelConfidence = fuel.Method, fuel.Confidence
		}
		// Summaries are read from cache only: this endpoint must stay instant, and a trip
		// that has not been prefetched simply reports the aggregates from its trip.json.
		if sum, ok := s.index.LoadSummary(t); ok {
			row.Summarised = true
			row.AmbientTempC = sum.AmbientTempC
			row.ElevationGainM = sum.ElevationGainM
			row.SocStartPct, row.SocEndPct = sum.SocStartPct, sum.SocEndPct
			row.GpsDistanceKm = sum.GpsDistanceKm
			if sum.SocStartPct != nil && sum.SocEndPct != nil {
				used := *sum.SocStartPct - *sum.SocEndPct
				row.SocUsedPct = &used
			}
			if len(sum.Stats) > 0 {
				row.Metrics = make(map[string]float64, len(sum.Stats))
				for _, st := range sum.Stats {
					row.Metrics[st.Name] = st.Mean
				}
			}
		}
		rows = append(rows, row)
	}
	sort.SliceStable(rows, func(i, j int) bool { return rows[i].StartedAt > rows[j].StartedAt })
	return rows
}

func (s *Server) handleAnalysisTrips(w http.ResponseWriter, r *http.Request) {
	cat, ok := s.catalog(w)
	if !ok {
		return
	}
	rows := s.analysisRows(r, cat)
	// Report how much of the answer is backed by full time-series data, so a half-prefetched
	// set cannot be mistaken for a complete one.
	summarised := 0
	metricSet := map[string]bool{}
	for _, row := range rows {
		if row.Summarised {
			summarised++
		}
		for k := range row.Metrics {
			metricSet[k] = true
		}
	}
	metrics := make([]string, 0, len(metricSet))
	for k := range metricSet {
		metrics = append(metrics, k)
	}
	sort.Strings(metrics)
	writeJSON(w, map[string]any{
		"rows": rows, "total": len(rows), "summarised": summarised, "metrics": metrics,
	})
}

func (s *Server) handleAnalysisTripsCSV(w http.ResponseWriter, r *http.Request) {
	cat, ok := s.catalog(w)
	if !ok {
		return
	}
	rows := s.analysisRows(r, cat)

	// A stable column set beats a ragged one: collect every metric present across the whole
	// result, then emit blanks where a trip did not record it.
	metricSet := map[string]bool{}
	for _, row := range rows {
		for k := range row.Metrics {
			metricSet[k] = true
		}
	}
	metrics := make([]string, 0, len(metricSet))
	for k := range metricSet {
		metrics = append(metrics, k)
	}
	sort.Strings(metrics)

	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="car-tracker-trips.csv"`)
	cw := csv.NewWriter(w)
	defer cw.Flush()

	header := []string{
		"tripSyncId", "vehicle", "startedAt", "startedAtMillis", "durationSec", "distanceKm",
		"gpsDistanceKm", "avgSpeedKmh", "maxSpeedKmh", "fuelUsedL", "avgConsumptionL100",
		"fuelLitres", "fuelL100", "fuelMethod", "fuelConfidence",
		"energyUsedKwh", "kwhPer100Km", "ambientTempC", "elevationGainM", "socStartPct",
		"socEndPct", "socUsedPct", "dtcCount", "summarised",
	}
	_ = cw.Write(append(header, metrics...))

	for _, row := range rows {
		rec := []string{
			row.TripSyncID, row.VehicleName,
			time.UnixMilli(row.StartedAt).Format(time.RFC3339),
			strconv.FormatInt(row.StartedAt, 10),
			fmtInt64Ptr(row.DurationSec), fmtPtr(row.DistanceKm), fmtPtr(row.GpsDistanceKm),
			fmtPtr(row.AvgSpeedKmh), fmtPtr(row.MaxSpeedKmh), fmtPtr(row.FuelUsedL),
			fmtPtr(row.L100),
			fmtPtr(row.FuelLitres), fmtPtr(row.FuelL100), row.FuelMethod, row.FuelConfidence,
			fmtPtr(row.EnergyUsedKwh), fmtPtr(row.Kwh100),
			fmtPtr(row.AmbientTempC), fmtPtr(row.ElevationGainM), fmtPtr(row.SocStartPct),
			fmtPtr(row.SocEndPct), fmtPtr(row.SocUsedPct),
			strconv.Itoa(row.DtcCount), strconv.FormatBool(row.Summarised),
		}
		for _, m := range metrics {
			if v, ok := row.Metrics[m]; ok {
				rec = append(rec, strconv.FormatFloat(v, 'f', -1, 64))
			} else {
				rec = append(rec, "")
			}
		}
		_ = cw.Write(rec)
	}
}

func fmtInt64Ptr(p *int64) string {
	if p == nil {
		return ""
	}
	return strconv.FormatInt(*p, 10)
}

// handleExportZip streams a zip of raw trip files. It is written directly to the response as
// it is built, so exporting 200 trips never materialises a multi-gigabyte archive in memory.
func (s *Server) handleExportZip(w http.ResponseWriter, r *http.Request) {
	cat, ok := s.catalog(w)
	if !ok {
		return
	}
	trips := s.filterTrips(r, cat)
	if len(trips) == 0 {
		writeErr(w, http.StatusNotFound, "no trips match that filter")
		return
	}
	want := map[string]bool{}
	for _, f := range listParam(r, "files") {
		want[f] = true
	}
	if len(want) == 0 {
		want["gpx"] = true
	}
	raw := r.URL.Query().Get("raw") == "1"

	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", `attachment; filename="car-tracker-trips.zip"`)

	zw := zip.NewWriter(w)
	defer zw.Close()

	names := s.vehicleNames(cat)
	add := func(t store.TripRecord, remote string) error {
		data, err := s.index.RawFile(r.Context(), t, remote)
		if err != nil {
			return err
		}
		if !raw && strings.HasSuffix(remote, ".gz") {
			if plain, err := webdav.Gunzip(data); err == nil {
				data = plain
			}
		}
		car := names[t.VehicleSyncID]
		if car == "" {
			car = t.VehicleSyncID
		}
		entry, err := zw.Create(sanitizeZipDir(car) + "/" + tripFilename(t, remote, raw))
		if err != nil {
			return err
		}
		_, err = entry.Write(data)
		return err
	}

	for _, t := range trips {
		if r.Context().Err() != nil {
			return // client went away mid-download
		}
		if want["json"] {
			_ = add(t, store.TripJSON)
		}
		if want["gpx"] && (t.GpxSize > 0 || t.Files.GpxGz != nil) {
			_ = add(t, store.TrackGpxGz)
		}
		if want["obd"] && (t.ObdSize > 0 || t.Files.ObdCsvGz != nil) {
			_ = add(t, store.ObdCsvGz)
		}
	}
}

// sanitizeZipDir keeps a car name usable as a folder inside the archive on any OS.
func sanitizeZipDir(name string) string {
	replacer := strings.NewReplacer("/", "-", "\\", "-", ":", "-", "*", "-", "?", "-",
		`"`, "-", "<", "-", ">", "-", "|", "-")
	cleaned := strings.TrimSpace(replacer.Replace(name))
	if cleaned == "" {
		return "car"
	}
	return cleaned
}
