package api

import (
	"crypto/tls"
	"encoding/csv"
	"net/http"
	"sort"
	"strconv"
	"time"

	"crsib.me/fct-viewer/internal/config"
	"crsib.me/fct-viewer/internal/domain"
	"crsib.me/fct-viewer/internal/model"
	"crsib.me/fct-viewer/internal/s3"
	"crsib.me/fct-viewer/internal/store"
	"crsib.me/fct-viewer/internal/webdav"
)

// ClientConfig turns the app config into a WebDAV client config, so pacing settings are
// applied identically everywhere a client is built (startup, connection test, and save).
func ClientConfig(cfg config.Config) webdav.Config {
	return webdav.Config{
		URL:               cfg.URL,
		Username:          cfg.Username,
		Password:          cfg.Password,
		Insecure:          cfg.Insecure,
		RequestsPerSecond: cfg.EffectiveRPS(),
		MaxRetries:        cfg.EffectiveRetries(),
	}
}

// NewBackend builds the client for whichever backend cfg selects, or nil when it is not
// configured. It returns the interface so "not configured" is a true nil, never a nil
// *webdav.Client hiding inside a non-nil interface.
func NewBackend(cfg config.Config) webdav.Backend {
	if !cfg.Configured() {
		return nil
	}
	if cfg.EffectiveBackend() == config.BackendS3 {
		return s3.New(s3.Config{
			Endpoint:          cfg.S3.Endpoint,
			Region:            cfg.S3.Region,
			Bucket:            cfg.S3.Bucket,
			Prefix:            cfg.S3.Prefix,
			AccessKey:         cfg.S3.AccessKey,
			SecretKey:         cfg.S3.SecretKey,
			VirtualHost:       cfg.S3.VirtualHost,
			RequestsPerSecond: cfg.EffectiveRPS(),
			MaxRetries:        cfg.EffectiveRetries(),
		}, Transport(cfg.Insecure))
	}
	return webdav.New(ClientConfig(cfg), Transport(cfg.Insecure))
}

// Transport builds the HTTP transport for a WebDAV or S3 connection. Insecure is opt-in and only
// exists for a home server with a self-signed certificate; it is never the default. Exported
// so main builds the same client the setup endpoint does.
func Transport(insecure bool) http.RoundTripper {
	t := http.DefaultTransport.(*http.Transport).Clone()
	// Keep connections warm between paced requests; without this the default idle-per-host
	// of 2 would re-handshake constantly now that requests are spread out in time.
	t.MaxIdleConnsPerHost = 8
	if insecure {
		t.TLSClientConfig = &tls.Config{InsecureSkipVerify: true}
	}
	return t
}

type vehicleView struct {
	SyncID           string        `json:"syncId"`
	Vehicle          model.Vehicle `json:"vehicle"`
	TripCount        int           `json:"tripCount"`
	FuelCount        int           `json:"fuelCount"`
	ChargeCount      int           `json:"chargeCount"`
	ExpenseCount     int           `json:"expenseCount"`
	FirstEntryMillis int64         `json:"firstEntryMillis,omitempty"`
	LastEntryMillis  int64         `json:"lastEntryMillis,omitempty"`
	Units            unitLabels    `json:"units"`
}

type unitLabels struct {
	Distance        string `json:"distance"`
	Volume          string `json:"volume"`
	FuelEconomy     string `json:"fuelEconomy"`
	ElectricEconomy string `json:"electricEconomy"`
	Currency        string `json:"currency"`
	FractionDigits  int    `json:"fractionDigits"`
}

func unitsFor(v model.Vehicle) unitLabels {
	sys := string(v.UnitSystem)
	return unitLabels{
		Distance:        domain.DistanceUnit(sys),
		Volume:          domain.VolumeUnit(sys),
		FuelEconomy:     domain.FuelEconomyUnit(sys),
		ElectricEconomy: domain.ElectricEconomyUnit(sys),
		Currency:        v.Currency,
		FractionDigits:  domain.CurrencyFractionDigits(v.Currency),
	}
}

