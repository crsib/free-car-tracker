// Package s3 is a small read-only S3 client: ListObjectsV2 for listings and GetObject for
// content, signed with AWS Signature V4. It is the S3 counterpart of package webdav and
// mirrors the Android app's S3Client (data/webdav/S3Client.kt), so it reads the same
// {prefix}/{vehicle}/trips/... layout from AWS or any S3-compatible store (MinIO, R2, B2,
// Yandex Object Storage). Like the rest of the viewer it never writes.
package s3

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"

	"crsib.me/fct-viewer/internal/webdav"
)

// DefaultRegion is what an empty region means. MinIO accepts it; R2 wants "auto".
const DefaultRegion = "us-east-1"

// Config is the bucket to read and the key pair to read it with.
type Config struct {
	// Endpoint is the service URL, e.g. https://s3.eu-central-1.amazonaws.com. Empty means
	// AWS for Region.
	Endpoint string
	Region   string
	Bucket   string
	// Prefix is the folder inside the bucket that holds the sync layout; empty is the root.
	Prefix    string
	AccessKey string
	SecretKey string
	// VirtualHost addresses https://bucket.host/key instead of https://host/bucket/key. Path
	// style works on every S3-compatible store and needs no wildcard DNS, so it is the default.
	VirtualHost bool

	// Pacing, as in webdav.Config.
	RequestsPerSecond float64
	MaxRetries        int
	UserAgent         string
}

// EffectiveRegion is Region, or DefaultRegion when unset.
func (c Config) EffectiveRegion() string {
	if r := strings.TrimSpace(c.Region); r != "" {
		return r
	}
	return DefaultRegion
}

// EffectiveEndpoint is Endpoint, or the AWS endpoint for the region when unset.
func (c Config) EffectiveEndpoint() string {
	if e := strings.TrimRight(strings.TrimSpace(c.Endpoint), "/"); e != "" {
		return e
	}
	return "https://s3." + c.EffectiveRegion() + ".amazonaws.com"
}

type Client struct {
	http    *http.Client
	cfg     Config
	limiter *webdav.Limiter
	// now is the signing clock; replaced in tests to check against fixed vectors.
	now func() time.Time
}

var _ webdav.Backend = (*Client)(nil)

// New builds a client. The timeout matches the WebDAV one: generous, since trip payloads run
// to tens of megabytes, but finite so a wedged server still fails the walk eventually.
func New(cfg Config, transport http.RoundTripper) *Client {
	return &Client{
		cfg:     cfg,
		http:    &http.Client{Transport: transport, Timeout: 10 * time.Minute},
		limiter: webdav.NewLimiter(cfg.RequestsPerSecond),
		now:     time.Now,
	}
}

func (c *Client) Limiter() *webdav.Limiter { return c.limiter }

// root is the key prefix of the sync folder: "" or "some/folder/".
func (c *Client) root() string {
	p := strings.Trim(strings.TrimSpace(c.cfg.Prefix), "/")
	if p == "" {
		return ""
	}
	return p + "/"
}

func (c *Client) key(rel string) string { return c.root() + strings.TrimLeft(rel, "/") }

func (c *Client) dirKey(rel string) string {
	k := c.key(rel)
	if k != "" && !strings.HasSuffix(k, "/") {
		k += "/"
	}
	return k
}

const defaultUserAgent = "fct-viewer/1.0 (+https://github.com/crsib/free-car-tracker)"

// request builds a signed GET for key ("" is the bucket itself) with the given query.
func (c *Client) request(ctx context.Context, key string, query url.Values) (*http.Request, error) {
	ep, err := url.Parse(c.cfg.EffectiveEndpoint())
	if err != nil || ep.Host == "" {
		return nil, fmt.Errorf("bad s3 endpoint %q", c.cfg.Endpoint)
	}
	bucket := strings.TrimSpace(c.cfg.Bucket)
	host := ep.Host
	var segs []string
	if c.cfg.VirtualHost {
		host = bucket + "." + ep.Host
	} else {
		segs = append(segs, bucket)
	}
	for _, s := range strings.Split(key, "/") {
		if s != "" {
			segs = append(segs, s)
		}
	}
	encoded := make([]string, len(segs))
	for i, s := range segs {
		encoded[i] = uriEncode(s)
	}
	canonicalURI := "/" + strings.Join(encoded, "/")
	canonicalQuery := canonicalQueryString(query)

	u := &url.URL{Scheme: ep.Scheme, Host: host, Path: unescapeLoose(canonicalURI), RawPath: canonicalURI, RawQuery: canonicalQuery}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return nil, err
	}
	// Go's url.String may re-encode differently from SigV4; pin the exact signed form.
	req.URL.RawPath = canonicalURI
	req.URL.RawQuery = canonicalQuery

	amzDate := c.now().UTC().Format("20060102T150405Z")
	payloadHash := emptySHA256
	req.Header.Set("x-amz-date", amzDate)
	req.Header.Set("x-amz-content-sha256", payloadHash)
	req.Header.Set("Authorization", authorization(signInput{
		method: http.MethodGet, canonicalURI: canonicalURI, canonicalQuery: canonicalQuery,
		host: host, payloadHash: payloadHash, amzDate: amzDate, region: c.cfg.EffectiveRegion(),
		accessKey: strings.TrimSpace(c.cfg.AccessKey), secretKey: strings.TrimSpace(c.cfg.SecretKey),
	}))
	ua := c.cfg.UserAgent
	if ua == "" {
		ua = defaultUserAgent
	}
	req.Header.Set("User-Agent", ua)
	return req, nil
}

