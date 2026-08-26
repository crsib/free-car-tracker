package series

import (
	"math"
	"strings"
	"testing"
)

// A CSV in exactly the shape ObdCsvExporter writes: the timestamp column first, the standard
// ObdSample columns next, then pivoted extended metrics as metricKey[UNIT]. Blank cells mean
// "the adapter did not answer", which must survive as NaN rather than becoming zero.
const sampleCSV = `timestampMillis,speedKmh,rpm,coolantTempC,EV_SOC[PCT],MOTOR_TEMP[C]
1000,0,800,20,80,45
2000,25.5,1400,,79.5,46
3000,,,35,79,47
4000,60,2200,40,78.5,48
`

func TestParseOBDKeepsBlanksAsGaps(t *testing.T) {
	f, err := ParseOBD([]byte(sampleCSV))
	if err != nil {
		t.Fatal(err)
	}
	if len(f.Timestamps) != 4 {
		t.Fatalf("got %d rows, want 4", len(f.Timestamps))
	}
	if len(f.Columns) != 5 {
		t.Fatalf("got %d columns, want 5", len(f.Columns))
	}

	byName := map[string]Column{}
	for _, c := range f.Columns {
		byName[c.Name] = c
	}

	speed := byName["speedKmh"]
	if speed.Unit != "km/h" {
		t.Errorf("speed unit = %q, want km/h", speed.Unit)
	}
	if speed.Extended {
		t.Error("a standard column must not be flagged extended")
	}
	if !math.IsNaN(speed.Values[2]) {
		t.Errorf("blank cell became %v, want NaN - a gap is not a zero reading", speed.Values[2])
	}
	if speed.Values[3] != 60 {
		t.Errorf("speed[3] = %v, want 60", speed.Values[3])
	}

	soc := byName["EV_SOC"]
	if !soc.Extended {
		t.Error("EV_SOC[PCT] should be flagged extended")
	}
	if soc.Unit != "PCT" {
		t.Errorf("EV_SOC unit = %q, want PCT (read from the header)", soc.Unit)
	}
}

func TestParseOBDRejectsForeignCSV(t *testing.T) {
	if _, err := ParseOBD([]byte("date,value\n1,2\n")); err == nil {
		t.Error("a CSV that is not one of our exports should be rejected, not silently mis-parsed")
	}
}

func TestStatsIgnoreGaps(t *testing.T) {
	f, err := ParseOBD([]byte(sampleCSV))
	if err != nil {
		t.Fatal(err)
	}
	stats := map[string]Stat{}
	for _, s := range f.Stats() {
		stats[s.Name] = s
	}
	speed := stats["speedKmh"]
	if speed.Count != 3 {
		t.Errorf("speed count = %d, want 3 (the blank row excluded)", speed.Count)
	}
	if speed.Min != 0 || speed.Max != 60 {
		t.Errorf("speed range = [%v, %v], want [0, 60]", speed.Min, speed.Max)
	}
	want := (0 + 25.5 + 60) / 3
	if math.Abs(speed.Mean-want) > 1e-9 {
		t.Errorf("speed mean = %v, want %v", speed.Mean, want)
	}
	// First and Last must skip gaps too - they seed the SoC start/end used by analysis.
	if speed.First != 0 || speed.Last != 60 {
		t.Errorf("speed first/last = %v/%v, want 0/60", speed.First, speed.Last)
	}
}

// The whole point of min/max bucketing is that a one-sample spike survives decimation. A
// stride sampler would drop it, and the chart would lie about the trip's peak.
func TestDecimatePreservesSpikes(t *testing.T) {
	var b strings.Builder
	b.WriteString("timestampMillis,rpm\n")
	const n = 2000
	for i := 0; i < n; i++ {
		v := 1000
		if i == 977 {
			v = 6500 // a single-sample peak, deliberately not on any round stride
		}
		b.WriteString(itoa(int64(i*1000)) + "," + itoa(int64(v)) + "\n")
	}
	f, err := ParseOBD([]byte(b.String()))
	if err != nil {
		t.Fatal(err)
	}

	small := f.Decimate(100)
	if len(small.Timestamps) > 100 {
		t.Errorf("decimated to %d points, want at most 100", len(small.Timestamps))
	}
	peak := math.Inf(-1)
	for _, v := range small.Columns[0].Values {
		if !math.IsNaN(v) && v > peak {
			peak = v
		}
	}
	if peak != 6500 {
		t.Errorf("peak after decimation = %v, want the original 6500", peak)
	}

	// Timestamps must stay sorted, or the chart draws lines backwards through time.
	for i := 1; i < len(small.Timestamps); i++ {
		if small.Timestamps[i] < small.Timestamps[i-1] {
			t.Fatalf("timestamps out of order at %d", i)
		}
	}
}

func TestDecimateIsANoOpWhenSmallEnough(t *testing.T) {
	f, _ := ParseOBD([]byte(sampleCSV))
	if got := f.Decimate(1000); len(got.Timestamps) != 4 {
		t.Errorf("got %d points, want the original 4", len(got.Timestamps))
	}
}

