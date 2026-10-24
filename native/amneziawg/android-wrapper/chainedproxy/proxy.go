// Package chainedproxy provides a loopback-only AWG bootstrap and second-layer exit gateway.
package chainedproxy

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"
)

type DialFunc func(context.Context, string, string) (net.Conn, error)

type Gateway struct {
	HTTPPort, SOCKSPort int
	secondLayerPort     int
	ctx                 context.Context
	cancel              context.CancelFunc
	mu                  sync.Mutex
	connections         map[io.Closer]struct{}
	closed              bool
	httpServer          *http.Server
	socks               net.Listener
	transport           *http.Transport
}

func Start(dial DialFunc, secondLayerPort int) (*Gateway, error) {
	if secondLayerPort < 1 || secondLayerPort > 65535 {
		return nil, errors.New("invalid second-layer SOCKS port")
	}
	ctx, cancel := context.WithCancel(context.Background())
	g := &Gateway{
		ctx: ctx, cancel: cancel, connections: make(map[io.Closer]struct{}),
		secondLayerPort: secondLayerPort,
	}
	upstream, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		cancel()
		return nil, err
	}
	g.HTTPPort = upstream.Addr().(*net.TCPAddr).Port
	g.socks, err = net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		upstream.Close()
		cancel()
		return nil, err
	}
	g.SOCKSPort = g.socks.Addr().(*net.TCPAddr).Port
	g.transport = &http.Transport{DialContext: dial, Proxy: nil, MaxIdleConns: 16, IdleConnTimeout: 30 * time.Second}
	g.httpServer = &http.Server{ReadHeaderTimeout: 15 * time.Second, MaxHeaderBytes: 32 * 1024, Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodConnect {
			if !r.URL.IsAbs() {
				http.Error(w, "absolute proxy URL required", 400)
				return
			}
			req := r.Clone(g.ctx)
			req.RequestURI = ""
			stripHopHeaders(req.Header)
			response, err := g.transport.RoundTrip(req)
			if err != nil {
				http.Error(w, "AmneziaWG upstream unavailable", 502)
				return
			}
			defer response.Body.Close()
			stripHopHeaders(response.Header)
			for k, v := range response.Header {
				w.Header()[k] = v
			}
			w.WriteHeader(response.StatusCode)
			io.Copy(w, response.Body)
			return
		}
		if _, _, err := net.SplitHostPort(r.Host); err != nil {
			http.Error(w, "invalid CONNECT authority", 400)
			return
		}
		ctx, cancel := context.WithTimeout(g.ctx, 30*time.Second)
		remote, err := dial(ctx, "tcp", r.Host)
		cancel()
		if err != nil {
			http.Error(w, "AmneziaWG upstream unavailable", 502)
			return
		}
		client, buffer, err := w.(http.Hijacker).Hijack()
		if err != nil {
			remote.Close()
			return
		}
		if !g.track(client) {
			remote.Close()
			return
		}
		defer g.release(client)
		if !g.track(remote) {
			return
		}
		defer g.release(remote)
		if _, err = buffer.WriteString("HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
			return
		}
		if err = buffer.Flush(); err != nil {
			return
		}
		relay(client, buffer, remote)
	})}
	go g.httpServer.Serve(upstream)
	go func() {
		for {
			client, err := g.socks.Accept()
			if err != nil {
				return
			}
			if !g.track(client) {
				continue
			}
			go func() { defer g.release(client); g.serveSOCKS(client, dial) }()
		}
	}()
	return g, nil
}

func stripHopHeaders(h http.Header) {
	for _, k := range []string{"Proxy-Authorization", "Proxy-Connection", "Connection", "Keep-Alive", "TE", "Trailer", "Transfer-Encoding", "Upgrade"} {
		h.Del(k)
	}
}
func (g *Gateway) track(c io.Closer) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.closed {
		c.Close()
		return false
	}
	g.connections[c] = struct{}{}
	return true
}
func (g *Gateway) release(c io.Closer) {
	c.Close()
	g.mu.Lock()
	delete(g.connections, c)
	g.mu.Unlock()
}
func (g *Gateway) Close() {
	g.cancel()
	g.httpServer.Close()
	g.transport.CloseIdleConnections()
	g.socks.Close()
	g.mu.Lock()
	g.closed = true
	for c := range g.connections {
		c.Close()
	}
	g.mu.Unlock()
}
func relay(client net.Conn, reader io.Reader, remote net.Conn) {
	done := make(chan struct{})
	go func() { io.Copy(remote, reader); remote.Close(); close(done) }()
	io.Copy(client, remote)
	client.Close()
	<-done
}
func readAddress(r io.Reader) (string, error) {
	var kind [1]byte
	if _, err := io.ReadFull(r, kind[:]); err != nil {
		return "", err
	}
	return readAddressWithType(r, kind[0])
}

