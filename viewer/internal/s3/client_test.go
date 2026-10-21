package s3

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sort"
	"strings"
	"testing"
	"time"

	"crsib.me/fct-viewer/internal/webdav"
)

// The "GET Bucket (List Objects)" example from the AWS SigV4 documentation
// (sig-v4-header-based-auth). Matching it means the signature is what S3 itself computes.
func TestAuthorizationMatchesAWSExample(t *testing.T) {
	got := authorization(signInput{
		method:         "GET",
		canonicalURI:   "/",
		canonicalQuery: canonicalQueryString(url.Values{"prefix": {"J"}, "max-keys": {"2"}}),
		host:           "examplebucket.s3.amazonaws.com",
		payloadHash:    emptySHA256,
		amzDate:        "20130524T000000Z",
		region:         "us-east-1",
		accessKey:      "AKIAIOSFODNN7EXAMPLE",
		secretKey:      "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
	})
	want := "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
		"SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
		"Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7"
	if got != want {
		t.Fatalf("authorization:\n got %s\nwant %s", got, want)
	}
}

// fakeS3 serves a tiny in-memory bucket with ListObjectsV2 + GetObject, and checks that every
// request it receives carries a signature it can verify itself.
type fakeS3 struct {
	t       *testing.T
	bucket  string
	objects map[string]string
	pageMax int
	lists   int
}

func (f *fakeS3) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	auth := r.Header.Get("Authorization")
	want := authorization(signInput{
		method: r.Method, canonicalURI: r.URL.EscapedPath(), canonicalQuery: canonicalQueryString(r.URL.Query()),
		host: r.Host, payloadHash: r.Header.Get("x-amz-content-sha256"), amzDate: r.Header.Get("x-amz-date"),
		region: "eu-central-1", accessKey: "AKID", secretKey: "secret",
	})
	if auth != want {
		w.WriteHeader(http.StatusForbidden)
		fmt.Fprint(w, `<Error><Code>SignatureDoesNotMatch</Code><Message>nope</Message></Error>`)
		return
	}
	path := strings.TrimPrefix(r.URL.Path, "/"+f.bucket)
	if path == "" || path == "/" {
		f.lists++
		f.list(w, r.URL.Query())
		return
	}
	body, ok := f.objects[strings.TrimPrefix(path, "/")]
	if !ok {
		w.WriteHeader(http.StatusNotFound)
		fmt.Fprint(w, `<Error><Code>NoSuchKey</Code></Error>`)
		return
	}
	fmt.Fprint(w, body)
}

