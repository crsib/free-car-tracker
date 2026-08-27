package series

import (
	"math"
	"sort"
)

// engineRunningRpm is the threshold above which the engine counts as turning. Cranking and
// sensor noise sit well below this; a petrol idle is 600-900 rpm.
const engineRunningRpm = 300.0

// tankEdgeSamples is how many readings at each end of the trip are used to fix the start and
// end tank level. A single sample is far too fragile: fuel sloshes, the gauge is damped, and
// one spurious reading at either end would swing the whole estimate. The median of a handful
// is much steadier at no real cost.
const tankEdgeSamples = 5

// minIntegrationIntervals is how many consecutive readings an integral needs before it counts
// as a trip total. A stray pair of samples integrates to a number that looks like an answer
// but is not one: a real trip had exactly two mass-air-flow readings while rpm sat at zero
// across 370 samples, and that two-sample integral outranked the fact that the engine was off.
const minIntegrationIntervals = 5

// Column returns a column by name.
func (f *Frame) Column(name string) (Column, bool) {
	for _, c := range f.Columns {
		if c.Name == name {
			return c, true
		}
	}
	return Column{}, false
}

// IntegrateOverTime integrates a column against the timestamp axis using the trapezoid rule,
// skipping gaps. perHour says whether the column's unit is "per hour" (so dt is in hours) or
// "per second". Returns the total and how many intervals contributed.
//
// Gaps matter here: OBD columns are polled at different rates, so a fuel-rate column may have
// a reading every few seconds while speed has one every 100 ms. Integrating only across
// consecutive *reported* values, rather than across every row, is what keeps the total honest.
func (f *Frame) IntegrateOverTime(name string, perHour bool) (float64, int) {
	col, ok := f.Column(name)
	if !ok {
		return 0, 0
	}
	divisor := 1000.0 // ms -> seconds
	if perHour {
		divisor = 3_600_000.0 // ms -> hours
	}

	total := 0.0
	intervals := 0
	prevIdx := -1
	for i, v := range col.Values {
		if math.IsNaN(v) {
			continue
		}
		if prevIdx >= 0 {
			dt := float64(f.Timestamps[i]-f.Timestamps[prevIdx]) / divisor
			// A long gap means the metric stopped being polled, not that it held its value;
			// integrating across it would invent consumption. Cap the bridged interval.
			if dt > 0 && dt <= maxBridge(perHour) {
				total += (v + col.Values[prevIdx]) / 2.0 * dt
				intervals++
			}
		}
		prevIdx = i
	}
	return total, intervals
}

// maxBridge caps how long a gap may be and still be integrated across: 60 seconds, expressed
// in whichever unit the caller is working in.
func maxBridge(perHour bool) float64 {
	if perHour {
		return 60.0 / 3600.0
	}
	return 60.0
}

// SecondsWhere totals the time a column spent satisfying pred, using the same gap-capped
// bridging as IntegrateOverTime.
func (f *Frame) SecondsWhere(name string, pred func(float64) bool) (float64, bool) {
	col, ok := f.Column(name)
	if !ok {
		return 0, false
	}
	seconds := 0.0
	seen := false
	prevIdx := -1
	for i, v := range col.Values {
		if math.IsNaN(v) {
			continue
		}
		seen = true
		if prevIdx >= 0 {
			dt := float64(f.Timestamps[i]-f.Timestamps[prevIdx]) / 1000.0
			// Attribute the interval to the condition when it held at both ends.
			if dt > 0 && dt <= 60 && pred(v) && pred(col.Values[prevIdx]) {
				seconds += dt
			}
		}
		prevIdx = i
	}
	return seconds, seen
}

// tankEdges reads the level at the start and end of the trip as the median of a few samples
// at each end.
//
// The window is clamped so the two halves can never overlap: on a trip with only a handful of
// level readings, an unclamped window would have both medians see the whole series and report
// start == end, silently losing the drop.
func tankEdges(col Column) (start, end *float64) {
	reported := 0
	for _, v := range col.Values {
		if !math.IsNaN(v) {
			reported++
		}
	}
	if reported == 0 {
		return nil, nil
	}
	n := tankEdgeSamples
	if half := reported / 2; half < n {
		n = half
	}
	if n < 1 {
		n = 1
	}
	return edgeMedian(col, n, false), edgeMedian(col, n, true)
}

// edgeMedian is the median of the first (or last) n non-NaN values of a column.
func edgeMedian(col Column, n int, fromEnd bool) *float64 {
	vals := make([]float64, 0, n)
	if fromEnd {
		for i := len(col.Values) - 1; i >= 0 && len(vals) < n; i-- {
			if !math.IsNaN(col.Values[i]) {
				vals = append(vals, col.Values[i])
			}
		}
	} else {
		for i := 0; i < len(col.Values) && len(vals) < n; i++ {
			if !math.IsNaN(col.Values[i]) {
				vals = append(vals, col.Values[i])
			}
		}
	}
	if len(vals) == 0 {
		return nil
	}
	sort.Float64s(vals)
	m := vals[len(vals)/2]
	return &m
}

// FuelSignals pulls every fuel-related observation out of the series in one pass over the
// columns. afr and density select the fuel chemistry for the MAF derivation.
//
// The returned struct is deliberately raw: it records what the car reported, and leaves the
// choice of which number to believe to the estimator.
func (f *Frame) FuelSignals(afr, density float64) (litresFromRate, litresFromMaf, tankStart, tankEnd *float64, engineRunSec float64, engineObserved bool) {
	if litres, n := f.IntegrateOverTime("fuelRateLph", true); n >= minIntegrationIntervals && litres > 0 {
		litresFromRate = &litres
	}

	// MAF is grams of air per second; fuel mass is that over the air-fuel ratio, and volume is
	// mass over density. Integrating in seconds keeps the arithmetic in one unit system.
	if grams, n := f.IntegrateOverTime("mafGramsPerSec", false); n >= minIntegrationIntervals && grams > 0 {
		litres := grams / afr / density
		if litres > 0 {
			litresFromMaf = &litres
		}
	}

	if col, ok := f.Column("fuelLevelPct"); ok {
		tankStart, tankEnd = tankEdges(col)
	}

	// Take the union of the two engine-activity signals rather than preferring one: rpm is the
	// direct measure, but an ECU that reports a stuck zero there would otherwise have us
	// declaring the engine off while it was plainly under load.
	rpmSec, rpmSeen := f.SecondsWhere("rpm", func(v float64) bool { return v > engineRunningRpm })
	loadSec, loadSeen := f.SecondsWhere("engineLoadPct", func(v float64) bool { return v > 0 })
	engineRunSec = math.Max(rpmSec, loadSec)
	engineObserved = rpmSeen || loadSeen
	return
}
