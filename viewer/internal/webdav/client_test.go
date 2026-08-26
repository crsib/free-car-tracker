package webdav

import (
	"bytes"
	"compress/gzip"
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// Nextcloud answers with the lp1:/d: prefix mix below and splits properties across a 200 and a
// 404 propstat. Parsing has to key off the DAV: namespace and read only the found half, or
// every entry comes back with a zero timestamp.
const nextcloudish = `<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns">
 <d:response>
  <d:href>/remote.php/dav/files/me/CarTracker/</d:href>
  <d:propstat>
   <d:prop><d:resourcetype><d:collection/></d:resourcetype>
    <d:getlastmodified>Mon, 03 Aug 2026 09:15:00 GMT</d:getlastmodified></d:prop>
   <d:status>HTTP/1.1 200 OK</d:status>
  </d:propstat>
  <d:propstat>
   <d:prop><d:getcontentlength/><d:getetag/></d:prop>
   <d:status>HTTP/1.1 404 Not Found</d:status>
  </d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/me/CarTracker/8f14e45f-ea8d/</d:href>
  <d:propstat>
   <d:prop><d:resourcetype><d:collection/></d:resourcetype>
    <d:getlastmodified>Tue, 04 Aug 2026 11:00:00 GMT</d:getlastmodified></d:prop>
   <d:status>HTTP/1.1 200 OK</d:status>
  </d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/me/CarTracker/ledger.json</d:href>
  <d:propstat>
   <d:prop><d:resourcetype/>
    <d:getlastmodified>Wed, 05 Aug 2026 08:30:00 GMT</d:getlastmodified>
    <d:getcontentlength>4211</d:getcontentlength>
    <d:getetag>&quot;abc123&quot;</d:getetag></d:prop>
   <d:status>HTTP/1.1 200 OK</d:status>
  </d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/me/CarTracker/My%20Car%20Notes.txt</d:href>
  <d:propstat>
   <d:prop><d:resourcetype/><d:getcontentlength>12</d:getcontentlength></d:prop>
   <d:status>HTTP/1.1 200 OK</d:status>
  </d:propstat>
 </d:response>
</d:multistatus>`

func newTestClient(t *testing.T, handler http.HandlerFunc) (*Client, *httptest.Server) {
	t.Helper()
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	c := New(Config{
		URL:      srv.URL + "/remote.php/dav/files/me/CarTracker/",
		Username: "me",
		Password: "pw",
	}, nil)
	return c, srv
}

func TestListParsesNextcloudMultistatus(t *testing.T) {
	var gotDepth, gotMethod, gotAuthUser string
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotDepth = r.Header.Get("Depth")
		gotAuthUser, _, _ = r.BasicAuth()
		w.Header().Set("Content-Type", "application/xml")
		w.WriteHeader(http.StatusMultiStatus)
		_, _ = w.Write([]byte(nextcloudish))
	})

	entries, err := c.List(context.Background(), "")
	if err != nil {
		t.Fatal(err)
	}
	if gotMethod != "PROPFIND" || gotDepth != "1" {
		t.Errorf("sent %s Depth:%q, want PROPFIND Depth:1", gotMethod, gotDepth)
	}
	if gotAuthUser != "me" {
		t.Errorf("basic auth user = %q, want me", gotAuthUser)
	}

	// The collection's own entry must be dropped, leaving three children.
	if len(entries) != 3 {
		t.Fatalf("got %d entries, want 3: %+v", len(entries), entries)
	}

	byName := map[string]Entry{}
	for _, e := range entries {
		byName[e.Name] = e
	}

	car, ok := byName["8f14e45f-ea8d"]
	if !ok || !car.IsCollection {
		t.Errorf("car folder missing or not a collection: %+v", car)
	}
	if car.LastModified.IsZero() {
		t.Error("last-modified was not read from the 200 propstat")
	}

	ledger, ok := byName["ledger.json"]
	if !ok {
		t.Fatal("ledger.json missing")
	}
	if ledger.IsCollection {
		t.Error("ledger.json must not be a collection")
	}
	if ledger.Size != 4211 {
		t.Errorf("size = %d, want 4211", ledger.Size)
	}
	if ledger.ETag != "abc123" {
		t.Errorf("etag = %q, want abc123 with the quotes stripped", ledger.ETag)
	}

	// Percent-encoded names must come back decoded, so paths round-trip.
	if _, ok := byName["My Car Notes.txt"]; !ok {
		t.Errorf("percent-encoded name was not decoded: %+v", entries)
	}
}

