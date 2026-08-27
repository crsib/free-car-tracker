package domain

import "math"

// Fuel-consumption estimation for a single trip.
//
// The app only fills Trip.fuelUsedL by integrating fuelRateLph (PID 0x5E), and most cars never
// answer that PID — across a real 157-trip folder it was set on 2 trips. Everything below
// exists to get a usable number for the rest, from whatever the ECU did report, while being
// explicit about which method produced it and how much to trust it.
//
// Constants match the app's DerivedMetrics so a MAF-derived figure here agrees with the
// instantaneous L/100km the phone computes.
const (
	PetrolAFR     = 14.7  // stoichiometric air-fuel ratio
	PetrolDensity = 745.0 // g/L
	DieselAFR     = 14.5
	DieselDensity = 832.0
)

// AirFuelRatio and FuelDensity for a fuel type, defaulting to petrol.
func AirFuelRatio(fuelType string) float64 {
	if fuelType == "DIESEL" {
		return DieselAFR
	}
	return PetrolAFR
}

func FuelDensity(fuelType string) float64 {
	if fuelType == "DIESEL" {
		return DieselDensity
	}
	return PetrolDensity
}

// FuelSignals is what a trip's OBD series had to say about fuel, extracted once at summarise
// time. Every field is optional because every PID behind it is optional.
type FuelSignals struct {
	// LitresFromRate integrates fuelRateLph over time. This is the good one.
	LitresFromRate *float64
	// LitresFromMaf integrates mass-air-flow / AFR. Assumes a stoichiometric mixture, so it
	// under-reads under enrichment (hard acceleration) and over-reads on overrun fuel cut.
	LitresFromMaf *float64
	// Tank level at the start and end of the trip, in percent, robustly sampled.
	TankStartPct *float64
	TankEndPct   *float64
	// EngineRunSec is time with the engine turning. Zero with EngineObserved true means the
	// engine demonstrably never started - decisive for a hybrid or range-extender.
	EngineRunSec   float64
	EngineObserved bool
}

// Fuel-estimate methods, most to least trustworthy.
const (
	FuelMethodTrip      = "trip"      // the app's own integration of fuel rate
	FuelMethodRate      = "fuelRate"  // our integration of fuel rate
	FuelMethodMaf       = "maf"       // derived from mass air flow
	FuelMethodTankLevel = "tankLevel" // tank gauge delta x capacity
	FuelMethodEngineOff = "engineOff" // the engine never ran, so nothing was burned
	FuelMethodNone      = ""          // nothing usable
)

// Confidence bands, for the UI to caption honestly.
const (
	FuelMeasured  = "measured"
	FuelEstimated = "estimated"
	FuelRough     = "rough"
)

// FuelEstimate is how much fuel a trip used and how much that number is worth.
type FuelEstimate struct {
	Litres     *float64 `json:"litres"`
	L100       *float64 `json:"l100"`
	Method     string   `json:"method"`
	Confidence string   `json:"confidence,omitempty"`
	// Note explains the method in one plain sentence, for a caption.
	Note string `json:"note,omitempty"`

	TankStartPct *float64 `json:"tankStartPct,omitempty"`
	TankEndPct   *float64 `json:"tankEndPct,omitempty"`
	EngineRunSec float64  `json:"engineRunSec,omitempty"`
}

// tankLevelMinDistanceKm is the distance below which a tank-gauge estimate is not worth
// showing at all. Gauges quantise coarsely - often ~0.4% steps, which on a 60 L tank is a
// quarter of a litre - so over a few kilometres the quantisation error dwarfs the reading and
// yields absurd economy figures.
const tankLevelMinDistanceKm = 15.0

// tankFullPct is where a gauge counts as brim-full and therefore unreliable at the top of its
// range: it reads 100% for the first part of a tank and only then starts to move.
const tankFullPct = 99.0

