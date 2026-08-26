package domain

// Canonical storage units are metric: km, litres, kWh. These pure helpers convert to what a
// UnitSystem displays. Ported from domain/UnitConversions.kt.

const (
	KmPerMile         = 1.609344
	LitresPerUSGallon = 3.785411784
	imperialMPGBridge = 235.214583 // (100 km in miles) * (litres per US gallon)
)

func imperial(system string) bool { return system == "IMPERIAL" }

func KmToDisplay(km float64, system string) float64 {
	if imperial(system) {
		return km / KmPerMile
	}
	return km
}

func LitresToDisplay(l float64, system string) float64 {
	if imperial(system) {
		return l / LitresPerUSGallon
	}
	return l
}

// FuelEconomyToDisplay converts L/100km into L/100km (metric) or US MPG (imperial).
func FuelEconomyToDisplay(lPer100Km *float64, system string) *float64 {
	if lPer100Km == nil || *lPer100Km <= 0 {
		return nil
	}
	if imperial(system) {
		v := imperialMPGBridge / *lPer100Km
		return &v
	}
	return lPer100Km
}

// ElectricEconomyToDisplay converts kWh/100km into kWh/100km (metric) or mi/kWh (imperial).
func ElectricEconomyToDisplay(kwhPer100Km *float64, system string) *float64 {
	if kwhPer100Km == nil || *kwhPer100Km <= 0 {
		return nil
	}
	if imperial(system) {
		v := (100.0 / KmPerMile) / *kwhPer100Km
		return &v
	}
	return kwhPer100Km
}

func DistanceUnit(system string) string {
	if imperial(system) {
		return "mi"
	}
	return "km"
}

func VolumeUnit(system string) string {
	if imperial(system) {
		return "gal"
	}
	return "L"
}

func FuelEconomyUnit(system string) string {
	if imperial(system) {
		return "mpg"
	}
	return "L/100km"
}

func ElectricEconomyUnit(system string) string {
	if imperial(system) {
		return "mi/kWh"
	}
	return "kWh/100km"
}

// CurrencyFractionDigits is the minor-unit exponent for an ISO 4217 code, so integer minor
// units render correctly. Go has no currency database, so this lists the codes that are not
// the 2-digit default; everything unknown falls back to 2, as Money.kt does.
func CurrencyFractionDigits(code string) int {
	switch code {
	case "JPY", "KRW", "VND", "CLP", "ISK", "PYG", "RWF", "UGX", "VUV", "XAF", "XOF", "XPF", "BIF", "DJF", "GNF", "KMF", "MGA":
		return 0
	case "BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND":
		return 3
	default:
		return 2
	}
}