func (f *fakeS3) list(w http.ResponseWriter, q url.Values) {
	prefix, delim := q.Get("prefix"), q.Get("delimiter")
	var keys []string
	for k := range f.objects {
		if strings.HasPrefix(k, prefix) {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	seen := map[string]bool{}
	var items []string // "P:prefix" or "K:key", in order
	for _, k := range keys {
		rest := strings.TrimPrefix(k, prefix)
		if delim != "" {
			if i := strings.Index(rest, delim); i >= 0 {
				p := prefix + rest[:i+1]
				if !seen[p] {
					seen[p] = true
					items = append(items, "P:"+p)
				}
				continue
			}
		}
		items = append(items, "K:"+k)
	}
	start := 0
	if tok := q.Get("continuation-token"); tok != "" {
		fmt.Sscanf(tok, "%d", &start)
	}
	end := len(items)
	if f.pageMax > 0 && start+f.pageMax < end {
		end = start + f.pageMax
	}
	var b strings.Builder
	b.WriteString(`<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">`)
	fmt.Fprintf(&b, "<IsTruncated>%v</IsTruncated>", end < len(items))
	if end < len(items) {
		fmt.Fprintf(&b, "<NextContinuationToken>%d</NextContinuationToken>", end)
	}
	for _, it := range items[start:end] {
		if strings.HasPrefix(it, "P:") {
			fmt.Fprintf(&b, "<CommonPrefixes><Prefix>%s</Prefix></CommonPrefixes>", it[2:])
		} else {
			fmt.Fprintf(&b, `<Contents><Key>%s</Key><LastModified>2026-06-01T10:00:00.000Z</LastModified><ETag>"abc"</ETag><Size>%d</Size></Contents>`,
				it[2:], len(f.objects[it[2:]]))
		}
	}
	b.WriteString("</ListBucketResult>")
	fmt.Fprint(w, b.String())
}

func newTestClient(t *testing.T, f *fakeS3) *Client {
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	return New(Config{
		Endpoint: srv.URL, Region: "eu-central-1", Bucket: f.bucket, Prefix: "/CarTracker/",
		AccessKey: "AKID", SecretKey: "secret",
	}, http.DefaultTransport)
}

func TestListAndGetOverTheSyncLayout(t *testing.T) {
	f := &fakeS3{t: t, bucket: "cars", objects: map[string]string{
		"CarTracker/":                                  "",
		"CarTracker/veh-1/vehicle.json":                `{"v":1}`,
		"CarTracker/veh-1/ledger.json":                 `{}`,
		"CarTracker/veh-1/trips/2026/06/t1/trip.json":  `{"t":1}`,
		"CarTracker/veh-1/trips/2026/06/t1/obd.csv.gz": "gz",
		"CarTracker/did-scans/scan.csv.gz":             "gz",
		"elsewhere/unrelated.txt":                      "x",
	}}
	c := newTestClient(t, f)
	ctx := context.Background()

	root, err := c.List(ctx, "")
	if err != nil {
		t.Fatal(err)
	}
	if got := names(root); got != "did-scans/ veh-1/" {
		t.Fatalf("root listing = %q", got)
	}

	car, err := c.List(ctx, "veh-1/")
	if err != nil {
		t.Fatal(err)
	}
	if got := names(car); got != "ledger.json trips/ vehicle.json" {
		t.Fatalf("car listing = %q", got)
	}
	for _, e := range car {
		if e.Name == "vehicle.json" && (e.ETag != "abc" || e.Size != 7 || e.LastModified.IsZero()) {
			t.Fatalf("file metadata not carried over: %+v", e)
		}
	}

	b, err := c.Get(ctx, "veh-1/trips/2026/06/t1/trip.json")
	if err != nil || string(b) != `{"t":1}` {
		t.Fatalf("Get = %q, %v", b, err)
	}
	if _, err := c.Get(ctx, "veh-1/missing.json"); !errors.Is(err, webdav.ErrNotFound) {
		t.Fatalf("missing key: want ErrNotFound, got %v", err)
	}
	if missing, err := c.List(ctx, "nope/"); err != nil || len(missing) != 0 {
		t.Fatalf("missing dir should list empty, got %v, %v", missing, err)
	}
}

func TestListFollowsContinuationTokens(t *testing.T) {
	objs := map[string]string{}
	for i := 0; i < 7; i++ {
		objs[fmt.Sprintf("CarTracker/veh-1/trips/2026/06/t%d/trip.json", i)] = "{}"
	}
	f := &fakeS3{t: t, bucket: "cars", objects: objs, pageMax: 3}
	c := newTestClient(t, f)
	got, err := c.List(context.Background(), "veh-1/trips/2026/06/")
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 7 || f.lists != 3 {
		t.Fatalf("got %d entries over %d pages, want 7 over 3", len(got), f.lists)
	}
}

func TestPingExplainsBadCredentials(t *testing.T) {
	f := &fakeS3{t: t, bucket: "cars", objects: map[string]string{}}
	c := newTestClient(t, f)
	if err := c.Ping(context.Background()); err != nil {
		t.Fatalf("good credentials: %v", err)
	}
	c.cfg.SecretKey = "wrong"
	err := c.Ping(context.Background())
	if err == nil || !strings.Contains(err.Error(), "secret access key") {
		t.Fatalf("want a secret-key hint, got %v", err)
	}
}

func TestSignsAtTheInjectedClock(t *testing.T) {
	c := New(Config{Endpoint: "https://s3.example.com", Bucket: "b", AccessKey: "k", SecretKey: "s"}, nil)
	c.now = func() time.Time { return time.Date(2013, 5, 24, 0, 0, 0, 0, time.UTC) }
	req, err := c.request(context.Background(), "a b/c.json", nil)
	if err != nil {
		t.Fatal(err)
	}
	if req.URL.EscapedPath() != "/b/a%20b/c.json" {
		t.Fatalf("path = %s", req.URL.EscapedPath())
	}
	if req.Header.Get("x-amz-date") != "20130524T000000Z" {
		t.Fatalf("date = %s", req.Header.Get("x-amz-date"))
	}
	if !strings.Contains(req.Header.Get("Authorization"), "/20130524/us-east-1/s3/aws4_request") {
		t.Fatalf("scope missing default region: %s", req.Header.Get("Authorization"))
	}
}

func names(es []webdav.Entry) string {
	var out []string
	for _, e := range es {
		n := e.Name
		if e.IsCollection {
			n += "/"
		}
		out = append(out, n)
	}
	sort.Strings(out)
	return strings.Join(out, " ")
}
