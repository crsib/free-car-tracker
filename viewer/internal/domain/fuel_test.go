package domain

import (
	"math"
	"strings"
	"testing"
)

func f(v float64) *float64 { return &v }

func TestEstimateFuelPrefersTheAppsOwnAggregate(t *testing.T) {
	est := EstimateFuel(f(4.2), f(100), FuelSignals{
		LitresFromRate: f(9.9), LitresFromMaf: f(8.8),
	}, f(60), true)

	if est.Method != FuelMethodTrip {
		t.Errorf("method = %q, want %q", est.Method, FuelMethodTrip)
	}
	if est.Litres == nil || *est.Litres != 4.2 {
		t.Errorf("litres = %v, want 4.2", est.Litres)
	}
	if est.L100 == nil || *est.L100 != 4.2 {
		t.Errorf("L/100km = %v, want 4.2 over 100 km", est.L100)
	}
	if est.Confidence != FuelMeasured {
		t.Errorf("confidence = %q, want measured", est.Confidence)
	}
}

// The order matters: fuel rate is a real measurement, MAF is a stoichiometric guess, and the
// tank gauge is barely better than a rumour.
func TestEstimateFuelMethodPrecedence(t *testing.T) {
	cases := []struct {
		name string
		sig  FuelSignals
		want string
	}{
		{"rate beats maf", FuelSignals{LitresFromRate: f(5), LitresFromMaf: f(6),
			TankStartPct: f(90), TankEndPct: f(80)}, FuelMethodRate},
		{"maf beats tank", FuelSignals{LitresFromMaf: f(6),
			TankStartPct: f(90), TankEndPct: f(80)}, FuelMethodMaf},
		{"tank is the last resort", FuelSignals{
			TankStartPct: f(90), TankEndPct: f(80), EngineRunSec: 600, EngineObserved: true}, FuelMethodTankLevel},
	}
	for _, tc := range cases {
		est := EstimateFuel(nil, f(100), tc.sig, f(60), true)
		if est.Method != tc.want {
			t.Errorf("%s: method = %q, want %q", tc.name, est.Method, tc.want)
		}
	}
}

// The whole point of this for a hybrid: an engine that never turned burned nothing, whatever
// the tank gauge drifted to. Reporting the gauge delta here would be actively misleading -
// on a real 6 km trip it claimed 8.7 L while the engine sat idle.
func TestEngineThatNeverRanBurnsNothing(t *testing.T) {
	est := EstimateFuel(nil, f(100), FuelSignals{
		TankStartPct: f(100), TankEndPct: f(85.5),
		EngineRunSec: 0, EngineObserved: true,
	}, f(60), true)

	if est.Method != FuelMethodEngineOff {
		t.Fatalf("method = %q, want %q", est.Method, FuelMethodEngineOff)
	}
	if est.Litres == nil || *est.Litres != 0 {
		t.Errorf("litres = %v, want 0", est.Litres)
	}
	if est.L100 != nil {
		t.Errorf("economy = %v, want nil - zero fuel over any distance is not an economy figure", est.L100)
	}
	if est.Confidence != FuelMeasured {
		t.Errorf("confidence = %q; an engine at rest is a measurement, not a guess", est.Confidence)
	}
}

// Not observing rpm at all is different from observing it at zero: without the signal we
// cannot claim the engine was off, so the gauge is still the best available answer.
func TestUnobservedEngineDoesNotClaimZero(t *testing.T) {
	est := EstimateFuel(nil, f(100), FuelSignals{
		TankStartPct: f(90), TankEndPct: f(80), EngineObserved: false,
	}, f(60), true)

	if est.Method != FuelMethodTankLevel {
		t.Errorf("method = %q, want the tank gauge when the engine was never observed", est.Method)
	}
	if est.Litres == nil || *est.Litres != 6 {
		t.Errorf("litres = %v, want 6 (10%% of a 60 L tank)", est.Litres)
	}
}

