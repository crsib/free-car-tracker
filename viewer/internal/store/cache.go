// Package store owns the local mirror of the WebDAV tree: a byte cache of downloaded
// resources, a catalog index built by walking the remote layout, and the derived per-trip
// summaries that make cross-trip analysis possible without re-downloading anything.
package store

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"crsib.me/fct-viewer/internal/webdav"
)

// Cache stores downloaded WebDAV payloads on disk, keyed by remote path.
//
// Trip side files are the reason this exists: obd.csv.gz runs to tens of megabytes and a
// trip, once uploaded, essentially never changes. Re-fetching one every time a chart is
// drawn would make the viewer unusable over a home uplink. Freshness is decided by the
// ETag (or last-modified + size) carried in the PROPFIND listing, so an edited trip is
// still picked up.
type Cache struct {
	dir string
	mu  sync.Mutex
	// inflight collapses concurrent requests for the same resource into one download, so
	// opening a trip while a prefetch is running does not fetch the same file twice.
	inflight map[string]*sync.WaitGroup

	// maxBytes caps the payload cache; 0 means unlimited. It matters on a small server:
	// a year of 10 Hz trips is gigabytes, and an unbounded cache would fill the disk of
	// whatever box this is deployed on. Eviction is least-recently-used.
	maxBytes int64
	// sizeEstimate avoids walking the whole cache on every write. It is seeded by one walk
	// at startup and adjusted on write; a real walk only happens when it says we are over.
	sizeEstimate int64
}

type cacheMeta struct {
	ETag         string    `json:"etag,omitempty"`
	LastModified time.Time `json:"lastModified,omitempty"`
	Size         int64     `json:"size"`
	FetchedAt    time.Time `json:"fetchedAt"`
}

// NewCache opens (or creates) a cache directory. maxBytes caps the stored payloads; pass 0
// for no limit, which is the sensible default on a desktop with room to spare.
func NewCache(dir string, maxBytes int64) (*Cache, error) {
	if err := os.MkdirAll(filepath.Join(dir, "data"), 0o755); err != nil {
		return nil, err
	}
	c := &Cache{dir: dir, inflight: map[string]*sync.WaitGroup{}, maxBytes: maxBytes}
	if maxBytes > 0 {
		if total, _, err := c.Size(); err == nil {
			c.sizeEstimate = total
		}
	}
	return c, nil
}

func (c *Cache) Dir() string { return c.dir }

// MaxBytes is the configured cap, 0 when unlimited.
func (c *Cache) MaxBytes() int64 { return c.maxBytes }

// localPath maps a remote path to a file under the cache. Remote names are UUIDs and fixed
// filenames, but the path still comes from a server response, so every segment is checked:
// a "..", an absolute path or a Windows drive letter must never escape the cache directory.
func (c *Cache) localPath(rel string) (string, error) {
	clean := strings.ReplaceAll(strings.Trim(rel, "/"), "\\", "/")
	if clean == "" {
		return "", errors.New("cache: empty path")
	}
	parts := strings.Split(clean, "/")
	for _, p := range parts {
		if p == "" || p == "." || p == ".." || strings.ContainsAny(p, `:<>"|?*`) {
			return "", fmt.Errorf("cache: unsafe path segment %q", p)
		}
	}
	// Very deep or very long remote paths can exceed the filesystem limit; fall back to a
	// hash of the path in that case so the cache degrades rather than failing.
	joined := filepath.Join(append([]string{c.dir, "data"}, parts...)...)
	if len(joined) > 240 {
		sum := sha256.Sum256([]byte(clean))
		joined = filepath.Join(c.dir, "data", "_long", hex.EncodeToString(sum[:16])+filepath.Ext(clean))
	}
	return joined, nil
}

func (c *Cache) read(rel string) ([]byte, cacheMeta, bool) {
	path, err := c.localPath(rel)
	if err != nil {
		return nil, cacheMeta{}, false
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, cacheMeta{}, false
	}
	var meta cacheMeta
	if mb, err := os.ReadFile(path + ".meta"); err == nil {
		_ = json.Unmarshal(mb, &meta)
	}
	// Stamp the file as recently used so eviction sheds what nobody looks at. Access times
	// are unreliable (relatime, noatime), so modification time is the LRU clock instead —
	// one extra syscall per cache hit, and only when a cap is actually in force.
	if c.maxBytes > 0 {
		now := time.Now()
		_ = os.Chtimes(path, now, now)
	}
	return data, meta, true
}

