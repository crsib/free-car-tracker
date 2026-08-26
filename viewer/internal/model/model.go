// Package model mirrors the JSON documents written by the Android app's WebDAV sync
// (see app/src/main/java/me/crsib/freecartracker/data/sync/SyncDocuments.kt). Field names
// and nullability follow the Kotlin data classes exactly: kotlinx-serialization writes
// property names verbatim, enums as their constant name, and the sync writer runs with
// encodeDefaults = true, so every field is present. Optional Kotlin fields are pointers
// here so "absent" and "zero" stay distinguishable.
package model

// ---- Vehicle ----

type Powertrain string

const (
	ICE    Powertrain = "ICE"
	Hybrid Powertrain = "HYBRID"
	REV    Powertrain = "REV"
	EV     Powertrain = "EV"
)

func (p Powertrain) UsesFuel() bool     { return p == ICE || p == Hybrid || p == REV }
func (p Powertrain) UsesCharging() bool { return p == REV || p == EV }

type UnitSystem string

const (
	Metric   UnitSystem = "METRIC"
	Imperial UnitSystem = "IMPERIAL"
)

type Vehicle struct {
	ID                 Long       `json:"id"`
	Name               string     `json:"name"`
	Make               *string    `json:"make"`
	Model              *string    `json:"model"`
	Year               *int       `json:"year"`
	Plate              *string    `json:"plate"`
	Powertrain         Powertrain `json:"powertrain"`
	FuelType           *string    `json:"fuelType"`
	TankCapacityL      *float64   `json:"tankCapacityL"`
	BatteryCapacityKwh *float64   `json:"batteryCapacityKwh"`
	UnitSystem         UnitSystem `json:"unitSystem"`
	Currency           string     `json:"currency"`
	InitialOdometerKm  float64    `json:"initialOdometerKm"`
	ColorArgb          *int32     `json:"colorArgb"`
	IsArchived         bool       `json:"isArchived"`
	CreatedAt          Long       `json:"createdAt"`
	SyncID             string     `json:"syncId"`
}

// Long is an epoch-millis / row-id alias, kept explicit so the intent reads at call sites.
type Long = int64

type VehicleDocument struct {
	SchemaVersion int     `json:"schemaVersion"`
	ExportedAt    Long    `json:"exportedAt"`
	Vehicle       Vehicle `json:"vehicle"`
}

// ---- Ledger ----

type FuelEntry struct {
	ID              Long    `json:"id"`
	VehicleID       Long    `json:"vehicleId"`
	DateMillis      Long    `json:"dateMillis"`
	OdometerKm      float64 `json:"odometerKm"`
	VolumeL         float64 `json:"volumeL"`
	TotalCostMinor  Long    `json:"totalCostMinor"`
	Currency        string  `json:"currency"`
	IsFullTank      bool    `json:"isFullTank"`
	FuelType        *string `json:"fuelType"`
	Station         *string `json:"station"`
	Note            *string `json:"note"`
	SyncID          string  `json:"syncId"`
	UpdatedAtMillis Long    `json:"updatedAtMillis"`
}

type ChargeEntry struct {
	ID              Long     `json:"id"`
	VehicleID       Long     `json:"vehicleId"`
	DateMillis      Long     `json:"dateMillis"`
	OdometerKm      *float64 `json:"odometerKm"`
	EnergyKwh       float64  `json:"energyKwh"`
	TotalCostMinor  Long     `json:"totalCostMinor"`
	Currency        string   `json:"currency"`
	ChargeType      string   `json:"chargeType"`
	StartPct        *int     `json:"startPct"`
	EndPct          *int     `json:"endPct"`
	Location        *string  `json:"location"`
	Note            *string  `json:"note"`
	SyncID          string   `json:"syncId"`
	UpdatedAtMillis Long     `json:"updatedAtMillis"`
}

