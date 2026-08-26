// Package webdav is a small read-only WebDAV client: PROPFIND for listings and GET for
// content, with HTTP Basic auth. It mirrors the request shape the Android app uses
// (data/webdav/WebDavClient.kt) so it works against the same Nextcloud/ownCloud/Apache
// deployments, but never writes — this whole program is a viewer.
package webdav

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// Config is the connection target: a directory URL plus Basic credentials.
type Config struct {
	URL      string
	Username string
	Password string
	// Insecure skips TLS verification, for a server with a self-signed certificate.
	Insecure bool

	// RequestsPerSecond caps the outbound rate. Zero disables pacing. Hosted WebDAV
	// (Yandex.Disk especially) throttles hard, and a throttled request is a dropped trip,
	// so the default set by the caller should be conservative rather than optimistic.
	RequestsPerSecond float64
	// MaxRetries is how many times a 429/503 is retried before giving up. Zero means the
	// request is attempted exactly once.
	MaxRetries int
	// UserAgent identifies this client to the server. Shared services expect to be able to
	// tell who is talking to them.
	UserAgent string
}

// Entry is one child of a collection, from a Depth:1 PROPFIND.
type Entry struct {
	Name         string
	IsCollection bool
	LastModified time.Time
	Size         int64
	ETag         string
}

// ErrNotFound is returned by Get for a 404, so callers can treat "absent" as data
// rather than as a transport failure (a trip folder may legitimately lack a side file).
var ErrNotFound = errors.New("webdav: not found")

// ErrThrottled means the server kept answering 429/503 until the retry budget ran out. It is
// deliberately distinct from a generic failure: it means "slow down", not "this is missing".
var ErrThrottled = errors.New("webdav: rate limited by the server")

type Client struct {
	http    *http.Client
	cfg     Config
	limiter *Limiter
}

// New builds a client. The timeout is per-request-attempt, not per byte: trip payloads
// run to tens of megabytes, so it is set generously rather than at Go's zero (infinite)
// so a wedged server still eventually fails instead of hanging the index walk.
func New(cfg Config, transport http.RoundTripper) *Client {
	return &Client{
		cfg:     cfg,
		http:    &http.Client{Transport: transport, Timeout: 10 * time.Minute},
		limiter: NewLimiter(cfg.RequestsPerSecond),
	}
}

// Limiter exposes the pacer so callers can report throttling to the user.
func (c *Client) Limiter() *Limiter { return c.limiter }

func (c *Client) Config() Config { return c.cfg }

// abs resolves a root-relative path against the configured URL, percent-encoding each
// segment. A trailing slash is preserved: some servers distinguish collection URLs.
func (c *Client) abs(rel string) (string, error) {
	base := c.cfg.URL
	if !strings.HasSuffix(base, "/") {
		base += "/"
	}
	u, err := url.Parse(base)
	if err != nil {
		return "", fmt.Errorf("bad webdav url %q: %w", c.cfg.URL, err)
	}
	trailing := strings.HasSuffix(rel, "/")
	var segs []string
	for _, s := range strings.Split(strings.Trim(rel, "/"), "/") {
		if s != "" {
			segs = append(segs, s)
		}
	}
	if len(segs) > 0 {
		u.Path = strings.TrimSuffix(u.Path, "/") + "/" + strings.Join(segs, "/")
		if trailing {
			u.Path += "/"
		}
	}
	return u.String(), nil
}

func (c *Client) request(ctx context.Context, method, rel string, body io.Reader) (*http.Request, error) {
	abs, err := c.abs(rel)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, method, abs, body)
	if err != nil {
		return nil, err
	}
	req.SetBasicAuth(c.cfg.Username, c.cfg.Password)
	ua := c.cfg.UserAgent
	if ua == "" {
		ua = defaultUserAgent
	}
	req.Header.Set("User-Agent", ua)
	return req, nil
}

const defaultUserAgent = "fct-viewer/1.0 (+https://github.com/crsib/free-car-tracker)"