func (c *Client) do(ctx context.Context, key string, query url.Values) (*http.Response, error) {
	return webdav.Send(ctx, c.http, c.limiter, c.cfg.MaxRetries, func() (*http.Request, error) {
		// Re-signed per attempt: a retry after a long Retry-After must carry a fresh date.
		return c.request(ctx, key, query)
	})
}

type listResult struct {
	IsTruncated           bool   `xml:"IsTruncated"`
	NextContinuationToken string `xml:"NextContinuationToken"`
	Contents              []struct {
		Key          string `xml:"Key"`
		LastModified string `xml:"LastModified"`
		ETag         string `xml:"ETag"`
		Size         int64  `xml:"Size"`
	} `xml:"Contents"`
	CommonPrefixes []struct {
		Prefix string `xml:"Prefix"`
	} `xml:"CommonPrefixes"`
}

// List returns a "directory": the common prefixes one level below rel as collections, and
// the objects directly in it as files. S3 has no real directories, so a missing one simply
// lists as empty - the same contract as the WebDAV client.
func (c *Client) List(ctx context.Context, rel string) ([]webdav.Entry, error) {
	prefix := c.dirKey(rel)
	var out []webdav.Entry
	token := ""
	for {
		q := url.Values{"list-type": {"2"}, "prefix": {prefix}, "delimiter": {"/"}}
		if token != "" {
			q.Set("continuation-token", token)
		}
		page, err := c.list(ctx, q)
		if err != nil {
			return nil, fmt.Errorf("LIST %s: %w", rel, err)
		}
		for _, p := range page.CommonPrefixes {
			name := strings.TrimSuffix(strings.TrimPrefix(p.Prefix, prefix), "/")
			if name != "" {
				out = append(out, webdav.Entry{Name: name, IsCollection: true})
			}
		}
		for _, o := range page.Contents {
			if o.Key == prefix {
				continue // a console-made "folder" placeholder, not a child
			}
			e := webdav.Entry{
				Name: strings.TrimPrefix(o.Key, prefix),
				Size: o.Size,
				ETag: strings.Trim(o.ETag, `"`),
			}
			if t, err := time.Parse(time.RFC3339, strings.TrimSpace(o.LastModified)); err == nil {
				e.LastModified = t
			}
			out = append(out, e)
		}
		if !page.IsTruncated || page.NextContinuationToken == "" {
			return out, nil
		}
		token = page.NextContinuationToken
	}
}

func (c *Client) list(ctx context.Context, q url.Values) (*listResult, error) {
	resp, err := c.do(ctx, "", q)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return nil, statusError(resp)
	}
	var res listResult
	if err := xml.NewDecoder(resp.Body).Decode(&res); err != nil {
		return nil, fmt.Errorf("parse listing: %w", err)
	}
	return &res, nil
}

// Get fetches an object. A missing key yields webdav.ErrNotFound, as the WebDAV client does,
// so callers need not know which backend they are reading.
func (c *Client) Get(ctx context.Context, rel string) ([]byte, error) {
	resp, err := c.do(ctx, c.key(rel), nil)
	if err != nil {
		return nil, fmt.Errorf("GET %s: %w", rel, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return nil, fmt.Errorf("GET %s: %w", rel, webdav.ErrNotFound)
	}
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("GET %s: %w", rel, statusError(resp))
	}
	return io.ReadAll(resp.Body)
}