type Expense struct {
	ID                  Long     `json:"id"`
	VehicleID           Long     `json:"vehicleId"`
	DateMillis          Long     `json:"dateMillis"`
	OdometerKm          *float64 `json:"odometerKm"`
	Category            string   `json:"category"`
	TotalCostMinor      Long     `json:"totalCostMinor"`
	Currency            string   `json:"currency"`
	Vendor              *string  `json:"vendor"`
	Description         *string  `json:"description"`
	Recurrence          string   `json:"recurrence"`
	RecurrenceEndMillis *Long    `json:"recurrenceEndMillis"`
	NextDueDateMillis   *Long    `json:"nextDueDateMillis"`
	NextDueOdometerKm   *float64 `json:"nextDueOdometerKm"`
	SyncID              string   `json:"syncId"`
	UpdatedAtMillis     Long     `json:"updatedAtMillis"`
}

type LedgerTombstone struct {
	SyncID          string `json:"syncId"`
	DeletedAtMillis Long   `json:"deletedAtMillis"`
}

type LedgerDocument struct {
	SchemaVersion  int               `json:"schemaVersion"`
	ExportedAt     Long              `json:"exportedAt"`
	VehicleSyncID  string            `json:"vehicleSyncId"`
	FuelEntries    []FuelEntry       `json:"fuelEntries"`
	ChargeEntries  []ChargeEntry     `json:"chargeEntries"`
	Expenses       []Expense         `json:"expenses"`
	DeletedEntries []LedgerTombstone `json:"deletedEntries"`
}

// ---- Trips ----

type Trip struct {
	ID                 Long     `json:"id"`
	VehicleID          Long     `json:"vehicleId"`
	StartedAtMillis    Long     `json:"startedAtMillis"`
	EndedAtMillis      *Long    `json:"endedAtMillis"`
	Status             string   `json:"status"`
	DistanceKm         *float64 `json:"distanceKm"`
	DurationSec        *Long    `json:"durationSec"`
	AvgSpeedKmh        *float64 `json:"avgSpeedKmh"`
	MaxSpeedKmh        *float64 `json:"maxSpeedKmh"`
	MovingSec          *Long    `json:"movingSec"`
	FuelUsedL          *float64 `json:"fuelUsedL"`
	EnergyUsedKwh      *float64 `json:"energyUsedKwh"`
	AvgConsumptionL100 *float64 `json:"avgConsumptionL100"`
	ObdSampleCount     int      `json:"obdSampleCount"`
	GpsPointCount      int      `json:"gpsPointCount"`
	Transport          *string  `json:"transport"`
	ProfileKey         *string  `json:"profileKey"`
	Note               *string  `json:"note"`
	SyncID             string   `json:"syncId"`
	SyncedAtMillis     *Long    `json:"syncedAtMillis"`
}

type DtcRecord struct {
	ID              Long   `json:"id"`
	TripID          Long   `json:"tripId"`
	TimestampMillis Long   `json:"timestampMillis"`
	Code            string `json:"code"`
	Pending         bool   `json:"pending"`
}

type TripFileManifest struct {
	ObdCsvGz       *string `json:"obdCsvGz"`
	GpxGz          *string `json:"gpxGz"`
	ObdSampleCount int     `json:"obdSampleCount"`
	GpsPointCount  int     `json:"gpsPointCount"`
}

type TripManifest struct {
	SchemaVersion int              `json:"schemaVersion"`
	TripSyncID    string           `json:"tripSyncId"`
	VehicleSyncID string           `json:"vehicleSyncId"`
	Trip          Trip             `json:"trip"`
	Dtcs          []DtcRecord      `json:"dtcs"`
	Files         TripFileManifest `json:"files"`
}

// ---- GPS ----

// GpsPoint mirrors the app entity, rebuilt from track.gpx.gz. tripId is not carried:
// the trip is identified by its folder, and the local Room row id has no meaning here.
type GpsPoint struct {
	TimestampMillis Long     `json:"t"`
	Latitude        float64  `json:"lat"`
	Longitude       float64  `json:"lon"`
	AltitudeM       *float64 `json:"ele,omitempty"`
	SpeedKmh        *float64 `json:"speed,omitempty"`
	BearingDeg      *float64 `json:"bearing,omitempty"`
	AccuracyM       *float64 `json:"accuracy,omitempty"`
	Provider        string   `json:"provider,omitempty"`
}