// do sends a request through the rate limiter, retrying when the server pushes back with 429
// or 503. Both mean "you are going too fast", and treating them as hard failures is what turned
// a burst of throttles into 131 silently missing trips.
//
// build must produce a fresh request each attempt: a retried request needs an unconsumed body.
func (c *Client) do(ctx context.Context, build func() (*http.Request, error)) (*http.Response, error) {
	attempts := c.cfg.MaxRetries + 1
	if attempts < 1 {
		attempts = 1
	}
	var lastErr error
	for attempt := 0; attempt < attempts; attempt++ {
		if err := c.limiter.Wait(ctx); err != nil {
			return nil, err
		}
		req, err := build()
		if err != nil {
			return nil, err
		}
		resp, err := c.http.Do(req)
		if err != nil {
			// Transport errors are not throttling; retrying them here would mask a genuinely
			// unreachable server behind a long delay.
			return nil, err
		}
		if resp.StatusCode != http.StatusTooManyRequests && resp.StatusCode != http.StatusServiceUnavailable {
			c.limiter.Succeeded()
			return resp, nil
		}

		wait, stated := retryAfter(resp)
		c.limiter.Throttled(wait)
		lastErr = fmt.Errorf("%s %s -> %s", req.Method, req.URL.Path, resp.Status)
		// Drain and close so the connection can be reused for the retry.
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
		resp.Body.Close()

		if attempt == attempts-1 {
			break
		}
		// Only invent a delay when the server did not state one; "Retry-After: 0" is an
		// instruction to come straight back, not an absence of instruction.
		if !stated {
			wait = backoff(attempt, 250*time.Millisecond)
		}
		if wait <= 0 {
			continue
		}
		timer := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, ctx.Err()
		case <-timer.C:
		}
	}
	return nil, fmt.Errorf("%w (after %d attempts): %v", ErrThrottled, attempts, lastErr)
}

const propfindBody = `<?xml version="1.0" encoding="utf-8"?>` +
	`<d:propfind xmlns:d="DAV:"><d:prop>` +
	`<d:resourcetype/><d:getlastmodified/><d:getcontentlength/><d:getetag/>` +
	`</d:prop></d:propfind>`

type msResponse struct {
	Href     string `xml:"DAV: href"`
	Propstat []struct {
		Status string `xml:"DAV: status"`
		Prop   struct {
			ResourceType struct {
				Collection *struct{} `xml:"DAV: collection"`
			} `xml:"DAV: resourcetype"`
			LastModified  string `xml:"DAV: getlastmodified"`
			ContentLength string `xml:"DAV: getcontentlength"`
			ETag          string `xml:"DAV: getetag"`
		} `xml:"DAV: prop"`
	} `xml:"DAV: propstat"`
}

type multistatus struct {
	Responses []msResponse `xml:"DAV: response"`
}

