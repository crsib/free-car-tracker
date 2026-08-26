// Package series parses the trip side files - obd.csv (from obd.csv.gz) and track.gpx
// (from track.gpx.gz) - and decimates long series down to something a browser can draw.
package series

import (
	"bytes"
	"encoding/csv"
	"errors"
	"math"
	"sort"
	"strconv"
	"strings"
)

// Column is one metric across a trip. Values is index-aligned with Frame.Timestamps and uses
// NaN for a blank cell, because the exporter writes blank for null (not zero) and collapsing
// that distinction would invent readings the car never reported.
type Column struct {
	Name     string    `json:"name"`
	Unit     string    `json:"unit,omitempty"`
	Extended bool      `json:"extended"`
	Values   []float64 `json:"-"`
}

// Frame is a parsed obd.csv: a shared timestamp axis plus one Column per metric.
type Frame struct {
	Timestamps []int64
	Columns    []Column
}

// standardUnits labels the fixed ObdSample columns (ObdCsvSchema.standard). Extended columns
// carry their unit in the header as metricKey[UNIT], so they are read from the file instead.
var standardUnits = map[string]string{
	"speedKmh": "km/h", "rpm": "rpm", "throttlePct": "%", "engineLoadPct": "%",
	"runTimeSec": "s", "coolantTempC": "°C", "timingAdvanceDeg": "°",
	"intakeAirTempC": "°C", "ambientAirTempC": "°C", "mafGramsPerSec": "g/s",
	"manifoldPressureKpa": "kPa", "controlModuleVoltage": "V", "fuelLevelPct": "%",
	"fuelRateLph": "L/h", "derivedL100": "L/100km", "derivedCo2Gkm": "g/km",
	"odometerKm": "km", "hybridBatteryLifePct": "%", "hvBatteryVoltage": "V",
	"hvBatteryCurrent": "A", "hvBatteryPowerKw": "kW",
}

// parseExtHeader splits a metricKey[unit] header, matching ObdCsvSchema.parseExtHeader.
func parseExtHeader(h string) (key, unit string, ok bool) {
	open := strings.LastIndex(h, "[")
	if open <= 0 || !strings.HasSuffix(h, "]") {
		return "", "", false
	}
	return h[:open], h[open+1 : len(h)-1], true
}

// ErrBadHeader means the file is not one of our OBD exports.
var ErrBadHeader = errors.New("obd csv: first column is not timestampMillis")

// ParseOBD reads an obd.csv body. The header defines the schema - standard ObdSample columns
// first, then one pivoted column per extended metricKey - so a file written by a newer app
// version with extra metrics still parses, its unknown columns simply carried through.
func ParseOBD(body []byte) (*Frame, error) {
	r := csv.NewReader(bytes.NewReader(body))
	r.FieldsPerRecord = -1 // tolerate a short trailing row from a truncated upload
	r.ReuseRecord = true

	header, err := r.Read()
	if err != nil {
		return nil, err
	}
	if len(header) == 0 || header[0] != "timestampMillis" {
		return nil, ErrBadHeader
	}

	cols := make([]Column, len(header)-1)
	for i, h := range header[1:] {
		if key, unit, ok := parseExtHeader(h); ok {
			cols[i] = Column{Name: key, Unit: unit, Extended: true}
		} else {
			cols[i] = Column{Name: h, Unit: standardUnits[h]}
		}
	}

	f := &Frame{Columns: cols}
	for {
		rec, err := r.Read()
		if err != nil {
			break // io.EOF, or a malformed tail we simply stop at
		}
		if len(rec) == 0 || rec[0] == "" {
			continue
		}
		ts, err := strconv.ParseInt(rec[0], 10, 64)
		if err != nil {
			continue
		}
		f.Timestamps = append(f.Timestamps, ts)
		for i := range f.Columns {
			v := math.NaN()
			if i+1 < len(rec) && rec[i+1] != "" {
				if parsed, err := strconv.ParseFloat(rec[i+1], 64); err == nil {
					v = parsed
				}
			}
			f.Columns[i].Values = append(f.Columns[i].Values, v)
		}
	}
	return f, nil
}

// Stat summarises one column over a trip. Count excludes NaN, so a column the adapter never
// answered reports Count 0 rather than a misleading zero mean.
type Stat struct {
	Name     string  `json:"name"`
	Unit     string  `json:"unit,omitempty"`
	Extended bool    `json:"extended"`
	Count    int     `json:"count"`
	Min      float64 `json:"min"`
	Max      float64 `json:"max"`
	Mean     float64 `json:"mean"`
	First    float64 `json:"first"`
	Last     float64 `json:"last"`
}