func (s *Server) handleVehicles(w http.ResponseWriter, r *http.Request) {
	cat, ok := s.catalog(w)
	if !ok {
		return
	}
	out := make([]vehicleView, 0, len(cat.Vehicles))
	for _, v := range cat.Vehicles {
		view := vehicleView{
			SyncID: v.SyncID, Vehicle: v.Vehicle, TripCount: v.TripCount,
			FuelCount:    len(v.Ledger.FuelEntries),
			ChargeCount:  len(v.Ledger.ChargeEntries),
			ExpenseCount: len(v.Ledger.Expenses),
			Units:        unitsFor(v.Vehicle),
		}
		for _, d := range ledgerDates(v) {
			if view.FirstEntryMillis == 0 || d < view.FirstEntryMillis {
				view.FirstEntryMillis = d
			}
			if d > view.LastEntryMillis {
				view.LastEntryMillis = d
			}
		}
		out = append(out, view)
	}
	writeJSON(w, out)
}

func ledgerDates(v store.VehicleRecord) []int64 {
	out := make([]int64, 0, len(v.Ledger.FuelEntries)+len(v.Ledger.ChargeEntries)+len(v.Ledger.Expenses))
	for _, e := range v.Ledger.FuelEntries {
		out = append(out, e.DateMillis)
	}
	for _, e := range v.Ledger.ChargeEntries {
		out = append(out, e.DateMillis)
	}
	for _, e := range v.Ledger.Expenses {
		out = append(out, e.DateMillis)
	}
	return out
}

// --- Dashboard ---

type categoryTotal struct {
	Category   string `json:"category"`
	TotalMinor int64  `json:"totalMinor"`
	// Entries is how many logged entries fed this bucket, Bills how many billing dates they
	// expanded to. Bills > Entries means a recurring cost was counted more than once; the UI
	// says so out loud, because otherwise the total cannot be reconciled with the ledger.
	Entries int `json:"entries"`
	Bills   int `json:"bills"`
}

type trendPoint struct {
	DateMillis     int64   `json:"dateMillis"`
	LitresPer100Km float64 `json:"litresPer100Km"`
}

type dashboardResponse struct {
	Vehicle     model.Vehicle `json:"vehicle"`
	Units       unitLabels    `json:"units"`
	WindowDays  int           `json:"windowDays"`
	SinceMillis int64         `json:"sinceMillis"`
	NowMillis   int64         `json:"nowMillis"`

	LatestFuelEconomyL100 *float64 `json:"latestFuelEconomyL100"`
	WindowFuelEconomyL100 *float64 `json:"windowFuelEconomyL100"`
	ElectricEconomyKwh100 *float64 `json:"electricEconomyKwh100"`
	WindowDistanceKm      *float64 `json:"windowDistanceKm"`
	WindowFuelVolumeL     float64  `json:"windowFuelVolumeL"`
	WindowEnergyKwh       float64  `json:"windowEnergyKwh"`

	TotalSpendMinor      int64                    `json:"totalSpendMinor"`
	CostPerKmMinor       *float64                 `json:"costPerKmMinor"`
	EnergyCostPerKmMinor *float64                 `json:"energyCostPerKmMinor"`
	EnergyCostShares     []domain.EnergyCostShare `json:"energyCostShares"`
	Buckets              []categoryTotal          `json:"buckets"`

	Trend                  []trendPoint           `json:"trend"`
	FuelSegments           []domain.FuelSegment   `json:"fuelSegments"`
	ChargeSegments         []domain.ChargeSegment `json:"chargeSegments"`
	WindowSegmentEconomies []float64              `json:"windowSegmentEconomiesL100"`
	WindowSegmentElectric  []float64              `json:"windowSegmentElectricKwh100"`
}

