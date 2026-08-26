package domain

import (
	"math"
	"testing"
	"time"
)

func ptr(v float64) *float64 { return &v }

func approx(t *testing.T, got, want float64, label string) {
	t.Helper()
	if math.Abs(got-want) > 1e-6 {
		t.Errorf("%s = %v, want %v", label, got, want)
	}
}

// The full-tank method: distance is the odometer delta between two full fills, and consumption
// is every fill *after* the opening full tank up to and including the closing one. The partial
// fill in the middle must be counted, and the opening tank's own volume must not be.
func TestFuelSegmentsIncludesPartialsAndExcludesOpeningFill(t *testing.T) {
	points := []FuelPoint{
		{OdometerKm: ptr(1000), VolumeL: 40, IsFullTank: true, DateMillis: 1},
		{OdometerKm: ptr(1200), VolumeL: 15, IsFullTank: false, DateMillis: 2},
		{OdometerKm: ptr(1500), VolumeL: 25, IsFullTank: true, DateMillis: 3},
	}
	segs := FuelSegments(points)
	if len(segs) != 1 {
		t.Fatalf("got %d segments, want 1", len(segs))
	}
	approx(t, segs[0].DistanceKm, 500, "distance")
	approx(t, segs[0].Litres, 40, "litres") // 15 + 25, not the opening 40
	approx(t, segs[0].LitresPer100Km(), 8, "L/100km")
}

// Fills before the first full tank cannot be attributed to any interval, so the first full
// fill anchors the series and everything before it is ignored.
func TestFuelSegmentsAnchorsOnFirstFullTank(t *testing.T) {
	points := []FuelPoint{
		{OdometerKm: ptr(900), VolumeL: 20, IsFullTank: false, DateMillis: 1},
		{OdometerKm: ptr(1000), VolumeL: 40, IsFullTank: true, DateMillis: 2},
		{OdometerKm: ptr(1400), VolumeL: 32, IsFullTank: true, DateMillis: 3},
	}
	segs := FuelSegments(points)
	if len(segs) != 1 {
		t.Fatalf("got %d segments, want 1", len(segs))
	}
	approx(t, segs[0].StartOdometerKm, 1000, "start odometer")
	approx(t, segs[0].Litres, 32, "litres")
}

func TestFuelSegmentsSkipsEntriesWithoutOdometer(t *testing.T) {
	points := []FuelPoint{
		{OdometerKm: ptr(1000), VolumeL: 40, IsFullTank: true, DateMillis: 1},
		{OdometerKm: nil, VolumeL: 999, IsFullTank: true, DateMillis: 2},
		{OdometerKm: ptr(1400), VolumeL: 28, IsFullTank: true, DateMillis: 3},
	}
	segs := FuelSegments(points)
	if len(segs) != 1 {
		t.Fatalf("got %d segments, want 1", len(segs))
	}
	// The odometer-less 999 L fill must not leak into the consumption total.
	approx(t, segs[0].Litres, 28, "litres")
}

// Two fills at the same odometer produce a zero-distance interval, which would divide by zero.
func TestFuelSegmentsSkipsNonPositiveDistance(t *testing.T) {
	points := []FuelPoint{
		{OdometerKm: ptr(1000), VolumeL: 40, IsFullTank: true, DateMillis: 1},
		{OdometerKm: ptr(1000), VolumeL: 5, IsFullTank: true, DateMillis: 2},
		{OdometerKm: ptr(1300), VolumeL: 24, IsFullTank: true, DateMillis: 3},
	}
	segs := FuelSegments(points)
	if len(segs) != 1 {
		t.Fatalf("got %d segments, want 1", len(segs))
	}
	approx(t, segs[0].DistanceKm, 300, "distance")
}

func TestLatestFuelEconomyPicksMostRecentInterval(t *testing.T) {
	points := []FuelPoint{
		{OdometerKm: ptr(1000), VolumeL: 40, IsFullTank: true, DateMillis: 100},
		{OdometerKm: ptr(1500), VolumeL: 40, IsFullTank: true, DateMillis: 200}, // 8.0
		{OdometerKm: ptr(2000), VolumeL: 25, IsFullTank: true, DateMillis: 300}, // 5.0
	}
	got := LatestFuelEconomyL100(points)
	if got == nil {
		t.Fatal("got nil, want the most recent interval")
	}
	approx(t, *got, 5, "latest economy")
}

// Energy for an interval is what was added at the *closing* charge - it replenishes what the
// interval just consumed, so pairing it with the opening charge would be off by one.
func TestChargeSegmentsUseClosingCharge(t *testing.T) {
	points := []ChargePoint{
		{OdometerKm: ptr(0), EnergyKwh: 50, DateMillis: 1},
		{OdometerKm: ptr(200), EnergyKwh: 30, DateMillis: 2},
	}
	segs := ChargeSegments(points)
	if len(segs) != 1 {
		t.Fatalf("got %d segments, want 1", len(segs))
	}
	approx(t, segs[0].Kwh, 30, "kWh")
	approx(t, segs[0].KwhPer100Km(), 15, "kWh/100km")
}

func TestPer100KmAndCostPerKmRejectUnusableInputs(t *testing.T) {
	if Per100Km(10, nil) != nil {
		t.Error("nil distance should give nil")
	}
	if Per100Km(10, ptr(0)) != nil {
		t.Error("zero distance should give nil")
	}
	if Per100Km(0, ptr(100)) != nil {
		t.Error("zero quantity should give nil")
	}
	if CostPerKmMinor(1000, ptr(0)) != nil {
		t.Error("zero distance should give nil cost per km")
	}
	if got := CostPerKmMinor(1000, ptr(100)); got == nil || *got != 10 {
		t.Errorf("cost per km = %v, want 10", got)
	}
}