// Stats summarises every column that has at least one reading.
func (f *Frame) Stats() []Stat {
	out := make([]Stat, 0, len(f.Columns))
	for _, c := range f.Columns {
		s := Stat{
			Name: c.Name, Unit: c.Unit, Extended: c.Extended,
			Min: math.Inf(1), Max: math.Inf(-1), First: math.NaN(), Last: math.NaN(),
		}
		sum := 0.0
		for _, v := range c.Values {
			if math.IsNaN(v) {
				continue
			}
			if s.Count == 0 {
				s.First = v
			}
			s.Last = v
			s.Count++
			sum += v
			if v < s.Min {
				s.Min = v
			}
			if v > s.Max {
				s.Max = v
			}
		}
		if s.Count == 0 {
			continue
		}
		s.Mean = sum / float64(s.Count)
		out = append(out, s)
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}

// Names lists every column present, in file order.
func (f *Frame) Names() []string {
	out := make([]string, len(f.Columns))
	for i, c := range f.Columns {
		out[i] = c.Name
	}
	return out
}

// Select returns a sub-frame with only the named columns, in the order requested.
func (f *Frame) Select(names []string) *Frame {
	byName := make(map[string]Column, len(f.Columns))
	for _, c := range f.Columns {
		byName[c.Name] = c
	}
	out := &Frame{Timestamps: f.Timestamps}
	for _, n := range names {
		if c, ok := byName[n]; ok {
			out.Columns = append(out.Columns, c)
		}
	}
	return out
}

// Window narrows the frame to [fromMillis, toMillis]. Zero bounds mean "open".
func (f *Frame) Window(fromMillis, toMillis int64) *Frame {
	lo, hi := 0, len(f.Timestamps)
	if fromMillis > 0 {
		lo = sort.Search(len(f.Timestamps), func(i int) bool { return f.Timestamps[i] >= fromMillis })
	}
	if toMillis > 0 {
		hi = sort.Search(len(f.Timestamps), func(i int) bool { return f.Timestamps[i] > toMillis })
	}
	if lo >= hi {
		return &Frame{}
	}
	out := &Frame{Timestamps: f.Timestamps[lo:hi]}
	for _, c := range f.Columns {
		out.Columns = append(out.Columns, Column{
			Name: c.Name, Unit: c.Unit, Extended: c.Extended, Values: c.Values[lo:hi],
		})
	}
	return out
}

// Decimate reduces the frame to at most maxPoints samples while keeping every spike visible.
//
// A plain "take every Nth row" would drop the one sample where RPM hit its peak - exactly the
// event worth seeing. Instead each bucket contributes two samples, the bucket minimum and the
// bucket maximum, emitted in the order they actually occurred. The drawn line then spans the
// true range of the bucket, so peaks and dropouts survive at any zoom level. Every column
// shares the bucket boundaries, so the result keeps one common timestamp axis.
func (f *Frame) Decimate(maxPoints int) *Frame {
	n := len(f.Timestamps)
	if maxPoints <= 0 || n <= maxPoints {
		return f
	}
	buckets := maxPoints / 2
	if buckets < 1 {
		buckets = 1
	}

	out := &Frame{Timestamps: make([]int64, 0, buckets*2)}
	for i := range f.Columns {
		out.Columns = append(out.Columns, Column{
			Name: f.Columns[i].Name, Unit: f.Columns[i].Unit, Extended: f.Columns[i].Extended,
			Values: make([]float64, 0, buckets*2),
		})
	}

	for b := 0; b < buckets; b++ {
		lo := b * n / buckets
		hi := (b + 1) * n / buckets
		if lo >= hi {
			continue
		}
		out.Timestamps = append(out.Timestamps, f.Timestamps[lo], f.Timestamps[hi-1])
		for ci, c := range f.Columns {
			minI, maxI := -1, -1
			for i := lo; i < hi; i++ {
				v := c.Values[i]
				if math.IsNaN(v) {
					continue
				}
				if minI < 0 || v < c.Values[minI] {
					minI = i
				}
				if maxI < 0 || v > c.Values[maxI] {
					maxI = i
				}
			}
			if minI < 0 {
				out.Columns[ci].Values = append(out.Columns[ci].Values, math.NaN(), math.NaN())
				continue
			}
			// Emit in chronological order so the line does not zig-zag backwards in time.
			first, second := minI, maxI
			if maxI < minI {
				first, second = maxI, minI
			}
			out.Columns[ci].Values = append(out.Columns[ci].Values, c.Values[first], c.Values[second])
		}
	}
	return out
}