func TestWindowAndSelect(t *testing.T) {
	f, _ := ParseOBD([]byte(sampleCSV))

	sel := f.Select([]string{"EV_SOC", "speedKmh"})
	if len(sel.Columns) != 2 {
		t.Fatalf("got %d columns, want 2", len(sel.Columns))
	}
	if sel.Columns[0].Name != "EV_SOC" {
		t.Errorf("Select must honour the requested order, got %q first", sel.Columns[0].Name)
	}

	win := f.Window(2000, 3000)
	if len(win.Timestamps) != 2 {
		t.Fatalf("window got %d rows, want 2", len(win.Timestamps))
	}
	if win.Timestamps[0] != 2000 || win.Timestamps[1] != 3000 {
		t.Errorf("window bounds are inclusive: got %v", win.Timestamps)
	}
	if len(f.Window(0, 0).Timestamps) != 4 {
		t.Error("zero bounds mean open, so the whole frame should come back")
	}
	if len(f.Window(99000, 99999).Timestamps) != 0 {
		t.Error("a window past the end should be empty")
	}
}

// A track written by GpxExporter, including the fct: extension block. Parsing must be driven
// by namespace, not by prefix, so a writer using a different prefix still round-trips.
const sampleGPX = `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="free-car-tracker" xmlns="http://www.topografix.com/GPX/1/1" xmlns:x="https://crsib.me/freecartracker/gpx/1">
<trk><name>trip-abc</name><trkseg>
<trkpt lat="52.2297" lon="21.0122"><ele>110.5</ele><time>2026-01-02T10:00:00Z</time><extensions><x:speedKmh>42.5</x:speedKmh><x:bearingDeg>180</x:bearingDeg><x:accuracyM>4.5</x:accuracyM><x:provider>fused</x:provider></extensions></trkpt>
<trkpt lat="52.2397" lon="21.0122"><ele>130.5</ele><time>2026-01-02T10:00:30Z</time><extensions><x:speedKmh>50</x:speedKmh></extensions></trkpt>
<trkpt lat="52.2497" lon="21.0122"><time>bogus</time></trkpt>
</trkseg></trk></gpx>`

func TestParseGPXReadsExtensionsByNamespace(t *testing.T) {
	pts, err := ParseGPX([]byte(sampleGPX))
	if err != nil {
		t.Fatal(err)
	}
	if len(pts) != 2 {
		t.Fatalf("got %d points, want 2 (the one with an unparseable time is skipped)", len(pts))
	}
	p := pts[0]
	if p.Latitude != 52.2297 || p.Longitude != 21.0122 {
		t.Errorf("coordinates = %v, %v", p.Latitude, p.Longitude)
	}
	if p.SpeedKmh == nil || *p.SpeedKmh != 42.5 {
		t.Errorf("speed = %v, want 42.5 - read via the x: prefix", p.SpeedKmh)
	}
	if p.AltitudeM == nil || *p.AltitudeM != 110.5 {
		t.Errorf("elevation = %v, want 110.5", p.AltitudeM)
	}
	if p.Provider != "fused" {
		t.Errorf("provider = %q, want fused", p.Provider)
	}
	if p.TimestampMillis != 1767348000000 {
		t.Errorf("timestamp = %d, want 1767348000000", p.TimestampMillis)
	}
	// A foreign GPX has no extensions at all; those fields stay nil and provider defaults.
	if pts[1].Provider != "gps" {
		t.Errorf("missing provider = %q, want the gps default", pts[1].Provider)
	}
	if pts[1].BearingDeg != nil {
		t.Error("a missing extension must stay nil, not zero")
	}
}

func TestCumulativeDistanceAndBounds(t *testing.T) {
	pts, _ := ParseGPX([]byte(sampleGPX))
	cum := CumulativeKm(pts)
	if cum[0] != 0 {
		t.Errorf("cumulative[0] = %v, want 0", cum[0])
	}
	// 0.01 degrees of latitude is ~1.11 km.
	if math.Abs(cum[1]-1.112) > 0.01 {
		t.Errorf("cumulative[1] = %v km, want about 1.112", cum[1])
	}
	b := TrackBounds(pts)
	if b == nil || b.MinLat != 52.2297 || b.MaxLat != 52.2397 {
		t.Errorf("bounds = %+v", b)
	}
	if TrackBounds(nil) != nil {
		t.Error("an empty track has no bounds")
	}
}

func TestDecimateTrackKeepsEndpoints(t *testing.T) {
	pts, _ := ParseGPX([]byte(sampleGPX))
	// Synthesise a longer track from the parsed shape.
	big := pts
	for len(big) < 500 {
		big = append(big, pts...)
	}
	out := DecimateTrack(big, 50)
	if len(out) != 50 {
		t.Fatalf("got %d points, want 50", len(out))
	}
	if out[0] != big[0] {
		t.Error("the first fix must be kept - the route has to start where the drive did")
	}
	if out[len(out)-1] != big[len(big)-1] {
		t.Error("the last fix must be kept")
	}
}

func itoa(v int64) string {
	if v == 0 {
		return "0"
	}
	var buf [24]byte
	i := len(buf)
	neg := v < 0
	if neg {
		v = -v
	}
	for v > 0 {
		i--
		buf[i] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