// List returns the immediate children of a collection (Depth:1), excluding the collection
// itself. A missing collection lists as empty rather than as an error: during an index walk
// a month bucket that no longer exists is an ordinary outcome.
func (c *Client) List(ctx context.Context, rel string) ([]Entry, error) {
	if rel != "" && !strings.HasSuffix(rel, "/") {
		rel += "/"
	}
	build := func() (*http.Request, error) {
		req, err := c.request(ctx, "PROPFIND", rel, strings.NewReader(propfindBody))
		if err != nil {
			return nil, err
		}
		req.Header.Set("Depth", "1")
		req.Header.Set("Content-Type", "application/xml; charset=utf-8")
		return req, nil
	}
	resp, err := c.do(ctx, build)
	if err != nil {
		return nil, fmt.Errorf("PROPFIND %s: %w", rel, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return nil, nil
	}
	if resp.StatusCode != http.StatusMultiStatus && resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("PROPFIND %s -> %s", rel, resp.Status)
	}

	var ms multistatus
	if err := xml.NewDecoder(resp.Body).Decode(&ms); err != nil {
		return nil, fmt.Errorf("PROPFIND %s: parse multistatus: %w", rel, err)
	}

	self := strings.TrimSuffix(resp.Request.URL.EscapedPath(), "/")
	out := make([]Entry, 0, len(ms.Responses))
	for _, r := range ms.Responses {
		hrefPath := r.Href
		if u, err := url.Parse(strings.TrimSpace(r.Href)); err == nil {
			hrefPath = u.EscapedPath()
		}
		hrefPath = strings.TrimSuffix(hrefPath, "/")
		if hrefPath == self {
			continue // the collection itself
		}
		name := hrefPath
		if i := strings.LastIndex(name, "/"); i >= 0 {
			name = name[i+1:]
		}
		decoded, err := url.PathUnescape(name)
		if err != nil {
			decoded = name
		}
		if decoded == "" {
			continue
		}
		e := Entry{Name: decoded}
		for _, ps := range r.Propstat {
			// Nextcloud splits props across a 200 and a 404 propstat; only read the found half.
			if ps.Status != "" && !strings.Contains(ps.Status, " 200 ") {
				continue
			}
			if ps.Prop.ResourceType.Collection != nil {
				e.IsCollection = true
			}
			if ps.Prop.LastModified != "" {
				if t, err := http.ParseTime(strings.TrimSpace(ps.Prop.LastModified)); err == nil {
					e.LastModified = t
				}
			}
			if ps.Prop.ContentLength != "" {
				if n, err := strconv.ParseInt(strings.TrimSpace(ps.Prop.ContentLength), 10, 64); err == nil {
					e.Size = n
				}
			}
			if ps.Prop.ETag != "" {
				e.ETag = strings.Trim(strings.TrimSpace(ps.Prop.ETag), `"`)
			}
		}
		out = append(out, e)
	}
	return out, nil
}

// Get fetches a resource's bytes. A 404 yields ErrNotFound.
func (c *Client) Get(ctx context.Context, rel string) ([]byte, error) {
	resp, err := c.do(ctx, func() (*http.Request, error) {
		return c.request(ctx, http.MethodGet, rel, nil)
	})
	if err != nil {
		return nil, fmt.Errorf("GET %s: %w", rel, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return nil, fmt.Errorf("GET %s: %w", rel, ErrNotFound)
	}
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("GET %s -> %s", rel, resp.Status)
	}
	return io.ReadAll(resp.Body)
}

// Ping verifies the URL and credentials with a Depth:0 PROPFIND on the root.
func (c *Client) Ping(ctx context.Context) error {
	resp, err := c.do(ctx, func() (*http.Request, error) {
		req, err := c.request(ctx, "PROPFIND", "", strings.NewReader(propfindBody))
		if err != nil {
			return nil, err
		}
		req.Header.Set("Depth", "0")
		req.Header.Set("Content-Type", "application/xml; charset=utf-8")
		return req, nil
	})
	if err != nil {
		// A transport failure here is almost always a typo in the URL or a server that is not
		// running. Go's raw dial error names the syscall, which is noise to someone filling in
		// a settings form, so lead with the actionable part and keep the detail after it.
		return fmt.Errorf("could not reach %s: %w", c.cfg.URL, err)
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	switch {
	case resp.StatusCode == http.StatusMultiStatus || resp.StatusCode/100 == 2:
		return nil
	case resp.StatusCode == http.StatusUnauthorized:
		return errors.New("authentication failed (check username / app password)")
	case resp.StatusCode == http.StatusNotFound:
		return errors.New("path not found on the server (check the URL)")
	default:
		return fmt.Errorf("server returned %s", resp.Status)
	}
}

// Gunzip decompresses a gzip payload. The sync writer gzips obd.csv and track.gpx before
// upload, so every side file arrives compressed.
func Gunzip(b []byte) ([]byte, error) {
	zr, err := gzip.NewReader(bytes.NewReader(b))
	if err != nil {
		return nil, err
	}
	defer zr.Close()
	return io.ReadAll(zr)
}