// evictIfNeeded sheds least-recently-used payloads until the cache is comfortably under the
// cap. It only touches data/: the catalog and the per-trip summaries live outside it and are
// deliberately never evicted — they are tiny, expensive to rebuild, and the analysis views
// keep working off them even after the raw payloads they were computed from are gone.
func (c *Cache) evictIfNeeded() {
	if c.maxBytes <= 0 || c.sizeEstimate <= c.maxBytes {
		return
	}
	total, _, err := c.Size()
	if err != nil {
		return
	}
	c.sizeEstimate = total
	if total <= c.maxBytes {
		return
	}

	type entry struct {
		path string
		size int64
		mod  time.Time
	}
	var entries []entry
	root := filepath.Join(c.dir, "data")
	_ = filepath.WalkDir(root, func(p string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || strings.HasSuffix(d.Name(), ".meta") || strings.HasSuffix(d.Name(), ".tmp") {
			return nil
		}
		if info, err := d.Info(); err == nil {
			entries = append(entries, entry{p, info.Size(), info.ModTime()})
		}
		return nil
	})
	sort.Slice(entries, func(i, j int) bool { return entries[i].mod.Before(entries[j].mod) })

	// Drop to 85% of the cap rather than exactly to it, so a cache sitting right at the
	// limit does not evict on every single write.
	target := c.maxBytes * 85 / 100
	for _, e := range entries {
		if total <= target {
			break
		}
		if err := os.Remove(e.path); err != nil {
			continue
		}
		_ = os.Remove(e.path + ".meta")
		total -= e.size
	}
	c.sizeEstimate = total
}

func (c *Cache) write(rel string, data []byte, entry webdav.Entry) error {
	path, err := c.localPath(rel)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		return err
	}
	meta := cacheMeta{
		ETag: entry.ETag, LastModified: entry.LastModified,
		Size: int64(len(data)), FetchedAt: time.Now(),
	}
	mb, _ := json.Marshal(meta)
	if err := os.WriteFile(path+".meta", mb, 0o644); err != nil {
		return err
	}
	if c.maxBytes > 0 {
		c.mu.Lock()
		c.sizeEstimate += int64(len(data))
		c.evictIfNeeded()
		c.mu.Unlock()
	}
	return nil
}

// fresh reports whether a cached entry still matches what the server advertises. With no
// server metadata at all (an entry we never listed) the cache is trusted: the alternative is
// re-downloading every trip on every page load.
func fresh(meta cacheMeta, entry webdav.Entry) bool {
	switch {
	case entry.ETag != "" && meta.ETag != "":
		return entry.ETag == meta.ETag
	case !entry.LastModified.IsZero() && !meta.LastModified.IsZero():
		return entry.LastModified.Equal(meta.LastModified) && (entry.Size == 0 || entry.Size == meta.Size)
	default:
		return true
	}
}

// Has reports whether a resource is already cached, without touching the network.
func (c *Cache) Has(rel string) bool {
	path, err := c.localPath(rel)
	if err != nil {
		return false
	}
	_, err = os.Stat(path)
	return err == nil
}

// Cached returns a resource from disk only, or false if it was never downloaded.
func (c *Cache) Cached(rel string) ([]byte, bool) {
	data, _, ok := c.read(rel)
	return data, ok
}

// Fetch returns a resource, downloading it only if the cache is empty or stale. entry is the
// PROPFIND record for the resource; pass the zero value when it is unknown.
func (c *Cache) Fetch(ctx context.Context, client webdav.Backend, rel string, entry webdav.Entry) ([]byte, error) {
	// Collapse concurrent fetches of the same path.
	c.mu.Lock()
	if wg, ok := c.inflight[rel]; ok {
		c.mu.Unlock()
		wg.Wait()
		if data, meta, ok := c.read(rel); ok && fresh(meta, entry) {
			return data, nil
		}
		// The winner failed or wrote something stale; fall through and fetch it ourselves.
		c.mu.Lock()
	}
	if data, meta, ok := c.read(rel); ok && fresh(meta, entry) {
		c.mu.Unlock()
		return data, nil
	}
	wg := &sync.WaitGroup{}
	wg.Add(1)
	c.inflight[rel] = wg
	c.mu.Unlock()

	defer func() {
		c.mu.Lock()
		delete(c.inflight, rel)
		c.mu.Unlock()
		wg.Done()
	}()

	data, err := client.Get(ctx, rel)
	if err != nil {
		// Serve a stale copy rather than an error when the server is unreachable: an offline
		// viewer showing last week's trips beats a viewer showing nothing.
		if stale, _, ok := c.read(rel); ok {
			return stale, nil
		}
		return nil, err
	}
	if err := c.write(rel, data, entry); err != nil {
		return data, nil // usable in memory even if it could not be cached
	}
	return data, nil
}

// Forget drops a cached resource, so the next Fetch re-downloads it.
func (c *Cache) Forget(rel string) {
	if path, err := c.localPath(rel); err == nil {
		_ = os.Remove(path)
		_ = os.Remove(path + ".meta")
	}
}

// Size totals the bytes held in the cache.
func (c *Cache) Size() (int64, int, error) {
	var total int64
	var files int
	err := filepath.WalkDir(filepath.Join(c.dir, "data"), func(_ string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		if strings.HasSuffix(d.Name(), ".meta") || strings.HasSuffix(d.Name(), ".tmp") {
			return nil
		}
		if info, err := d.Info(); err == nil {
			total += info.Size()
			files++
		}
		return nil
	})
	return total, files, err
}

// Clear empties the payload cache. The catalog and summaries are left alone - they are cheap
// to keep and expensive to rebuild.
func (c *Cache) Clear() error {
	if err := os.RemoveAll(filepath.Join(c.dir, "data")); err != nil {
		return err
	}
	return os.MkdirAll(filepath.Join(c.dir, "data"), 0o755)
}