// EstimateFuel picks the best available method. tripFuelUsedL is the app's own aggregate,
// tankCapacityL comes from the vehicle record (nil when unknown), and fuelType selects the
// AFR/density pair. Returns a zero-value estimate with FuelMethodNone when nothing is usable.
func EstimateFuel(
	tripFuelUsedL *float64,
	distanceKm *float64,
	sig FuelSignals,
	tankCapacityL *float64,
	usesFuel bool,
) FuelEstimate {
	if !usesFuel {
		return FuelEstimate{Method: FuelMethodNone}
	}

	est := FuelEstimate{
		Method:       FuelMethodNone,
		TankStartPct: sig.TankStartPct,
		TankEndPct:   sig.TankEndPct,
		EngineRunSec: sig.EngineRunSec,
	}

	// Every branch is plausibility-checked and falls through on failure. A single spurious
	// fuel-rate sample from an ELM327 integrates into an absurd total - two real trips carried
	// an app-computed 6e14 litres - and trusting the "best" source unconditionally would just
	// launder that into the dashboard.
	switch {
	// Checked first, because it is a fact rather than a derivation: an engine that never
	// turned burned nothing, so any non-zero figure from any other source is wrong by
	// definition. A stray sensor blip must not outvote 370 samples of stationary crankshaft.
	case sig.EngineObserved && sig.EngineRunSec == 0:
		zero := 0.0
		est.Litres = &zero
		est.Method = FuelMethodEngineOff
		est.Confidence = FuelMeasured
		est.Note = "No fuel was burned."

	case plausibleLitres(tripFuelUsedL, tankCapacityL):
		est.Litres = tripFuelUsedL
		est.Method = FuelMethodTrip
		est.Confidence = FuelMeasured
		est.Note = ""

	case plausibleLitres(sig.LitresFromRate, tankCapacityL):
		est.Litres = sig.LitresFromRate
		est.Method = FuelMethodRate
		est.Confidence = FuelMeasured
		est.Note = ""

	case plausibleLitres(sig.LitresFromMaf, tankCapacityL):
		est.Litres = sig.LitresFromMaf
		est.Method = FuelMethodMaf
		est.Confidence = FuelEstimated
		est.Note = "Assumes a stoichiometric mixture, so it under-reads under hard acceleration " +
			"and over-reads when the engine cuts fuel on overrun."

	case canUseTankLevel(sig, tankCapacityL, distanceKm):
		drop := *sig.TankStartPct - *sig.TankEndPct
		litres := drop / 100.0 * *tankCapacityL
		est.Litres = &litres
		est.Method = FuelMethodTankLevel
		est.Confidence = FuelRough
		est.Note = "The gauge is coarse and moves with slope and fuel slosh, so treat this as a " +
			"ballpark rather than a measurement."
		// A gauge sitting at full has nowhere to go: it stays pinned for the first litres
		// after a fill, so the drop - and therefore this estimate - reads low.
		if *sig.TankStartPct >= tankFullPct {
			est.Note += " The tank also started full, and a full gauge stays pinned for a " +
				"while, so the real figure is probably higher."
		}
	}

	if est.Litres != nil && *est.Litres > 0 {
		est.L100 = Per100Km(*est.Litres, distanceKm)
	}
	return est
}

// unknownTankCeilingL bounds a trip's fuel use when the car's tank size is unknown. Nothing on
// the road holds 200 litres, so anything above it is a broken reading rather than a long drive.
const unknownTankCeilingL = 200.0

// plausibleLitres accepts a figure that could physically have been burned in one trip. A trip
// cannot consume much more than a tankful - refuelling mid-trip is the only way past one, hence
// the factor of two - and NaN/Inf are always rejected.
func plausibleLitres(litres *float64, tankCapacityL *float64) bool {
	if litres == nil {
		return false
	}
	v := *litres
	if math.IsNaN(v) || math.IsInf(v, 0) || v <= 0 {
		return false
	}
	ceiling := unknownTankCeilingL
	if tankCapacityL != nil && *tankCapacityL > 0 {
		ceiling = *tankCapacityL * 2
	}
	return v <= ceiling
}

// canUseTankLevel guards the gauge method against the ways it produces nonsense: a missing
// capacity, a level that rose (a refuel mid-trip, or slosh), and trips too short for the
// gauge's resolution to mean anything.
func canUseTankLevel(sig FuelSignals, tankCapacityL, distanceKm *float64) bool {
	if tankCapacityL == nil || *tankCapacityL <= 0 {
		return false
	}
	if sig.TankStartPct == nil || sig.TankEndPct == nil {
		return false
	}
	if *sig.TankStartPct <= *sig.TankEndPct {
		return false
	}
	if distanceKm == nil || *distanceKm < tankLevelMinDistanceKm {
		return false
	}
	return true
}

// FuelMethodLabel is a short human name for a method, for captions and CSV columns.
func FuelMethodLabel(method string) string {
	switch method {
	case FuelMethodTrip, FuelMethodRate:
		return "fuel rate"
	case FuelMethodMaf:
		return "mass air flow"
	case FuelMethodTankLevel:
		return "tank gauge"
	case FuelMethodEngineOff:
		return "engine off"
	default:
		return ""
	}
}
