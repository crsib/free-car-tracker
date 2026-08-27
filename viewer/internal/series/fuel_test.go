package series

import (
	"math"
	"strings"
	"testing"
)

func mustParse(t *testing.T, csv string) *Frame {
	t.Helper()
	f, err := ParseOBD([]byte(csv))
	if err != nil {
		t.Fatal(err)
	}
	return f
}

// A steady 6 L/h for one hour is 6 litres, whatever the sampling looks like.
func TestIntegrateFuelRateOverTime(t *testing.T) {
	var b strings.Builder
	b.WriteString("timestampMillis,fuelRateLph\n")
	for i := 0; i <= 120; i++ { // two minutes at 1 Hz
		b.WriteString(itoa(int64(i)*1000) + ",6\n")
	}
	f := mustParse(t, b.String())

	litres, n := f.IntegrateOverTime("fuelRateLph", true)
	if n == 0 {
		t.Fatal("no intervals integrated")
	}
	want := 6.0 * (120.0 / 3600.0) // 6 L/h for 120 s
	if math.Abs(litres-want) > 1e-9 {
		t.Errorf("litres = %v, want %v", litres, want)
	}
}

// A metric that stops being polled must not be integrated across the silence: holding the last
// value over a ten-minute gap would invent fuel that was never burned.
func TestIntegrateDoesNotBridgeLongGaps(t *testing.T) {
	csv := "timestampMillis,fuelRateLph\n" +
		"0,6\n" +
		"1000,6\n" +
		"601000,6\n" + // ten-minute gap
		"602000,6\n"
	f := mustParse(t, csv)

	litres, n := f.IntegrateOverTime("fuelRateLph", true)
	if n != 2 {
		t.Errorf("integrated %d intervals, want 2 (the gap skipped)", n)
	}
	want := 6.0 * (2.0 / 3600.0)
	if math.Abs(litres-want) > 1e-9 {
		t.Errorf("litres = %v, want %v - the gap was bridged", litres, want)
	}
}

func TestIntegrateSkipsGapsInSparseColumns(t *testing.T) {
	// Fuel rate reported every other row; the blanks must not be read as zero.
	csv := "timestampMillis,fuelRateLph\n" +
		"0,3600\n" +
		"1000,\n" +
		"2000,3600\n"
	f := mustParse(t, csv)

	litres, n := f.IntegrateOverTime("fuelRateLph", true)
	if n != 1 {
		t.Errorf("intervals = %d, want 1 across the blank", n)
	}
	// 3600 L/h for 2 s = 2 L. Treating the blank as zero would have given 1 L.
	if math.Abs(litres-2.0) > 1e-9 {
		t.Errorf("litres = %v, want 2", litres)
	}
}

func TestIntegrateMissingColumn(t *testing.T) {
	f := mustParse(t, "timestampMillis,speedKmh\n0,10\n1000,10\n")
	if total, n := f.IntegrateOverTime("fuelRateLph", true); n != 0 || total != 0 {
		t.Errorf("missing column gave %v/%d, want 0/0", total, n)
	}
}

func TestSecondsWhere(t *testing.T) {
	csv := "timestampMillis,rpm\n" +
		"0,0\n" +
		"1000,0\n" +
		"2000,800\n" +
		"3000,900\n" + // one second with the engine turning at both ends
		"4000,0\n"
	f := mustParse(t, csv)

	secs, seen := f.SecondsWhere("rpm", func(v float64) bool { return v > engineRunningRpm })
	if !seen {
		t.Fatal("rpm column should have been seen")
	}
	if math.Abs(secs-1.0) > 1e-9 {
		t.Errorf("running seconds = %v, want 1", secs)
	}

	if _, seen := f.SecondsWhere("nonexistent", func(float64) bool { return true }); seen {
		t.Error("a missing column must report not-seen, which is what stops us claiming the engine was off")
	}
}

// The tank reading must survive one spurious sample at either end, because a single bad value
// would swing the whole litres estimate.
func TestTankLevelUsesRobustEdges(t *testing.T) {
	csv := "timestampMillis,fuelLevelPct\n" +
		"0,10\n" + // spurious dropout at the start
		"1000,80\n" +
		"2000,80\n" +
		"3000,80\n" +
		"4000,80\n" +
		"5000,70\n" +
		"6000,70\n" +
		"7000,70\n" +
		"8000,70\n" +
		"9000,99\n" // spurious spike at the end
	f := mustParse(t, csv)

	_, _, start, end, _, _ := f.FuelSignals(14.7, 745)
	if start == nil || end == nil {
		t.Fatal("tank readings missing")
	}
	if *start != 80 {
		t.Errorf("start = %v, want 80 - the 10%% dropout should not win", *start)
	}
	if *end != 70 {
		t.Errorf("end = %v, want 70 - the 99%% spike should not win", *end)
	}
}

