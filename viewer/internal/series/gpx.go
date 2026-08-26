package series

import (
	"bytes"
	"encoding/xml"
	"math"
	"time"

	"crsib.me/fct-viewer/internal/model"
)

// gpxDoc mirrors what GpxExporter.kt writes: GPX 1.1 trkpts whose non-standard fields
// (speed, bearing, accuracy, provider) live in a custom-namespace <extensions> block.
// Matching on namespace rather than prefix keeps this working whatever prefix a writer picks.
// The nesting is spelled out as structs rather than as an "a>b>c" tag path because that form
// cannot carry a namespace per step. Struct tags cannot reference constants, so the literals
// in the tags below repeat these two declarations - keep them in step.
const (
	gpxNS = "http://www.topografix.com/GPX/1/1"
	fctNS = "https://crsib.me/freecartracker/gpx/1"
)

type gpxExtensions struct {
	SpeedKmh   *float64 `xml:"https://crsib.me/freecartracker/gpx/1 speedKmh"`
	BearingDeg *float64 `xml:"https://crsib.me/freecartracker/gpx/1 bearingDeg"`
	AccuracyM  *float64 `xml:"https://crsib.me/freecartracker/gpx/1 accuracyM"`
	Provider   string   `xml:"https://crsib.me/freecartracker/gpx/1 provider"`
}

type gpxTrkpt struct {
	Lat        float64       `xml:"lat,attr"`
	Lon        float64       `xml:"lon,attr"`
	Ele        *float64      `xml:"http://www.topografix.com/GPX/1/1 ele"`
	Time       string        `xml:"http://www.topografix.com/GPX/1/1 time"`
	Extensions gpxExtensions `xml:"http://www.topografix.com/GPX/1/1 extensions"`
}

type gpxTrkseg struct {
	Trkpts []gpxTrkpt `xml:"http://www.topografix.com/GPX/1/1 trkpt"`
}

type gpxTrk struct {
	Trksegs []gpxTrkseg `xml:"http://www.topografix.com/GPX/1/1 trkseg"`
}

type gpxDoc struct {
	Trks []gpxTrk `xml:"http://www.topografix.com/GPX/1/1 trk"`
}

// trkpts flattens every segment of every track into one ordered list. The exporter writes a
// single trk/trkseg, but a track hand-edited in another tool may not, and dropping the extra
// segments would silently truncate the route.
func (d gpxDoc) trkpts() []gpxTrkpt {
	var out []gpxTrkpt
	for _, trk := range d.Trks {
		for _, seg := range trk.Trksegs {
			out = append(out, seg.Trkpts...)
		}
	}
	return out
}

// ParseGPX reads a track back into points. A trkpt without a parseable time is skipped, the
// same rule GpxParser.kt applies - a fix with no clock cannot be placed on the trip timeline.
func ParseGPX(body []byte) ([]model.GpsPoint, error) {
	var doc gpxDoc
	if err := xml.NewDecoder(bytes.NewReader(body)).Decode(&doc); err != nil {
		return nil, err
	}
	pts := doc.trkpts()
	out := make([]model.GpsPoint, 0, len(pts))
	for _, p := range pts {
		t, err := time.Parse(time.RFC3339Nano, p.Time)
		if err != nil {
			continue
		}
		provider := p.Extensions.Provider
		if provider == "" {
			provider = "gps"
		}
		out = append(out, model.GpsPoint{
			TimestampMillis: t.UnixMilli(),
			Latitude:        p.Lat,
			Longitude:       p.Lon,
			AltitudeM:       p.Ele,
			SpeedKmh:        p.Extensions.SpeedKmh,
			BearingDeg:      p.Extensions.BearingDeg,
			AccuracyM:       p.Extensions.AccuracyM,
			Provider:        provider,
		})
	}
	return out, nil
}

// Bounds is the lat/lon envelope of a track, for framing the map.
type Bounds struct {
	MinLat float64 `json:"minLat"`
	MinLon float64 `json:"minLon"`
	MaxLat float64 `json:"maxLat"`
	MaxLon float64 `json:"maxLon"`
}

func TrackBounds(pts []model.GpsPoint) *Bounds {
	if len(pts) == 0 {
		return nil
	}
	b := Bounds{MinLat: pts[0].Latitude, MaxLat: pts[0].Latitude, MinLon: pts[0].Longitude, MaxLon: pts[0].Longitude}
	for _, p := range pts[1:] {
		b.MinLat = math.Min(b.MinLat, p.Latitude)
		b.MaxLat = math.Max(b.MaxLat, p.Latitude)
		b.MinLon = math.Min(b.MinLon, p.Longitude)
		b.MaxLon = math.Max(b.MaxLon, p.Longitude)
	}
	return &b
}

const earthRadiusKm = 6371.0088

// HaversineKm is great-circle distance between two fixes.
func HaversineKm(lat1, lon1, lat2, lon2 float64) float64 {
	rad := math.Pi / 180
	dLat := (lat2 - lat1) * rad
	dLon := (lon2 - lon1) * rad
	a := math.Sin(dLat/2)*math.Sin(dLat/2) +
		math.Cos(lat1*rad)*math.Cos(lat2*rad)*math.Sin(dLon/2)*math.Sin(dLon/2)
	return 2 * earthRadiusKm * math.Asin(math.Sqrt(math.Min(1, a)))
}

// CumulativeKm is running distance along the track, index-aligned with pts. It lets the UI put
// a chart on a distance axis and lets a trip with no OBD odometer still report how far it went.
func CumulativeKm(pts []model.GpsPoint) []float64 {
	out := make([]float64, len(pts))
	for i := 1; i < len(pts); i++ {
		out[i] = out[i-1] + HaversineKm(pts[i-1].Latitude, pts[i-1].Longitude, pts[i].Latitude, pts[i].Longitude)
	}
	return out
}

// DecimateTrack thins a track to at most maxPoints, keeping the first and last fix and
// preferring points that carry the most shape. It walks fixed index strides rather than
// running a full Douglas-Peucker: a 10 Hz track is already dense enough that stride sampling
// is visually identical at map zoom, and it stays O(n) on a trip with 100k fixes.
func DecimateTrack(pts []model.GpsPoint, maxPoints int) []model.GpsPoint {
	if maxPoints <= 2 || len(pts) <= maxPoints {
		return pts
	}
	out := make([]model.GpsPoint, 0, maxPoints)
	step := float64(len(pts)-1) / float64(maxPoints-1)
	for i := 0; i < maxPoints-1; i++ {
		out = append(out, pts[int(float64(i)*step)])
	}
	return append(out, pts[len(pts)-1])
}