func TestDistanceSpan(t *testing.T) {
	if DistanceSpanKm([]float64{100}) != nil {
		t.Error("a single reading cannot span a distance")
	}
	if DistanceSpanKm([]float64{100, 100}) != nil {
		t.Error("a zero span should be nil, not zero")
	}
	if got := DistanceSpanKm([]float64{300, 100, 250}); got == nil || *got != 200 {
		t.Errorf("span = %v, want 200", got)
	}
}

// Shares spread each source's spend over the whole distance, so they must sum to the combined
// running cost. If they did not, the dashboard's breakdown would contradict its headline.
func TestEnergySharesSumToTotalCostPerKm(t *testing.T) {
	purchases := []EnergyPurchase{
		{Source: Fuel("PETROL"), CostMinor: 6000, Quantity: 40},
		{Source: Fuel("LPG"), CostMinor: 2000, Quantity: 50},
		{Source: Electricity, CostMinor: 2000, Quantity: 30},
	}
	distance := ptr(1000.0)
	shares := EnergyBreakdown(purchases, distance)
	if len(shares) != 3 {
		t.Fatalf("got %d shares, want 3", len(shares))
	}
	if shares[0].Source.FuelType != "PETROL" {
		t.Errorf("shares should be biggest spender first, got %q", shares[0].Source.FuelType)
	}
	var sum float64
	for _, s := range shares {
		if s.CostPerKmMinor == nil {
			t.Fatalf("share %v has no cost per km", s.Source)
		}
		sum += *s.CostPerKmMinor
	}
	total := TotalEnergyCostPerKmMinor(purchases, distance)
	if total == nil {
		t.Fatal("total cost per km is nil")
	}
	approx(t, sum, *total, "sum of shares")
}

func TestNoPurchasesIsUnknownNotFree(t *testing.T) {
	if TotalEnergyCostPerKmMinor(nil, ptr(100)) != nil {
		t.Error("no purchases should be nil (unknown), not zero (free)")
	}
}

func TestOccurrencesInWindow(t *testing.T) {
	utc := time.UTC
	jan := time.Date(2026, 1, 15, 12, 0, 0, 0, utc).UnixMilli()
	junEnd := time.Date(2026, 6, 30, 0, 0, 0, 0, utc).UnixMilli()

	if got := OccurrencesInWindow(jan, "MONTHLY", jan, junEnd, 0, utc); got != 6 {
		t.Errorf("monthly Jan 15 - Jun 30 = %d, want 6", got)
	}
	// A one-off counts only if its own date lands in the window.
	if got := OccurrencesInWindow(jan, "NONE", jan, junEnd, 0, utc); got != 1 {
		t.Errorf("one-off inside window = %d, want 1", got)
	}
	before := time.Date(2025, 1, 1, 0, 0, 0, 0, utc).UnixMilli()
	if got := OccurrencesInWindow(before, "NONE", jan, junEnd, 0, utc); got != 0 {
		t.Errorf("one-off before window = %d, want 0", got)
	}
	// A loan anchored long ago still bills inside the window, but stops at its final payment.
	// Anchored on the 1st, so within [Jan 15, Mar 20] only Feb 1 and Mar 1 land: the Jan 1
	// bill predates the window start and the Apr 1 one is past the termination date.
	end := time.Date(2026, 3, 20, 0, 0, 0, 0, utc).UnixMilli()
	if got := OccurrencesInWindow(before, "MONTHLY", jan, junEnd, end, utc); got != 2 {
		t.Errorf("terminated monthly = %d, want 2 (Feb/Mar)", got)
	}
	// Without the termination date the same charge bills through to the window end.
	if got := OccurrencesInWindow(before, "MONTHLY", jan, junEnd, 0, utc); got != 5 {
		t.Errorf("open-ended monthly = %d, want 5 (Feb..Jun)", got)
	}
	if got := OccurrencesInWindow(jan, "MONTHLY", junEnd, jan, 0, utc); got != 0 {
		t.Errorf("inverted window = %d, want 0", got)
	}
}

func TestUnitConversions(t *testing.T) {
	// 235.214583 / 8 L/100km is the standard US MPG bridge.
	if got := FuelEconomyToDisplay(ptr(8), "IMPERIAL"); got == nil {
		t.Fatal("nil economy")
	} else {
		approx(t, *got, 29.401822875, "mpg")
	}
	if got := FuelEconomyToDisplay(ptr(8), "METRIC"); got == nil || *got != 8 {
		t.Errorf("metric economy = %v, want 8 unchanged", got)
	}
	if FuelEconomyToDisplay(ptr(0), "METRIC") != nil {
		t.Error("non-positive economy should be nil")
	}
	if got := ElectricEconomyToDisplay(ptr(20), "IMPERIAL"); got == nil {
		t.Fatal("nil electric economy")
	} else {
		approx(t, *got, (100/KmPerMile)/20, "mi/kWh")
	}
	if got := CurrencyFractionDigits("JPY"); got != 0 {
		t.Errorf("JPY digits = %d, want 0", got)
	}
	if got := CurrencyFractionDigits("BHD"); got != 3 {
		t.Errorf("BHD digits = %d, want 3", got)
	}
	if got := CurrencyFractionDigits("ZZZ"); got != 2 {
		t.Errorf("unknown currency digits = %d, want the 2 default", got)
	}
}