func TestFuelSignalsFromMaf(t *testing.T) {
	// 14.7 g/s of air for 100 s = 1470 g air = 100 g fuel at stoich = 100/745 L.
	var b strings.Builder
	b.WriteString("timestampMillis,mafGramsPerSec\n")
	for i := 0; i <= 100; i++ {
		b.WriteString(itoa(int64(i)*1000) + ",14.7\n")
	}
	f := mustParse(t, b.String())

	_, maf, _, _, _, _ := f.FuelSignals(14.7, 745)
	if maf == nil {
		t.Fatal("no MAF-derived litres")
	}
	want := 100.0 / 745.0
	if math.Abs(*maf-want) > 1e-6 {
		t.Errorf("litres = %v, want %v", *maf, want)
	}
}

// The real shape that motivated all of this: a range-extender that ran on battery the whole
// way, with a tank gauge that wandered anyway.
func TestFuelSignalsForAnEngineThatNeverRan(t *testing.T) {
	csv := "timestampMillis,rpm,fuelLevelPct,speedKmh\n" +
		"0,0,100,0\n" +
		"1000,0,100,20\n" +
		"2000,0,92,40\n" +
		"3000,0,85.5,30\n"
	f := mustParse(t, csv)

	rate, maf, start, end, runSec, observed := f.FuelSignals(14.7, 745)
	if rate != nil || maf != nil {
		t.Error("no fuel-rate or MAF data was present, so neither should be derived")
	}
	if !observed {
		t.Error("rpm was reported, so the engine state was observed")
	}
	if runSec != 0 {
		t.Errorf("engine ran for %v s, want 0", runSec)
	}
	if start == nil || end == nil || *start <= *end {
		t.Errorf("tank should still be reported as dropping: %v -> %v", start, end)
	}
}

func TestEngineObservedFallsBackToLoad(t *testing.T) {
	csv := "timestampMillis,engineLoadPct\n0,0\n1000,0\n2000,40\n3000,40\n"
	f := mustParse(t, csv)

	_, _, _, _, runSec, observed := f.FuelSignals(14.7, 745)
	if !observed {
		t.Fatal("engine load should stand in for rpm when rpm is absent")
	}
	if math.Abs(runSec-1.0) > 1e-9 {
		t.Errorf("running seconds = %v, want 1", runSec)
	}
}

// Two samples are not a trip total. Integrating them anyway produces a number that looks like
// an answer, and it outranked a demonstrably stationary engine on a real trip.
func TestIntegralsNeedEnoughSamplesToCount(t *testing.T) {
	csv := "timestampMillis,mafGramsPerSec,rpm\n" +
		"0,0.31,0\n" +
		"1000,0.66,0\n"
	for i := 2; i < 12; i++ {
		csv += itoa(int64(i)*1000) + ",,0\n"
	}
	f := mustParse(t, csv)

	_, maf, _, _, runSec, observed := f.FuelSignals(14.7, 745)
	if maf != nil {
		t.Errorf("a 2-sample MAF integral was accepted as a trip total: %v L", *maf)
	}
	if !observed || runSec != 0 {
		t.Errorf("engine should read as observed and stationary, got observed=%v runSec=%v", observed, runSec)
	}
}

// An ECU reporting a stuck zero rpm while the engine is plainly under load must not have us
// declaring the engine off. The two activity signals are unioned, not ranked.
func TestEngineActivityUnionsRpmAndLoad(t *testing.T) {
	csv := "timestampMillis,rpm,engineLoadPct\n" +
		"0,0,40\n" +
		"1000,0,45\n" +
		"2000,0,50\n"
	f := mustParse(t, csv)

	_, _, _, _, runSec, observed := f.FuelSignals(14.7, 745)
	if !observed {
		t.Fatal("engine activity should have been observed")
	}
	if runSec <= 0 {
		t.Errorf("run time = %v; engine load says it was working despite rpm reading zero", runSec)
	}
}