// handleDashboard reproduces DashboardViewModel.perVehicleState: same window rule, same
// full-tank economy, same recurring-expense expansion. The viewer is only useful if its
// numbers are the phone's numbers.
func (s *Server) handleDashboard(w http.ResponseWriter, r *http.Request) {
	cat, ok := s.catalog(w)
	if !ok {
		return
	}
	v, found := cat.FindVehicle(r.PathValue("vehicle"))
	if !found {
		writeErr(w, http.StatusNotFound, "no such car")
		return
	}
	days := intParam(r, "windowDays", 90)
	now := time.Now().UnixMilli()
	// windowDays <= 0 is the app's ALL_TIME sentinel: no lower bound at all.
	var since int64 = -1 << 62
	if days > 0 {
		since = now - int64(days)*24*60*60*1000
	}

	fuel, charge, expenses := v.Ledger.FuelEntries, v.Ledger.ChargeEntries, v.Ledger.Expenses

	fuelPoints := make([]domain.FuelPoint, 0, len(fuel))
	for _, f := range fuel {
		odo := f.OdometerKm
		fuelPoints = append(fuelPoints, domain.FuelPoint{
			OdometerKm: &odo, VolumeL: f.VolumeL, IsFullTank: f.IsFullTank, DateMillis: f.DateMillis,
		})
	}

	// Distance in the window comes from any odometer-bearing entry, fuel or charge.
	var windowOdos []float64
	for _, f := range fuel {
		if f.DateMillis >= since {
			windowOdos = append(windowOdos, f.OdometerKm)
		}
	}
	for _, c := range charge {
		if c.DateMillis >= since && c.OdometerKm != nil {
			windowOdos = append(windowOdos, *c.OdometerKm)
		}
	}
	windowDistance := domain.DistanceSpanKm(windowOdos)

	var windowFuelL, windowKwh float64
	var fuelCost, chargeCost int64
	var fuelEntries, chargeEntries int
	var purchases []domain.EnergyPurchase
	for _, f := range fuel {
		if f.DateMillis < since {
			continue
		}
		windowFuelL += f.VolumeL
		if f.DateMillis <= now {
			fuelCost += f.TotalCostMinor
			fuelEntries++
			ft := ""
			if f.FuelType != nil {
				ft = *f.FuelType
			}
			purchases = append(purchases, domain.EnergyPurchase{
				Source: domain.Fuel(ft), CostMinor: f.TotalCostMinor, Quantity: f.VolumeL,
			})
		}
	}
	for _, c := range charge {
		if c.DateMillis < since {
			continue
		}
		windowKwh += c.EnergyKwh
		if c.DateMillis <= now {
			chargeCost += c.TotalCostMinor
			chargeEntries++
			purchases = append(purchases, domain.EnergyPurchase{
				Source: domain.Electricity, CostMinor: c.TotalCostMinor, Quantity: c.EnergyKwh,
			})
		}
	}

	// Recurring expenses are expanded so every billing date inside the window counts, even
	// though the entry was logged once.
	expenseBuckets := map[string]int64{}
	expenseBills := map[string]int{}
	expenseEntries := map[string]int{}
	var bucketOrder []string
	for _, e := range expenses {
		var end int64
		if e.RecurrenceEndMillis != nil {
			end = *e.RecurrenceEndMillis
		}
		bills := domain.OccurrencesInWindow(e.DateMillis, e.Recurrence, since, now, end, time.Local)
		if bills <= 0 {
			continue
		}
		if _, seen := expenseBuckets[e.Category]; !seen {
			bucketOrder = append(bucketOrder, e.Category)
		}
		expenseBuckets[e.Category] += e.TotalCostMinor * int64(bills)
		expenseBills[e.Category] += bills
		expenseEntries[e.Category]++
	}

	total := fuelCost + chargeCost
	for _, v := range expenseBuckets {
		total += v
	}
	buckets := []categoryTotal{}
	// A fill or a charge is billed the day it happens, so for those a bill *is* an entry.
	if fuelCost > 0 {
		buckets = append(buckets, categoryTotal{"FUEL", fuelCost, fuelEntries, fuelEntries})
	}
	if chargeCost > 0 {
		buckets = append(buckets, categoryTotal{"CHARGE", chargeCost, chargeEntries, chargeEntries})
	}
	for _, k := range bucketOrder {
		buckets = append(buckets, categoryTotal{k, expenseBuckets[k], expenseEntries[k], expenseBills[k]})
	}
	sort.SliceStable(buckets, func(i, j int) bool { return buckets[i].TotalMinor > buckets[j].TotalMinor })

	segments := domain.FuelSegments(fuelPoints)
	trend := []trendPoint{}
	windowSegs := []float64{}
	for _, seg := range segments {
		if seg.EndDateMillis < since {
			continue
		}
		trend = append(trend, trendPoint{seg.EndDateMillis, seg.LitresPer100Km()})
		windowSegs = append(windowSegs, seg.LitresPer100Km())
	}
	if len(trend) > 12 {
		trend = trend[len(trend)-12:]
	}

	chargePoints := make([]domain.ChargePoint, 0, len(charge))
	for _, c := range charge {
		chargePoints = append(chargePoints, domain.ChargePoint{
			OdometerKm: c.OdometerKm, EnergyKwh: c.EnergyKwh, DateMillis: c.DateMillis,
		})
	}
	chargeSegs := domain.ChargeSegments(chargePoints)
	windowElectric := []float64{}
	for _, seg := range chargeSegs {
		if seg.EndDateMillis >= since {
			windowElectric = append(windowElectric, seg.KwhPer100Km())
		}
	}

	writeJSON(w, dashboardResponse{
		Vehicle: v.Vehicle, Units: unitsFor(v.Vehicle),
		WindowDays: days, SinceMillis: since, NowMillis: now,

		LatestFuelEconomyL100: domain.LatestFuelEconomyL100(fuelPoints),
		WindowFuelEconomyL100: domain.Per100Km(windowFuelL, windowDistance),
		ElectricEconomyKwh100: domain.Per100Km(windowKwh, windowDistance),
		WindowDistanceKm:      windowDistance,
		WindowFuelVolumeL:     windowFuelL,
		WindowEnergyKwh:       windowKwh,

		TotalSpendMinor:      total,
		CostPerKmMinor:       domain.CostPerKmMinor(total, windowDistance),
		EnergyCostPerKmMinor: domain.TotalEnergyCostPerKmMinor(purchases, windowDistance),
		EnergyCostShares:     domain.EnergyBreakdown(purchases, windowDistance),
		Buckets:              buckets,

		Trend:                  trend,
		FuelSegments:           segments,
		ChargeSegments:         chargeSegs,
		WindowSegmentEconomies: windowSegs,
		WindowSegmentElectric:  windowElectric,
	})
}