func readAddressWithType(r io.Reader, kind byte) (string, error) {
	var host string
	switch kind {
	case 1:
		b := make([]byte, 4)
		if _, err := io.ReadFull(r, b); err != nil {
			return "", err
		}
		host = net.IP(b).String()
	case 4:
		b := make([]byte, 16)
		if _, err := io.ReadFull(r, b); err != nil {
			return "", err
		}
		host = net.IP(b).String()
	case 3:
		var size [1]byte
		if _, err := io.ReadFull(r, size[:]); err != nil {
			return "", err
		}
		if size[0] == 0 {
			return "", errors.New("empty host")
		}
		b := make([]byte, int(size[0]))
		if _, err := io.ReadFull(r, b); err != nil {
			return "", err
		}
		host = string(b)
	default:
		return "", errors.New("unsupported address")
	}
	var port [2]byte
	if _, err := io.ReadFull(r, port[:]); err != nil {
		return "", err
	}
	return net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(port[:])))), nil
}

func encodeAddress(address string) ([]byte, error) {
	host, portText, err := net.SplitHostPort(address)
	if err != nil {
		return nil, err
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 {
		return nil, errors.New("invalid destination port")
	}
	encoded := []byte{5, 1, 0}
	if ip := net.ParseIP(host); ip != nil {
		if ipv4 := ip.To4(); ipv4 != nil {
			encoded = append(encoded, 1)
			encoded = append(encoded, ipv4...)
		} else {
			encoded = append(encoded, 4)
			encoded = append(encoded, ip.To16()...)
		}
	} else {
		if len(host) == 0 || len(host) > 255 {
			return nil, errors.New("invalid destination host")
		}
		encoded = append(encoded, 3, byte(len(host)))
		encoded = append(encoded, host...)
	}
	return binary.BigEndian.AppendUint16(encoded, uint16(port)), nil
}

func writeAll(w io.Writer, payload []byte) error {
	for len(payload) > 0 {
		n, err := w.Write(payload)
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
		payload = payload[n:]
	}
	return nil
}

// dialSecondLayer sends user TCP through the local Psiphon/Tor SOCKS listener.
// The AWG-backed dialer is deliberately not used as a fallback: that would leak
// the first-hop IP when the requested second layer is unavailable.
func (g *Gateway) dialSecondLayer(ctx context.Context, address string) (net.Conn, error) {
	endpoint := net.JoinHostPort("127.0.0.1", strconv.Itoa(g.secondLayerPort))
	remote, err := (&net.Dialer{}).DialContext(ctx, "tcp", endpoint)
	if err != nil {
		return nil, err
	}
	stopOnCancel := context.AfterFunc(ctx, func() { remote.Close() })
	defer stopOnCancel()
	failed := func(err error) (net.Conn, error) {
		remote.Close()
		return nil, err
	}
	if deadline, ok := ctx.Deadline(); ok {
		if err := remote.SetDeadline(deadline); err != nil {
			return failed(err)
		}
	}
	if err := writeAll(remote, []byte{5, 1, 0}); err != nil {
		return failed(err)
	}
	var greeting [2]byte
	if _, err := io.ReadFull(remote, greeting[:]); err != nil {
		return failed(err)
	}
	if greeting[0] != 5 || greeting[1] != 0 {
		return failed(errors.New("second-layer SOCKS does not support no-auth mode"))
	}
	request, err := encodeAddress(address)
	if err != nil {
		return failed(err)
	}
	if err := writeAll(remote, request); err != nil {
		return failed(err)
	}
	var response [4]byte
	if _, err := io.ReadFull(remote, response[:]); err != nil {
		return failed(err)
	}
	if response[0] != 5 || response[2] != 0 {
		return failed(errors.New("invalid second-layer SOCKS response"))
	}
	if _, err := readAddressWithType(remote, response[3]); err != nil {
		return failed(err)
	}
	if response[1] != 0 {
		return failed(errors.New("second-layer SOCKS CONNECT failed"))
	}
	if err := remote.SetDeadline(time.Time{}); err != nil {
		return failed(err)
	}
	return remote, nil
}
func reply(c net.Conn, code byte) error {
	_, err := c.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
	return err
}
func (g *Gateway) serveSOCKS(c net.Conn, dial DialFunc) {
	c.SetDeadline(time.Now().Add(15 * time.Second))
	r := bufio.NewReader(c)
	var greeting [2]byte
	if _, err := io.ReadFull(r, greeting[:]); err != nil || greeting[0] != 5 {
		return
	}
	methods := make([]byte, int(greeting[1]))
	if _, err := io.ReadFull(r, methods); err != nil {
		return
	}
	noAuth := false
	for _, method := range methods {
		if method == 0 {
			noAuth = true
		}
	}
	if !noAuth {
		c.Write([]byte{5, 255})
		return
	}
	if _, err := c.Write([]byte{5, 0}); err != nil {
		return
	}
	var header [3]byte
	if _, err := io.ReadFull(r, header[:]); err != nil || header[0] != 5 || header[2] != 0 {
		return
	}
	address, err := readAddress(r)
	if err != nil {
		reply(c, 8)
		return
	}
	switch header[1] {
	case 1:
		// User TCP must use the second layer; falling back to AWG would leak its
		// first-hop exit IP and contradict the active Psiphon/Tor profile.
		ctx, cancel := context.WithTimeout(g.ctx, 30*time.Second)
		remote, err := g.dialSecondLayer(ctx, address)
		cancel()
		if err != nil {
			reply(c, 5)
			return
		}
		if !g.track(remote) {
			return
		}
		defer g.release(remote)
		if reply(c, 0) != nil {
			return
		}
		c.SetDeadline(time.Time{})
		relay(c, r, remote)
	case 3:
		// The second-layer mobile SOCKS listener is TCP-only. Preserve DNS and
		// other UDP over AWG, but drop UDP/443 so browsers fall back to TCP and
		// cannot expose the AWG exit IP through QUIC/HTTP3.
		g.serveUDP(c, r, dial)
	default:
		reply(c, 7)
	}
}
func (g *Gateway) serveUDP(control net.Conn, reader io.Reader, dial DialFunc) {
	udp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		reply(control, 1)
		return
	}
	if !g.track(udp) {
		return
	}
	defer g.release(udp)
	port := udp.LocalAddr().(*net.UDPAddr).Port
	if _, err := control.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, byte(port >> 8), byte(port)}); err != nil {
		return
	}
	control.SetDeadline(time.Time{})
	done := make(chan struct{})
	go func() { io.Copy(io.Discard, reader); udp.Close(); close(done) }()
	var client *net.UDPAddr
	buffer := make([]byte, 65535)
	type flow struct {
		conn   net.Conn
		header []byte
	}
	var flowsMu sync.Mutex
	flows := make(map[string]*flow)
	defer func() {
		flowsMu.Lock()
		for _, f := range flows {
			f.conn.Close()
		}
		flowsMu.Unlock()
	}()
	for {
		n, from, err := udp.ReadFromUDP(buffer)
		if err != nil {
			break
		}
		if !from.IP.IsLoopback() || n < 4 || buffer[0] != 0 || buffer[1] != 0 || buffer[2] != 0 {
			continue
		}
		if client == nil {
			client = from
		}
		if client.String() != from.String() {
			continue
		}
		packet := bufio.NewReaderSize(bytesReader(buffer[3:n]), n)
		address, err := readAddress(packet)
		if err != nil {
			continue
		}
		_, portText, err := net.SplitHostPort(address)
		if err != nil {
			continue
		}
		if portText == "443" {
			continue
		}
		payload, err := io.ReadAll(packet)
		if err != nil {
			continue
		}
		flowsMu.Lock()
		f := flows[address]
		full := len(flows) >= 64
		flowsMu.Unlock()
		if f == nil {
			if full {
				continue
			}
			ctx, cancel := context.WithTimeout(g.ctx, 5*time.Second)
			remote, err := dial(ctx, "udp", address)
			cancel()
			if err != nil {
				continue
			}
			if !g.track(remote) {
				break
			}
			f = &flow{conn: remote, header: append([]byte(nil), buffer[:n-len(payload)]...)}
			flowsMu.Lock()
			flows[address] = f
			flowsMu.Unlock()
			destination := *client
			go func(address string, f *flow) {
				defer func() {
					flowsMu.Lock()
					if flows[address] == f {
						delete(flows, address)
					}
					flowsMu.Unlock()
					g.release(f.conn)
				}()
				response := make([]byte, 65507)
				for {
					count, err := f.conn.Read(response)
					if err != nil {
						return
					}
					out := append(append([]byte(nil), f.header...), response[:count]...)
					if _, err = udp.WriteToUDP(out, &destination); err != nil {
						return
					}
				}
			}(address, f)
		}
		f.conn.SetDeadline(time.Now().Add(90 * time.Second))
		if _, err := f.conn.Write(payload); err != nil {
			f.conn.Close()
		}
	}
	control.Close()
	<-done
}

// Small immutable reader so no packet can alias the reused receive buffer.
func bytesReader(b []byte) io.Reader { return &packetReader{data: append([]byte(nil), b...)} }

type packetReader struct{ data []byte }

func (r *packetReader) Read(p []byte) (int, error) {
	if len(r.data) == 0 {
		return 0, io.EOF
	}
	n := copy(p, r.data)
	r.data = r.data[n:]
	return n, nil
}
