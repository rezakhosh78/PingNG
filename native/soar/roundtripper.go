package soar

import (
	"context"
	"net"
	"net/http"
	"sync"
	"time"
)

// NewRoundTripper connects to addr through the tunnel (bounded by ctx) and returns an HTTP
// transport whose first dial to addr uses that connection; later dials ride the tunnel too. It
// returns only once the server confirms it reached addr: kindling treats this as the connect
// phase and falls back to another transport only when connecting fails. TLS runs end to end, so
// the server only relays ciphertext. With Close, MaxLength and RequestTimeout, this satisfies
// kindling's DNS-tunnel transport (dnstt.DNSTT).
//
// The confirmed connection holds one of the session's streams until a request takes it. Close
// the returned transport (it implements io.Closer) when it won't be used, as a race does when
// another transport wins; otherwise the connection is closed after preDialedTTL.
func (c *Client) NewRoundTripper(ctx context.Context, addr string) (http.RoundTripper, error) {
	var pre *preDialed
	if addr != "" {
		conn, err := c.DialContext(ctx, "tcp", addr)
		if err != nil {
			return nil, err
		}
		if err := conn.(*Conn).waitOpened(ctx); err != nil {
			conn.Close()
			return nil, err
		}
		pre = &preDialed{conn: conn, addr: addr}
		pre.mu.Lock()
		pre.timer = time.AfterFunc(preDialedTTL, pre.close)
		pre.mu.Unlock()
	} else if _, err := c.session(ctx); err != nil {
		return nil, err
	}
	return &roundTripper{
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, network, a string) (net.Conn, error) {
				if conn := pre.take(a); conn != nil {
					return conn, nil
				}
				return c.DialContext(ctx, network, a)
			},
			MaxIdleConns:        4,
			MaxIdleConnsPerHost: 2,
			// Idle keep-alives hold a stream open, and an open stream keeps a poll outstanding.
			IdleConnTimeout:     5 * time.Second,
			TLSHandshakeTimeout: 30 * time.Second,
		},
		pre: pre,
	}, nil
}

// preDialedTTL bounds how long a confirmed connection waits for its first request. A session
// has only MaxStreams streams, so one never taken must not hold its stream for good.
var preDialedTTL = 10 * time.Second

// roundTripper is the transport NewRoundTripper returns.
type roundTripper struct {
	*http.Transport
	pre *preDialed
}

// Close releases the confirmed connection if no request took it, and closes idle connections.
// Requests already in flight are unaffected.
func (rt *roundTripper) Close() error {
	rt.pre.close()
	rt.CloseIdleConnections()
	return nil
}

// preDialed hands a confirmed connection to the first dial for its address.
type preDialed struct {
	mu    sync.Mutex
	conn  net.Conn
	addr  string
	timer *time.Timer
}

func (p *preDialed) take(addr string) net.Conn {
	if p == nil {
		return nil
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.conn == nil || addr != p.addr {
		return nil
	}
	conn := p.conn
	p.conn = nil
	p.timer.Stop()
	return conn
}

// close closes the connection unless a dial already took it.
func (p *preDialed) close() {
	if p == nil {
		return
	}
	p.mu.Lock()
	conn := p.conn
	p.conn = nil
	if p.timer != nil {
		p.timer.Stop()
	}
	p.mu.Unlock()
	if conn != nil {
		conn.Close()
	}
}

// MaxLength caps request bodies: uploads ride ~50-byte queries, so big ones belong elsewhere.
func (c *Client) MaxLength() int { return 64 << 10 }

// RequestTimeout bounds a request through the tunnel, including session setup.
func (c *Client) RequestTimeout() time.Duration { return 90 * time.Second }