// --- Ledger ---

// ledgerRow flattens the three entry types into one shape so the UI can render, sort and
// filter a single table instead of three that never line up.
type ledgerRow struct {
	Kind           string   `json:"kind"`
	SyncID         string   `json:"syncId"`
	VehicleSyncID  string   `json:"vehicleSyncId"`
	VehicleName    string   `json:"vehicleName"`
	DateMillis     int64    `json:"dateMillis"`
	OdometerKm     *float64 `json:"odometerKm"`
	Quantity       *float64 `json:"quantity"` // litres for fuel, kWh for a charge
	QuantityUnit   string   `json:"quantityUnit"`
	TotalCostMinor int64    `json:"totalCostMinor"`
	Currency       string   `json:"currency"`
	FractionDigits int      `json:"fractionDigits"`
	Category       string   `json:"category"` // fuel type, charge type, or expense category
	IsFullTank     *bool    `json:"isFullTank,omitempty"`
	Detail         string   `json:"detail"` // station / location / vendor
	Note           string   `json:"note"`
	Recurrence     string   `json:"recurrence,omitempty"`
	UnitPriceMinor *float64 `json:"unitPriceMinor,omitempty"`
	// Projected marks a row the ledger derived rather than read: a repeat of a recurring
	// expense, dated at its billing date. AnchorDateMillis is the logged entry it repeats.
	Projected        bool  `json:"projected,omitempty"`
	AnchorDateMillis int64 `json:"anchorDateMillis,omitempty"`
}