func TestTankLevelArithmetic(t *testing.T) {
	est := EstimateFuel(nil, f(120), FuelSignals{
		TankStartPct: f(75), TankEndPct: f(60), EngineRunSec: 3600, EngineObserved: true,
	}, f(60), true)

	if est.Litres == nil || *est.Litres != 9 {
		t.Fatalf("litres = %v, want 9 (15%% of 60 L)", est.Litres)
	}
	if est.L100 == nil || *est.L100 != 7.5 {
		t.Errorf("L/100km = %v, want 7.5", est.L100)
	}
	if est.Confidence != FuelRough {
		t.Errorf("confidence = %q, want rough", est.Confidence)
	}
	if est.TankStartPct == nil || est.TankEndPct == nil {
		t.Error("the tank readings should be carried through so the UI can show its working")
	}
}

// Every way the gauge produces nonsense.
func TestTankLevelGuards(t *testing.T) {
	ran := FuelSignals{TankStartPct: f(90), TankEndPct: f(80), EngineRunSec: 600, EngineObserved: true}

	cases := []struct {
		name     string
		sig      FuelSignals
		distance *float64
		capacity *float64
	}{
		{"no tank capacity", ran, f(100), nil},
		{"zero tank capacity", ran, f(100), f(0)},
		{"level rose (refuelled mid-trip)", FuelSignals{
			TankStartPct: f(20), TankEndPct: f(80), EngineRunSec: 600, EngineObserved: true}, f(100), f(60)},
		{"level unchanged", FuelSignals{
			TankStartPct: f(80), TankEndPct: f(80), EngineRunSec: 600, EngineObserved: true}, f(100), f(60)},
		{"trip too short for the gauge's resolution", ran, f(3), f(60)},
		{"no distance", ran, nil, f(60)},
		{"no readings", FuelSignals{EngineRunSec: 600, EngineObserved: true}, f(100), f(60)},
	}
	for _, tc := range cases {
		est := EstimateFuel(nil, tc.distance, tc.sig, tc.capacity, true)
		if est.Method == FuelMethodTankLevel {
			t.Errorf("%s: used the tank gauge anyway (litres=%v)", tc.name, est.Litres)
		}
	}
}

func TestElectricCarsGetNoFuelEstimate(t *testing.T) {
	est := EstimateFuel(f(5), f(100), FuelSignals{LitresFromRate: f(5)}, f(60), false)
	if est.Method != FuelMethodNone || est.Litres != nil {
		t.Errorf("an EV should have no fuel estimate, got method=%q litres=%v", est.Method, est.Litres)
	}
}

func TestNoSignalsAtAllYieldsNothing(t *testing.T) {
	est := EstimateFuel(nil, f(100), FuelSignals{}, f(60), true)
	if est.Method != FuelMethodNone {
		t.Errorf("method = %q, want none", est.Method)
	}
	if est.Litres != nil || est.L100 != nil {
		t.Error("with no signals there is nothing to report, not a zero")
	}
}

func TestFuelChemistryPerType(t *testing.T) {
	if AirFuelRatio("DIESEL") != DieselAFR || FuelDensity("DIESEL") != DieselDensity {
		t.Error("diesel should use its own AFR and density")
	}
	// Unknown falls back to petrol, matching the app's DerivedMetrics default.
	if AirFuelRatio("") != PetrolAFR || FuelDensity("LPG") != PetrolDensity {
		t.Error("unknown fuel types should fall back to petrol")
	}
}