// Ping lists one key under the prefix, which exercises the endpoint, bucket, region and both
// keys in a single request, and translates S3's error codes into what to fix on the form.
func (c *Client) Ping(ctx context.Context) error {
	if strings.TrimSpace(c.cfg.Bucket) == "" {
		return errors.New("bucket is required")
	}
	_, err := c.list(ctx, url.Values{"list-type": {"2"}, "prefix": {c.root()}, "max-keys": {"1"}})
	if err == nil {
		return nil
	}
	var se *Error
	if errors.As(err, &se) {
		switch se.Code {
		case "InvalidAccessKeyId":
			return errors.New("access key ID not recognised")
		case "SignatureDoesNotMatch":
			return errors.New("secret access key is wrong")
		case "NoSuchBucket":
			return fmt.Errorf("bucket %q does not exist at this endpoint", c.cfg.Bucket)
		case "AuthorizationHeaderMalformed", "IllegalLocationConstraintException", "PermanentRedirect":
			return fmt.Errorf("wrong region for this bucket (%s)", se.Message)
		case "RequestTimeTooSkewed":
			return errors.New("this machine's clock is too far off for S3 to accept the request")
		case "AccessDenied":
			return errors.New("access denied: the key needs list and read permission on this bucket")
		}
		return se
	}
	return fmt.Errorf("could not reach %s: %w", c.cfg.EffectiveEndpoint(), err)
}

// Error is an S3 error response.
type Error struct {
	Status  string
	Code    string `xml:"Code"`
	Message string `xml:"Message"`
}

func (e *Error) Error() string {
	if e.Code == "" {
		return "server returned " + e.Status
	}
	if e.Message == "" {
		return fmt.Sprintf("%s (%s)", e.Code, e.Status)
	}
	return fmt.Sprintf("%s: %s (%s)", e.Code, e.Message, e.Status)
}

func statusError(resp *http.Response) error {
	e := &Error{Status: resp.Status}
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 16<<10))
	_ = xml.Unmarshal(b, e)
	return e
}

// --- Signature V4 ---

var emptySHA256 = sha256Hex(nil)

type signInput struct {
	method, canonicalURI, canonicalQuery, host, payloadHash, amzDate, region, accessKey, secretKey string
}

const signedHeaders = "host;x-amz-content-sha256;x-amz-date"

func authorization(in signInput) string {
	dateStamp := in.amzDate[:8]
	canonicalHeaders := "host:" + in.host + "\n" +
		"x-amz-content-sha256:" + in.payloadHash + "\n" +
		"x-amz-date:" + in.amzDate + "\n"
	canonicalRequest := strings.Join([]string{
		in.method, in.canonicalURI, in.canonicalQuery, canonicalHeaders, signedHeaders, in.payloadHash,
	}, "\n")
	scope := dateStamp + "/" + in.region + "/s3/aws4_request"
	stringToSign := "AWS4-HMAC-SHA256\n" + in.amzDate + "\n" + scope + "\n" + sha256Hex([]byte(canonicalRequest))

	k := hmacSHA256([]byte("AWS4"+in.secretKey), dateStamp)
	k = hmacSHA256(k, in.region)
	k = hmacSHA256(k, "s3")
	k = hmacSHA256(k, "aws4_request")
	sig := hex.EncodeToString(hmacSHA256(k, stringToSign))
	return "AWS4-HMAC-SHA256 Credential=" + in.accessKey + "/" + scope +
		", SignedHeaders=" + signedHeaders + ", Signature=" + sig
}

// canonicalQueryString encodes every name and value and sorts by name, then value.
func canonicalQueryString(q url.Values) string {
	type kv struct{ k, v string }
	var pairs []kv
	for k, vs := range q {
		for _, v := range vs {
			pairs = append(pairs, kv{uriEncode(k), uriEncode(v)})
		}
	}
	sort.Slice(pairs, func(i, j int) bool {
		if pairs[i].k != pairs[j].k {
			return pairs[i].k < pairs[j].k
		}
		return pairs[i].v < pairs[j].v
	})
	parts := make([]string, len(pairs))
	for i, p := range pairs {
		parts[i] = p.k + "=" + p.v
	}
	return strings.Join(parts, "&")
}

// uriEncode percent-encodes everything except A-Za-z0-9-_.~, as SigV4 requires.
func uriEncode(s string) string {
	const hexDigits = "0123456789ABCDEF"
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		c := s[i]
		if ('A' <= c && c <= 'Z') || ('a' <= c && c <= 'z') || ('0' <= c && c <= '9') ||
			c == '-' || c == '_' || c == '.' || c == '~' {
			b.WriteByte(c)
			continue
		}
		b.WriteByte('%')
		b.WriteByte(hexDigits[c>>4])
		b.WriteByte(hexDigits[c&15])
	}
	return b.String()
}

func unescapeLoose(p string) string {
	if u, err := url.PathUnescape(p); err == nil {
		return u
	}
	return p
}

func sha256Hex(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

func hmacSHA256(key []byte, data string) []byte {
	m := hmac.New(sha256.New, key)
	m.Write([]byte(data))
	return m.Sum(nil)
}
