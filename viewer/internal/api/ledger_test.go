package api

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"crsib.me/fct-viewer/internal/model"
	"crsib.me/fct-viewer/internal/store"
)

// ledgerCatalog is one car whose only expense is a recurring one, anchored well before any
// window the tests ask for - the case that used to make a monthly cost invisible.
func ledgerCatalog(anchor time.Time, recurrence string, end *int64) *store.Catalog {
	return &store.Catalog{
		Vehicles: []store.VehicleRecord{{
			SyncID:  "car-1",
			Vehicle: model.Vehicle{Name: "Car", Currency: "RUB", SyncID: "car-1"},
			Ledger: model.LedgerDocument{
				Expenses: []model.Expense{{
					DateMillis:          anchor.UnixMilli(),
					Category:            "INSURANCE",
					TotalCostMinor:      4_500_000,
					Currency:            "RUB",
					Recurrence:          recurrence,
					RecurrenceEndMillis: end,
					SyncID:              "exp-1",
				}},
			},
			LedgerFound: true,
		}},
	}
}

func ledgerRequest(query string) *http.Request {
	return httptest.NewRequest(http.MethodGet, "/api/ledger?"+query, nil)
}

func TestLedgerProjectsRecurringExpensesIntoTheWindow(t *testing.T) {
	anchor := time.Date(2026, 1, 15, 9, 0, 0, 0, time.Local)
	cat := ledgerCatalog(anchor, "MONTHLY", nil)
	srv := &Server{}

	from := time.Date(2026, 4, 1, 0, 0, 0, 0, time.Local).UnixMilli()
	to := time.Date(2026, 6, 30, 23, 59, 59, 0, time.Local).UnixMilli()
	rows := srv.ledgerRows(ledgerRequest(sprintfQuery(from, to)), cat)

	// April, May and June bills - none of which is the logged entry, which sits in January.
	if len(rows) != 3 {
		t.Fatalf("rows = %d, want 3: %+v", len(rows), rows)
	}
	var total int64
	for _, r := range rows {
		if !r.Projected {
			t.Errorf("row dated %s is not marked projected", time.UnixMilli(r.DateMillis))
		}
		if r.AnchorDateMillis != anchor.UnixMilli() {
			t.Errorf("row anchor = %d, want %d", r.AnchorDateMillis, anchor.UnixMilli())
		}
		if r.OdometerKm != nil {
			t.Errorf("projected row invented an odometer reading: %v", *r.OdometerKm)
		}
		if day := time.UnixMilli(r.DateMillis).Day(); day != 15 {
			t.Errorf("bill on day %d, want the 15th", day)
		}
		total += r.TotalCostMinor
	}
	if want := int64(3 * 4_500_000); total != want {
		t.Errorf("window total = %d, want %d", total, want)
	}
}

func TestLedgerKeepsTheLoggedEntryAndCanDropTheRepeats(t *testing.T) {
	anchor := time.Now().AddDate(0, -3, 0)
	cat := ledgerCatalog(anchor, "MONTHLY", nil)
	srv := &Server{}

	// bills=0 is the old behaviour: what was written down, nothing derived.
	rows := srv.ledgerRows(ledgerRequest("bills=0"), cat)
	if len(rows) != 1 || rows[0].Projected {
		t.Fatalf("bills=0 rows = %+v, want just the logged entry", rows)
	}

	// Unfiltered, the entry is followed by every bill since, up to today and no further.
	rows = srv.ledgerRows(ledgerRequest(""), cat)
	if len(rows) < 2 {
		t.Fatalf("rows = %d, want the entry plus its repeats", len(rows))
	}
	logged := 0
	now := time.Now().UnixMilli()
	for _, r := range rows {
		if !r.Projected {
			logged++
			continue
		}
		if r.DateMillis <= anchor.UnixMilli() || r.DateMillis > now {
			t.Errorf("projected bill at %s is outside (anchor, now]", time.UnixMilli(r.DateMillis))
		}
	}
	if logged != 1 {
		t.Errorf("logged rows = %d, want exactly 1", logged)
	}
}

func TestLedgerStopsProjectingAtTheRecurrenceEnd(t *testing.T) {
	anchor := time.Date(2026, 1, 15, 9, 0, 0, 0, time.Local)
	end := time.Date(2026, 3, 20, 0, 0, 0, 0, time.Local).UnixMilli()
	cat := ledgerCatalog(anchor, "MONTHLY", &end)
	srv := &Server{}

	from := time.Date(2026, 2, 1, 0, 0, 0, 0, time.Local).UnixMilli()
	to := time.Date(2026, 6, 30, 0, 0, 0, 0, time.Local).UnixMilli()
	rows := srv.ledgerRows(ledgerRequest(sprintfQuery(from, to)), cat)

	// February and March are billed; the term ends before April.
	if len(rows) != 2 {
		t.Fatalf("rows = %d, want 2 (Feb, Mar): %+v", len(rows), rows)
	}
}

func TestLedgerDoesNotRepeatAOneOff(t *testing.T) {
	anchor := time.Date(2026, 1, 15, 9, 0, 0, 0, time.Local)
	cat := ledgerCatalog(anchor, "NONE", nil)
	srv := &Server{}

	from := time.Date(2026, 4, 1, 0, 0, 0, 0, time.Local).UnixMilli()
	to := time.Date(2026, 6, 30, 0, 0, 0, 0, time.Local).UnixMilli()
	if rows := srv.ledgerRows(ledgerRequest(sprintfQuery(from, to)), cat); len(rows) != 0 {
		t.Fatalf("a one-off outside the window produced %d rows: %+v", len(rows), rows)
	}
}

func sprintfQuery(from, to int64) string {
	return fmt.Sprintf("from=%d&to=%d", from, to)
}
