package domain

import (
	"sort"
	"time"
)

// EnergySource is one separately-costed energy source: a fuel type, or electricity.
// FuelType is empty for electricity and for fuel entries logged without a type.
type EnergySource struct {
	Kind     string `json:"kind"` // "FUEL" or "CHARGE"
	FuelType string `json:"fuelType,omitempty"`
}

var Electricity = EnergySource{Kind: "CHARGE"}

func Fuel(fuelType string) EnergySource { return EnergySource{Kind: "FUEL", FuelType: fuelType} }

// EnergyPurchase is one purchase reduced to what the cost breakdown needs.
// Quantity is litres for fuel, kWh for a charge.
type EnergyPurchase struct {
	Source    EnergySource
	CostMinor int64
	Quantity  float64
}

// EnergyCostShare is what one source cost over a window and its share of the per-km cost.
//
// Every share spreads that source's spend over the *whole* distance driven in the window,
// not over some distance attributed to it — a car cannot say which kilometres ran on LPG and
// which on petrol. Read a share as "what this source adds to each kilometre"; the shares
// therefore sum to the total energy cost per km.
type EnergyCostShare struct {
	Source         EnergySource `json:"source"`
	SpentMinor     int64        `json:"spentMinor"`
	Quantity       float64      `json:"quantity"`
	CostPerKmMinor *float64     `json:"costPerKmMinor"`
}

// EnergyBreakdown splits energy spend into per-source running costs, biggest spender first.
func EnergyBreakdown(purchases []EnergyPurchase, distanceKm *float64) []EnergyCostShare {
	type acc struct {
		spent int64
		qty   float64
	}
	order := []EnergySource{}
	totals := map[EnergySource]*acc{}
	for _, p := range purchases {
		a, ok := totals[p.Source]
		if !ok {
			a = &acc{}
			totals[p.Source] = a
			order = append(order, p.Source)
		}
		a.spent += p.CostMinor
		a.qty += p.Quantity
	}
	out := make([]EnergyCostShare, 0, len(order))
	for _, src := range order {
		a := totals[src]
		if a.spent == 0 && a.qty <= 0 {
			continue
		}
		out = append(out, EnergyCostShare{
			Source:         src,
			SpentMinor:     a.spent,
			Quantity:       a.qty,
			CostPerKmMinor: CostPerKmMinor(a.spent, distanceKm),
		})
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].SpentMinor > out[j].SpentMinor })
	return out
}

// TotalEnergyCostPerKmMinor is the combined per-km cost of every energy source, excluding
// other expenses. Nil when nothing was bought — that is "unknown", not "free".
func TotalEnergyCostPerKmMinor(purchases []EnergyPurchase, distanceKm *float64) *float64 {
	if len(purchases) == 0 {
		return nil
	}
	var sum int64
	for _, p := range purchases {
		sum += p.CostMinor
	}
	return CostPerKmMinor(sum, distanceKm)
}

// --- Recurrence ---

const recurrenceGuard = 100_000

// OccurrencesInWindow counts billing dates in [windowStart, windowEnd] for a charge anchored
// at startMillis. A one-off ("NONE") counts once if its own date falls in the window;
// occurrences before the window are skipped; endMillis (when non-zero) stops a fixed-term
// cost on its final payment. Calendar arithmetic runs in loc so a monthly bill keeps its
// day-of-month across DST, matching the Kotlin using ZoneId.systemDefault().
func OccurrencesInWindow(startMillis int64, recurrence string, windowStart, windowEnd int64, endMillis int64, loc *time.Location) int {
	if windowEnd < windowStart {
		return 0
	}
	upper := windowEnd
	if endMillis != 0 && endMillis < upper {
		upper = endMillis
	}
	if upper < windowStart {
		return 0
	}
	if recurrence == "" || recurrence == "NONE" {
		if startMillis >= windowStart && startMillis <= upper {
			return 1
		}
		return 0
	}
	if loc == nil {
		loc = time.Local
	}
	occ := time.UnixMilli(startMillis).In(loc)
	count := 0
	for guard := 0; guard < recurrenceGuard; guard++ {
		ms := occ.UnixMilli()
		if ms > upper {
			break
		}
		if ms >= windowStart {
			count++
		}
		switch recurrence {
		case "WEEKLY":
			occ = occ.AddDate(0, 0, 7)
		case "MONTHLY":
			occ = occ.AddDate(0, 1, 0)
		case "QUARTERLY":
			occ = occ.AddDate(0, 3, 0)
		case "YEARLY":
			occ = occ.AddDate(1, 0, 0)
		default:
			return count
		}
	}
	return count
}