// A month bucket that no longer exists is an ordinary outcome mid-walk, not a failure.
func TestListTreatsMissingCollectionAsEmpty(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		http.NotFound(w, r)
	})
	entries, err := c.List(context.Background(), "trips/2020/01/")
	if err != nil {
		t.Fatalf("a 404 listing should not be an error: %v", err)
	}
	if len(entries) != 0 {
		t.Errorf("got %d entries, want none", len(entries))
	}
}

func TestGetEncodesPathSegments(t *testing.T) {
	var gotPath string
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.EscapedPath()
		_, _ = w.Write([]byte("payload"))
	})
	body, err := c.Get(context.Background(), "8f14e45f/trips/2026/08/a b/trip.json")
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != "payload" {
		t.Errorf("body = %q", body)
	}
	if !strings.HasSuffix(gotPath, "/8f14e45f/trips/2026/08/a%20b/trip.json") {
		t.Errorf("path = %q, want the space percent-encoded", gotPath)
	}
}

func TestGetReportsNotFoundDistinctly(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		http.NotFound(w, r)
	})
	_, err := c.Get(context.Background(), "missing.json")
	if !errors.Is(err, ErrNotFound) {
		t.Errorf("err = %v, want it to wrap ErrNotFound so callers can treat absence as data", err)
	}
}

func TestPingExplainsCommonFailures(t *testing.T) {
	cases := []struct {
		code int
		want string
	}{
		{http.StatusUnauthorized, "authentication failed"},
		{http.StatusNotFound, "path not found"},
		{http.StatusInternalServerError, "500"},
	}
	for _, tc := range cases {
		c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(tc.code)
		})
		err := c.Ping(context.Background())
		if err == nil {
			t.Fatalf("%d should be an error", tc.code)
		}
		if !strings.Contains(err.Error(), tc.want) {
			t.Errorf("%d gave %q, want it to mention %q", tc.code, err, tc.want)
		}
	}

	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusMultiStatus)
	})
	if err := c.Ping(context.Background()); err != nil {
		t.Errorf("207 should be success, got %v", err)
	}
}

// The client must never issue a write, whatever it is asked to do. This is the guarantee that
// makes it safe to point at a live sync folder.
func TestClientOnlyIssuesReadMethods(t *testing.T) {
	var methods []string
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		methods = append(methods, r.Method)
		w.WriteHeader(http.StatusMultiStatus)
		_, _ = w.Write([]byte(nextcloudish))
	})
	ctx := context.Background()
	_, _ = c.List(ctx, "")
	_, _ = c.Get(ctx, "vehicle.json")
	_ = c.Ping(ctx)

	for _, m := range methods {
		switch m {
		case "PROPFIND", http.MethodGet, http.MethodHead:
		default:
			t.Errorf("issued %s - the viewer must be read-only", m)
		}
	}
}

func TestGunzipRoundTrip(t *testing.T) {
	// Compressed with the same gzip settings the app's WebDavClient uses.
	original := []byte("timestampMillis,speedKmh\n1000,42\n")
	var buf bytes.Buffer
	zw := gzip.NewWriter(&buf)
	_, _ = zw.Write(original)
	_ = zw.Close()

	out, err := Gunzip(buf.Bytes())
	if err != nil {
		t.Fatal(err)
	}
	if string(out) != string(original) {
		t.Errorf("round trip gave %q, want %q", out, original)
	}
}
