package store

import (
	"context"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"crsib.me/fct-viewer/internal/webdav"
)

// putDirect writes straight into the cache, standing in for a completed download.
func putDirect(t *testing.T, c *Cache, rel string, size int) {
	t.Helper()
	if err := c.write(rel, make([]byte, size), webdav.Entry{ETag: rel}); err != nil {
		t.Fatalf("write %s: %v", rel, err)
	}
}

func TestCacheUncappedNeverEvicts(t *testing.T) {
	c, err := NewCache(t.TempDir(), 0)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 20; i++ {
		putDirect(t, c, "car/trips/t"+strconv.Itoa(i)+"/obd.csv.gz", 50_000)
	}
	total, files, err := c.Size()
	if err != nil {
		t.Fatal(err)
	}
	if files != 20 {
		t.Errorf("got %d files, want all 20 kept when uncapped", files)
	}
	if total != 20*50_000 {
		t.Errorf("total = %d, want %d", total, 20*50_000)
	}
}

func TestCacheEvictsLeastRecentlyUsed(t *testing.T) {
	const cap = 500_000
	c, err := NewCache(t.TempDir(), cap)
	if err != nil {
		t.Fatal(err)
	}

	// Fill well past the cap. Stamp each write with an increasing mtime so "oldest" is
	// unambiguous regardless of how fast the test runs.
	base := time.Now().Add(-time.Hour)
	for i := 0; i < 20; i++ {
		rel := "car/trips/t" + strconv.Itoa(i) + "/obd.csv.gz"
		putDirect(t, c, rel, 50_000)
		p, _ := c.localPath(rel)
		stamp := base.Add(time.Duration(i) * time.Minute)
		if err := os.Chtimes(p, stamp, stamp); err != nil {
			t.Fatal(err)
		}
	}
	// The writes above raced the eviction pass, so force a deterministic one now that every
	// file carries a known mtime.
	c.sizeEstimate = 20 * 50_000
	c.evictIfNeeded()

	total, _, err := c.Size()
	if err != nil {
		t.Fatal(err)
	}
	if total > cap {
		t.Errorf("cache is %d bytes, over the %d cap", total, cap)
	}

	// The newest entries must be the survivors.
	if !c.Has("car/trips/t19/obd.csv.gz") {
		t.Error("the most recently used file was evicted")
	}
	if c.Has("car/trips/t0/obd.csv.gz") {
		t.Error("the least recently used file survived eviction")
	}
	// A .meta orphaned by eviction would make the next Fetch think it had a valid entry.
	p, _ := c.localPath("car/trips/t0/obd.csv.gz")
	if _, err := os.Stat(p + ".meta"); !os.IsNotExist(err) {
		t.Error("eviction left the .meta sidecar behind")
	}
}

// Reading a file marks it as used, so a trip you keep opening is not the one thrown away.
func TestCacheReadRefreshesRecency(t *testing.T) {
	const cap = 300_000
	c, err := NewCache(t.TempDir(), cap)
	if err != nil {
		t.Fatal(err)
	}

	// Fill to just under the cap so nothing is evicted yet, with t0 the oldest.
	base := time.Now().Add(-time.Hour)
	for i := 0; i < 5; i++ {
		rel := "car/t" + strconv.Itoa(i)
		putDirect(t, c, rel, 50_000)
		p, _ := c.localPath(rel)
		stamp := base.Add(time.Duration(i) * time.Minute)
		if err := os.Chtimes(p, stamp, stamp); err != nil {
			t.Fatal(err)
		}
	}

	// Use the oldest entry through the normal read path. That should promote it to newest,
	// leaving t1 as the least-recently-used.
	if _, _, ok := c.read("car/t0"); !ok {
		t.Fatal("t0 should still be cached at this point")
	}

	// Now push past the cap, which triggers eviction.
	for i := 5; i < 7; i++ {
		putDirect(t, c, "car/t"+strconv.Itoa(i), 50_000)
	}

	total, _, err := c.Size()
	if err != nil {
		t.Fatal(err)
	}
	if total > cap {
		t.Errorf("cache is %d bytes, over the %d cap", total, cap)
	}
	if !c.Has("car/t0") {
		t.Error("the recently-read file was evicted despite being used")
	}
	if c.Has("car/t1") {
		t.Error("t1 was the least-recently-used entry and should have gone first")
	}
}

// Eviction must only ever touch data/. The catalog and the per-trip summaries are small and
// expensive to rebuild, and the analysis views keep working from them after the raw payloads
// they were derived from are gone.
func TestEvictionNeverTouchesSummariesOrCatalog(t *testing.T) {
	dir := t.TempDir()
	c, err := NewCache(dir, 100_000)
	if err != nil {
		t.Fatal(err)
	}
	summary := filepath.Join(dir, "summaries", "car", "trip-1.json")
	if err := os.MkdirAll(filepath.Dir(summary), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(summary, []byte(`{"tripSyncId":"trip-1"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	catalog := filepath.Join(dir, "catalog.json")
	if err := os.WriteFile(catalog, []byte(`{"trips":[]}`), 0o644); err != nil {
		t.Fatal(err)
	}

	for i := 0; i < 10; i++ {
		putDirect(t, c, "car/t"+strconv.Itoa(i), 50_000)
	}
	c.sizeEstimate = 10 * 50_000
	c.evictIfNeeded()

	if _, err := os.Stat(summary); err != nil {
		t.Errorf("eviction removed a trip summary: %v", err)
	}
	if _, err := os.Stat(catalog); err != nil {
		t.Errorf("eviction removed the catalog: %v", err)
	}
}

// Size must not count the .meta sidecars or leftover .tmp files, or the cap would be enforced
// against a number that does not match what the user sees on disk.
func TestSizeCountsPayloadsOnly(t *testing.T) {
	c, err := NewCache(t.TempDir(), 0)
	if err != nil {
		t.Fatal(err)
	}
	putDirect(t, c, "car/a", 1000)
	putDirect(t, c, "car/b", 2000)
	total, files, err := c.Size()
	if err != nil {
		t.Fatal(err)
	}
	if files != 2 || total != 3000 {
		t.Errorf("got %d files / %d bytes, want 2 / 3000", files, total)
	}
}

func TestCacheRejectsPathTraversal(t *testing.T) {
	c, err := NewCache(t.TempDir(), 0)
	if err != nil {
		t.Fatal(err)
	}
	for _, bad := range []string{"../escape", "a/../../escape", "", "C:/windows/system32"} {
		if _, err := c.localPath(bad); err == nil {
			t.Errorf("localPath(%q) should be rejected", bad)
		}
	}
}

// Fetch must serve a stale copy when the server is unreachable: an offline viewer showing
// last week's trips is far more useful than one showing an error.
func TestFetchFallsBackToStaleWhenServerIsDown(t *testing.T) {
	c, err := NewCache(t.TempDir(), 0)
	if err != nil {
		t.Fatal(err)
	}
	putDirect(t, c, "car/vehicle.json", 128)

	// A client pointed at a closed port stands in for "the server is gone".
	dead := webdav.New(webdav.Config{URL: "http://127.0.0.1:1/"}, nil)
	// A different ETag makes the cached copy stale, forcing the network path.
	got, err := c.Fetch(context.Background(), dead, "car/vehicle.json", webdav.Entry{ETag: "changed"})
	if err != nil {
		t.Fatalf("expected the stale copy, got error: %v", err)
	}
	if len(got) != 128 {
		t.Errorf("got %d bytes, want the 128-byte stale copy", len(got))
	}
}