// A gauge that starts brim-full understates the drop, because it reads 100% for the first
// litres after a fill. The number is still worth showing, but it must say so.
func TestFullTankEstimateSaysItReadsLow(t *testing.T) {
	est := EstimateFuel(nil, f(50), FuelSignals{
		TankStartPct: f(100), TankEndPct: f(98.8), EngineRunSec: 1976, EngineObserved: true,
	}, f(60), true)

	if est.Method != FuelMethodTankLevel {
		t.Fatalf("method = %q, want the tank gauge", est.Method)
	}
	if !strings.Contains(est.Note, "pinned") {
		t.Errorf("note should warn that a full gauge under-reads, got %q", est.Note)
	}

	// A mid-range tank gets the plain caveat with no full-tank warning.
	mid := EstimateFuel(nil, f(50), FuelSignals{
		TankStartPct: f(65), TankEndPct: f(50), EngineRunSec: 1976, EngineObserved: true,
	}, f(60), true)
	if strings.Contains(mid.Note, "pinned") {
		t.Errorf("a mid-range tank should not get the full-tank warning, got %q", mid.Note)
	}
}

// An ELM327 that reports one spurious fuel-rate sample integrates into an absurd total. Two
// real trips carried an app-computed 597,423,361,848,841 L. Trusting the "best" source
// unconditionally would launder that straight into the dashboard, so every branch is
// plausibility-checked and falls through instead.
func TestImplausibleFuelFallsThroughToTheNextMethod(t *testing.T) {
	absurd := 597423361848841.4

	est := EstimateFuel(&absurd, f(2.8), FuelSignals{
		TankStartPct: f(90), TankEndPct: f(80), EngineRunSec: 600, EngineObserved: true,
	}, f(60), true)

	if est.Method == FuelMethodTrip {
		t.Fatalf("an impossible %v L was accepted from the app aggregate", absurd)
	}
	if est.Litres != nil && *est.Litres > 120 {
		t.Errorf("litres = %v, still implausible for a 60 L tank", *est.Litres)
	}

	// The same guard applies to our own integrations.
	est = EstimateFuel(nil, f(100), FuelSignals{LitresFromRate: &absurd, LitresFromMaf: &absurd}, f(60), true)
	if est.Method == FuelMethodRate || est.Method == FuelMethodMaf {
		t.Errorf("method = %q; an absurd integral should be rejected too", est.Method)
	}
}

func TestPlausibilityBounds(t *testing.T) {
	nan := math.NaN()
	inf := math.Inf(1)
	cases := []struct {
		name     string
		litres   *float64
		capacity *float64
		want     bool
	}{
		{"a normal tankful", f(45), f(60), true},
		{"right at twice the tank", f(120), f(60), true},
		{"beyond two tanks", f(121), f(60), false},
		{"unknown tank, sane figure", f(45), nil, true},
		{"unknown tank, absurd figure", f(500), nil, false},
		{"zero", f(0), f(60), false},
		{"negative", f(-5), f(60), false},
		{"NaN", &nan, f(60), false},
		{"infinity", &inf, f(60), false},
		{"absent", nil, f(60), false},
	}
	for _, tc := range cases {
		if got := plausibleLitres(tc.litres, tc.capacity); got != tc.want {
			t.Errorf("%s: plausible = %v, want %v", tc.name, got, tc.want)
		}
	}
}

// A demonstrably stationary engine outranks every derived integral. A real trip had exactly
// two mass-air-flow samples while rpm and engine load both sat at zero across 370 samples;
// that blip integrated to a "reading" and beat the fact that the engine was off.
func TestEngineOffOutranksAStraySensorIntegral(t *testing.T) {
	blip := 0.0004
	est := EstimateFuel(nil, f(6.9), FuelSignals{
		LitresFromMaf: &blip,
		TankStartPct:  f(100), TankEndPct: f(91.4),
		EngineRunSec: 0, EngineObserved: true,
	}, f(60), true)

	if est.Method != FuelMethodEngineOff {
		t.Errorf("method = %q, want %q - a stray blip must not outvote a stationary engine",
			est.Method, FuelMethodEngineOff)
	}
	if est.Litres == nil || *est.Litres != 0 {
		t.Errorf("litres = %v, want 0", est.Litres)
	}
}