func str(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

func unitPrice(costMinor int64, qty float64) *float64 {
	if qty <= 0 {
		return nil
	}
	v := float64(costMinor) / qty
	return &v
}

func (s *Server) ledgerRows(r *http.Request, cat *store.Catalog) []ledgerRow {
	vehicleFilter := r.URL.Query().Get("vehicle")
	kinds := map[string]bool{}
	for _, k := range listParam(r, "kind") {
		kinds[k] = true
	}
	from := int64Param(r, "from", 0)
	to := int64Param(r, "to", 0)
	// Projected repeats are on unless asked for the logged entries alone.
	withBills := r.URL.Query().Get("bills") != "0"
	now := time.Now().UnixMilli()

	inRange := func(d int64) bool {
		return (from == 0 || d >= from) && (to == 0 || d <= to)
	}
	want := func(kind string) bool { return len(kinds) == 0 || kinds[kind] }

	var rows []ledgerRow
	for _, v := range cat.Vehicles {
		if vehicleFilter != "" && v.SyncID != vehicleFilter {
			continue
		}
		digits := domain.CurrencyFractionDigits(v.Vehicle.Currency)
		if want("FUEL") {
			for _, e := range v.Ledger.FuelEntries {
				if !inRange(e.DateMillis) {
					continue
				}
				odo, qty, full := e.OdometerKm, e.VolumeL, e.IsFullTank
				rows = append(rows, ledgerRow{
					Kind: "FUEL", SyncID: e.SyncID, VehicleSyncID: v.SyncID, VehicleName: v.Vehicle.Name,
					DateMillis: e.DateMillis, OdometerKm: &odo, Quantity: &qty, QuantityUnit: "L",
					TotalCostMinor: e.TotalCostMinor, Currency: e.Currency, FractionDigits: digits,
					Category: str(e.FuelType), IsFullTank: &full, Detail: str(e.Station), Note: str(e.Note),
					UnitPriceMinor: unitPrice(e.TotalCostMinor, e.VolumeL),
				})
			}
		}
		if want("CHARGE") {
			for _, e := range v.Ledger.ChargeEntries {
				if !inRange(e.DateMillis) {
					continue
				}
				qty := e.EnergyKwh
				rows = append(rows, ledgerRow{
					Kind: "CHARGE", SyncID: e.SyncID, VehicleSyncID: v.SyncID, VehicleName: v.Vehicle.Name,
					DateMillis: e.DateMillis, OdometerKm: e.OdometerKm, Quantity: &qty, QuantityUnit: "kWh",
					TotalCostMinor: e.TotalCostMinor, Currency: e.Currency, FractionDigits: digits,
					Category: e.ChargeType, Detail: str(e.Location), Note: str(e.Note),
					UnitPriceMinor: unitPrice(e.TotalCostMinor, e.EnergyKwh),
				})
			}
		}
		if want("EXPENSE") {
			for _, e := range v.Ledger.Expenses {
				row := func(date int64, projected bool) ledgerRow {
					lr := ledgerRow{
						Kind: "EXPENSE", SyncID: e.SyncID, VehicleSyncID: v.SyncID, VehicleName: v.Vehicle.Name,
						DateMillis: date, OdometerKm: e.OdometerKm,
						TotalCostMinor: e.TotalCostMinor, Currency: e.Currency, FractionDigits: digits,
						Category: e.Category, Detail: str(e.Vendor), Note: str(e.Description),
						Recurrence: e.Recurrence,
					}
					if projected {
						lr.Projected = true
						lr.AnchorDateMillis = e.DateMillis
						// The odometer was read on the day the entry was logged; carrying it
						// onto a later bill would invent a reading that was never taken.
						lr.OdometerKm = nil
					}
					return lr
				}
				// The entry as logged. Its own date is the first billing date.
				if inRange(e.DateMillis) {
					rows = append(rows, row(e.DateMillis, false))
				}
				if !withBills || e.Recurrence == "" || e.Recurrence == "NONE" {
					continue
				}
				// ...and the repeats it stands for. Without these a date-filtered ledger cannot
				// be reconciled with the dashboard, which counts every billing date in its
				// window, and a monthly cost anchored years ago shows up in no window at all.
				var end int64
				if e.RecurrenceEndMillis != nil {
					end = *e.RecurrenceEndMillis
				}
				lower, upper := from, to
				if lower == 0 {
					lower = e.DateMillis
				}
				if upper == 0 {
					upper = now
				}
				for _, d := range domain.OccurrenceDatesInWindow(e.DateMillis, e.Recurrence, lower, upper, end, time.Local) {
					if d == e.DateMillis {
						continue // already listed as the logged entry
					}
					rows = append(rows, row(d, true))
				}
			}
		}
	}
	sort.SliceStable(rows, func(i, j int) bool { return rows[i].DateMillis > rows[j].DateMillis })
	return rows
}

func (s *Server) handleLedger(w http.ResponseWriter, r *http.Request) {
	cat, ok := s.catalog(w)
	if !ok {
		return
	}
	writeJSON(w, s.ledgerRows(r, cat))
}

func (s *Server) handleLedgerCSV(w http.ResponseWriter, r *http.Request) {
	cat, ok := s.catalog(w)
	if !ok {
		return
	}
	rows := s.ledgerRows(r, cat)

	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="car-tracker-ledger.csv"`)
	cw := csv.NewWriter(w)
	defer cw.Flush()
	_ = cw.Write([]string{
		"kind", "vehicle", "date", "dateMillis", "odometerKm", "quantity", "quantityUnit",
		"total", "currency", "unitPrice", "category", "fullTank", "detail", "note", "recurrence",
		"projected", "anchorDate", "syncId",
	})
	for _, row := range rows {
		major := func(minor float64) string {
			return strconv.FormatFloat(minor/pow10(row.FractionDigits), 'f', row.FractionDigits, 64)
		}
		rec := []string{
			row.Kind, row.VehicleName,
			time.UnixMilli(row.DateMillis).Format(time.RFC3339),
			strconv.FormatInt(row.DateMillis, 10),
			fmtPtr(row.OdometerKm), fmtPtr(row.Quantity), row.QuantityUnit,
			major(float64(row.TotalCostMinor)), row.Currency,
		}
		if row.UnitPriceMinor != nil {
			rec = append(rec, strconv.FormatFloat(*row.UnitPriceMinor/pow10(row.FractionDigits), 'f', row.FractionDigits+1, 64))
		} else {
			rec = append(rec, "")
		}
		full := ""
		if row.IsFullTank != nil {
			full = strconv.FormatBool(*row.IsFullTank)
		}
		anchor := ""
		if row.AnchorDateMillis != 0 {
			anchor = time.UnixMilli(row.AnchorDateMillis).Format(time.RFC3339)
		}
		rec = append(rec, row.Category, full, row.Detail, row.Note, row.Recurrence,
			strconv.FormatBool(row.Projected), anchor, row.SyncID)
		_ = cw.Write(rec)
	}
}

func pow10(n int) float64 {
	v := 1.0
	for i := 0; i < n; i++ {
		v *= 10
	}
	return v
}

func fmtPtr(p *float64) string {
	if p == nil {
		return ""
	}
	return strconv.FormatFloat(*p, 'f', -1, 64)
}
