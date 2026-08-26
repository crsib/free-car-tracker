// Package domain ports the app's pure calculators (domain/EconomyCalculator.kt,
// EnergyCostCalculator.kt, RecurrenceProjector.kt, UnitConversions.kt, Money.kt) to Go.
// It is a deliberate re-implementation rather than an approximation: the viewer showing a
// different fuel economy from the phone for the same data would make it useless, so the
// ordering, the skip rules and the full-tank anchoring below all follow the Kotlin exactly.
package domain

import "sort"

// FuelPoint is one fill in canonical units. OdometerKm is a pointer because fills without a
// reading are dropped rather than treated as zero.
type FuelPoint struct {
	OdometerKm *float64
	VolumeL    float64
	IsFullTank bool
	DateMillis int64
}

// FuelSegment is one completed full-tank-to-full-tank interval.
type FuelSegment struct {
	StartOdometerKm float64 `json:"startOdometerKm"`
	EndOdometerKm   float64 `json:"endOdometerKm"`
	DistanceKm      float64 `json:"distanceKm"`
	Litres          float64 `json:"litres"`
	EndDateMillis   int64   `json:"endDateMillis"`
}

func (s FuelSegment) LitresPer100Km() float64 { return s.Litres / s.DistanceKm * 100.0 }

type ChargePoint struct {
	OdometerKm *float64
	EnergyKwh  float64
	DateMillis int64
}

type ChargeSegment struct {
	StartOdometerKm float64 `json:"startOdometerKm"`
	EndOdometerKm   float64 `json:"endOdometerKm"`
	DistanceKm      float64 `json:"distanceKm"`
	Kwh             float64 `json:"kwh"`
	EndDateMillis   int64   `json:"endDateMillis"`
}

func (s ChargeSegment) KwhPer100Km() float64 { return s.Kwh / s.DistanceKm * 100.0 }

// FuelSegments computes economy intervals with the full-tank-to-full-tank method: between two
// full fills, distance is the odometer delta and consumption is every fill made *after* the
// opening full tank up to and including the closing one, partial fills included.
func FuelSegments(points []FuelPoint) []FuelSegment {
	ordered := make([]FuelPoint, 0, len(points))
	for _, p := range points {
		if p.OdometerKm != nil {
			ordered = append(ordered, p)
		}
	}
	// Sorted by odometer then date, matching compareBy({ odometerKm }, { dateMillis }).
	sort.SliceStable(ordered, func(i, j int) bool {
		if *ordered[i].OdometerKm != *ordered[j].OdometerKm {
			return *ordered[i].OdometerKm < *ordered[j].OdometerKm
		}
		return ordered[i].DateMillis < ordered[j].DateMillis
	})
	if len(ordered) < 2 {
		return nil
	}
	firstFull := -1
	for i, p := range ordered {
		if p.IsFullTank {
			firstFull = i
			break
		}
	}
	if firstFull < 0 || firstFull == len(ordered)-1 {
		return nil
	}

	var segments []FuelSegment
	anchor := firstFull
	consumed := 0.0
	for j := firstFull + 1; j < len(ordered); j++ {
		consumed += ordered[j].VolumeL
		if ordered[j].IsFullTank {
			start := *ordered[anchor].OdometerKm
			end := *ordered[j].OdometerKm
			if d := end - start; d > 0 {
				segments = append(segments, FuelSegment{
					StartOdometerKm: start,
					EndOdometerKm:   end,
					DistanceKm:      d,
					Litres:          consumed,
					EndDateMillis:   ordered[j].DateMillis,
				})
			}
			anchor = j
			consumed = 0
		}
	}
	return segments
}

// LatestFuelEconomyL100 is the economy of the most recent completed full-to-full interval.
func LatestFuelEconomyL100(points []FuelPoint) *float64 {
	segs := FuelSegments(points)
	if len(segs) == 0 {
		return nil
	}
	best := segs[0]
	for _, s := range segs[1:] {
		if s.EndDateMillis > best.EndDateMillis {
			best = s
		}
	}
	v := best.LitresPer100Km()
	return &v
}

// ChargeSegments pairs consecutive odometer-bearing charges: distance is the odometer delta,
// energy is what was added at the *closing* charge (replenishing what the interval consumed).
func ChargeSegments(points []ChargePoint) []ChargeSegment {
	ordered := make([]ChargePoint, 0, len(points))
	for _, p := range points {
		if p.OdometerKm != nil {
			ordered = append(ordered, p)
		}
	}
	sort.SliceStable(ordered, func(i, j int) bool {
		if *ordered[i].OdometerKm != *ordered[j].OdometerKm {
			return *ordered[i].OdometerKm < *ordered[j].OdometerKm
		}
		return ordered[i].DateMillis < ordered[j].DateMillis
	})
	if len(ordered) < 2 {
		return nil
	}
	var segments []ChargeSegment
	for i := 1; i < len(ordered); i++ {
		start := *ordered[i-1].OdometerKm
		end := *ordered[i].OdometerKm
		if d := end - start; d > 0 && ordered[i].EnergyKwh > 0 {
			segments = append(segments, ChargeSegment{
				StartOdometerKm: start,
				EndOdometerKm:   end,
				DistanceKm:      d,
				Kwh:             ordered[i].EnergyKwh,
				EndDateMillis:   ordered[i].DateMillis,
			})
		}
	}
	return segments
}

// DistanceSpanKm is the span between the smallest and largest odometer reading.
func DistanceSpanKm(odometersKm []float64) *float64 {
	if len(odometersKm) < 2 {
		return nil
	}
	min, max := odometersKm[0], odometersKm[0]
	for _, v := range odometersKm[1:] {
		if v < min {
			min = v
		}
		if v > max {
			max = v
		}
	}
	if d := max - min; d > 0 {
		return &d
	}
	return nil
}

// Per100Km is the generic "quantity per 100 km" (fuel or energy).
func Per100Km(quantity float64, distanceKm *float64) *float64 {
	if distanceKm == nil || *distanceKm <= 0 || quantity <= 0 {
		return nil
	}
	v := quantity / *distanceKm * 100.0
	return &v
}

// CostPerKmMinor is cost in currency minor units per km.
func CostPerKmMinor(totalCostMinor int64, distanceKm *float64) *float64 {
	if distanceKm == nil || *distanceKm <= 0 {
		return nil
	}
	v := float64(totalCostMinor) / *distanceKm
	return &v
}
