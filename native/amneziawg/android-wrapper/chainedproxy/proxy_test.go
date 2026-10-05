package chainedproxy

import (
	"bufio"
	"context"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/proxy"
)

func echo(t *testing.T) net.Listener {
	t.Helper()
	l, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	return l
}
func port(l net.Listener) int { return l.Addr().(*net.TCPAddr).Port }
func exchange(t *testing.T, c net.Conn) {
	t.Helper()
	defer c.Close()
	c.SetDeadline(time.Now().Add(3 * time.Second))
	c.Write([]byte("chained"))
	b := make([]byte, 7)
	if _, err := io.ReadFull(c, b); err != nil {
		t.Fatal(err)
	}
	if string(b) != "chained" {
		t.Fatal(string(b))
	}
}

func TestCONNECTUsesOnlyTunnelDialerAndPreservesBufferedBytes(t *testing.T) {
	target := echo(t)
	calls := make(chan string, 1)
	g, err := Start(func(ctx context.Context, network, address string) (net.Conn, error) {
		calls <- address
		return (&net.Dialer{}).DialContext(ctx, network, target.Addr().String())
	}, 1)
	if err != nil {
		t.Fatal(err)
	}
	defer g.Close()
	c, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(g.HTTPPort)))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(3 * time.Second))
	io.WriteString(c, "CONNECT tunnel-only.example:443 HTTP/1.1\r\nHost: tunnel-only.example:443\r\n\r\nearly")
	r := bufio.NewReader(c)
	response, err := http.ReadResponse(r, &http.Request{Method: "CONNECT"})
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != 200 {
		t.Fatal(response.Status)
	}
	b := make([]byte, 5)
	if _, err := io.ReadFull(r, b); err != nil {
		t.Fatal(err)
	}
	if string(b) != "early" {
		t.Fatal(string(b))
	}
	if <-calls != "tunnel-only.example:443" {
		t.Fatal("wrong tunnel destination")
	}
}

func TestFinalTCPUsesPsiphonAndNeverFallsBack(t *testing.T) {
	psiphon := echoSOCKS(t)
	g, err := Start(func(context.Context, string, string) (net.Conn, error) {
		t.Error("TCP bypassed Psiphon")
		return nil, io.EOF
	}, port(psiphon))
	if err != nil {
		t.Fatal(err)
	}
	defer g.Close()
	d, _ := proxy.SOCKS5("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(g.SOCKSPort)), nil, &net.Dialer{Timeout: time.Second})
	c, err := d.Dial("tcp", "user.example:443")
	if err != nil {
		t.Fatal(err)
	}
	exchange(t, c)
	psiphon.Close()
	if c, err = d.Dial("tcp", "user.example:443"); err == nil {
		c.Close()
		t.Fatal("unexpected fallback")
	}
}

func echoSOCKS(t *testing.T) net.Listener {
	l, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				c.SetDeadline(time.Now().Add(3 * time.Second))
				r := bufio.NewReader(c)
				var h [2]byte
				if _, err := io.ReadFull(r, h[:]); err != nil {
					return
				}
				methods := make([]byte, int(h[1]))
				io.ReadFull(r, methods)
				c.Write([]byte{5, 0})
				var req [3]byte
				io.ReadFull(r, req[:])
				address, err := readAddress(r)
				if err != nil {
					return
				}
				if !strings.HasSuffix(address, ":443") {
					return
				}
				reply(c, 0)
				io.Copy(c, r)
			}()
		}
	}()
	return l
}

func TestUDPDNSUsesTunnelAndCloseReleasesAssociation(t *testing.T) {
	requests := make(chan string, 1)
	g, err := Start(func(ctx context.Context, network, address string) (net.Conn, error) {
		requests <- network + ":" + address
		a, b := net.Pipe()
		go func() { defer b.Close(); buf := make([]byte, 32); n, _ := b.Read(buf); b.Write(buf[:n]) }()
		return a, nil
	}, 1)
	if err != nil {
		t.Fatal(err)
	}
	defer g.Close()
	c, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(g.SOCKSPort)))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(3 * time.Second))
	c.Write([]byte{5, 1, 0})
	var auth [2]byte
	io.ReadFull(c, auth[:])
	c.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0})
	var response [3]byte
	io.ReadFull(c, response[:])
	address, err := readAddress(c)
	if err != nil {
		t.Fatal(err)
	}
	u, err := net.Dial("udp", address)
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	u.SetDeadline(time.Now().Add(3 * time.Second))
	packet := []byte{0, 0, 0, 1, 1, 1, 1, 1, 0, 53, 'd', 'n', 's'}
	u.Write(packet)
	b := make([]byte, 100)
	n, err := u.Read(b)
	if err != nil {
		t.Fatal(err)
	}
	if string(b[:n]) != string(packet) {
		t.Fatal("bad DNS response")
	}
	if <-requests != "udp:1.1.1.1:53" {
		t.Fatal("DNS escaped AWG")
	}
	g.Close()
	if _, err = c.Read(b); err == nil {
		t.Fatal("control socket survived stop")
	}
}
