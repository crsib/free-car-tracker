package api

import (
	"fmt"
	"log"
	"net"
	"net/http"
	"strings"
)

// AllowList restricts who may talk to the server, by the peer address of the TCP connection.
//
// This is the fence for a deployment where the viewer binds a LAN address behind a reverse
// proxy: only the proxy (and the host itself) should be able to reach it directly, because
// the process holds WebDAV credentials and will happily serve the data to anyone who asks.
//
// It deliberately ignores X-Forwarded-For. That header is set by whoever is talking to us, so
// trusting it would let any LAN host forge an allowed origin and defeat the whole control.
type AllowList struct {
	nets []*net.IPNet
	ips  []net.IP
}

// ParseAllowList reads a comma-separated list of IPs and CIDRs. An empty spec means "allow
// everyone", which is correct for the default loopback-only bind and wrong for anything else.
func ParseAllowList(spec string) (*AllowList, error) {
	spec = strings.TrimSpace(spec)
	if spec == "" {
		return nil, nil
	}
	a := &AllowList{}
	for _, part := range strings.Split(spec, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		if strings.Contains(part, "/") {
			_, n, err := net.ParseCIDR(part)
			if err != nil {
				return nil, fmt.Errorf("allow-from: bad CIDR %q: %w", part, err)
			}
			a.nets = append(a.nets, n)
			continue
		}
		ip := net.ParseIP(part)
		if ip == nil {
			return nil, fmt.Errorf("allow-from: bad IP %q", part)
		}
		a.ips = append(a.ips, ip)
	}
	if len(a.nets) == 0 && len(a.ips) == 0 {
		return nil, nil
	}
	return a, nil
}

func (a *AllowList) Allows(ip net.IP) bool {
	if a == nil {
		return true
	}
	if ip == nil {
		return false
	}
	for _, want := range a.ips {
		if want.Equal(ip) {
			return true
		}
	}
	for _, n := range a.nets {
		if n.Contains(ip) {
			return true
		}
	}
	return false
}

func (a *AllowList) String() string {
	if a == nil {
		return "everyone"
	}
	parts := make([]string, 0, len(a.ips)+len(a.nets))
	for _, ip := range a.ips {
		parts = append(parts, ip.String())
	}
	for _, n := range a.nets {
		parts = append(parts, n.String())
	}
	return strings.Join(parts, ",")
}

// withAllowList rejects any connection whose peer address is not on the list.
func (s *Server) withAllowList(next http.Handler) http.Handler {
	if s.allow == nil {
		return next
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			host = r.RemoteAddr
		}
		ip := net.ParseIP(host)
		// An IPv4 peer arriving over a dual-stack listener shows up as ::ffff:a.b.c.d;
		// normalise so a plain "192.0.2.22" entry still matches.
		if v4 := ip.To4(); v4 != nil {
			ip = v4
		}
		if !s.allow.Allows(ip) {
			log.Printf("denied %s %s from %s (not in -allow-from)", r.Method, r.URL.Path, host)
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	})
}
