package chainedproxy

import (
	"bufio"
	"encoding/hex"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/conn/bindtest"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
	"golang.org/x/crypto/curve25519"
)

func TestHTTPUpstreamCrossesEncryptedAmneziaTunnel(t *testing.T) {
	keyA := strings.Repeat("11", 32)
	keyB := strings.Repeat("22", 32)
	public := func(key string) string {
		raw, _ := hex.DecodeString(key)
		out, err := curve25519.X25519(raw, curve25519.Basepoint)
		if err != nil {
			t.Fatal(err)
		}
		return hex.EncodeToString(out)
	}
	binds := bindtest.NewChannelBinds()
	makeTunnel := func(address, key, peer, allowed, extra string, bind conn.Bind) *netstack.Net {
		tun, network, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr(address)}, []netip.Addr{netip.MustParseAddr("10.77.0.2")}, 1280)
		if err != nil {
			t.Fatal(err)
		}
		dev := device.NewDevice(tun, bind, device.NewLogger(device.LogLevelError, "test: "))
		t.Cleanup(dev.Close)
		settings := "private_key=" + key + "\njc=0\ns1=0\ns2=0\nh1=11\nh2=22\nh3=33\nh4=44\n" + extra + "public_key=" + peer + "\nallowed_ip=" + allowed + "\n"
		if err = dev.IpcSet(settings); err != nil {
			t.Fatal(err)
		}
		if err = dev.Up(); err != nil {
			t.Fatal(err)
		}
		return network
	}
	server := makeTunnel("10.77.0.2", keyB, public(keyA), "10.77.0.1/32", "", binds[1])
	tun, client, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.77.0.1")}, []netip.Addr{netip.MustParseAddr("10.77.0.2")}, 1280)
	if err != nil {
		t.Fatal(err)
	}
	dev := device.NewDevice(tun, binds[0], device.NewLogger(device.LogLevelError, "test: "))
	defer dev.Close()
	if err = dev.IpcSet("private_key=" + keyA + "\njc=0\ns1=0\ns2=0\nh1=11\nh2=22\nh3=33\nh4=44\npublic_key=" + public(keyB) + "\nendpoint=127.0.0.1:1\nallowed_ip=10.77.0.2/32\n"); err != nil {
		t.Fatal(err)
	}
	if err = dev.Up(); err != nil {
		t.Fatal(err)
	}
	listener, err := server.ListenTCPAddrPort(netip.MustParseAddrPort("10.77.0.2:8080"))
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		c, err := listener.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		io.Copy(c, c)
	}()
	gateway, err := Start(client.DialContext, 1)
	if err != nil {
		t.Fatal(err)
	}
	defer gateway.Close()
	c, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(gateway.HTTPPort)))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(10 * time.Second))
	io.WriteString(c, "CONNECT 10.77.0.2:8080 HTTP/1.1\r\nHost: 10.77.0.2:8080\r\n\r\n")
	reader := bufio.NewReader(c)
	response, err := http.ReadResponse(reader, &http.Request{Method: "CONNECT"})
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != 200 {
		t.Fatal(response.Status)
	}
	io.WriteString(c, "encrypted-amnezia")
	result := make([]byte, len("encrypted-amnezia"))
	if _, err = io.ReadFull(reader, result); err != nil {
		t.Fatal(err)
	}
	if string(result) != "encrypted-amnezia" {
		t.Fatal("corrupt tunnel payload")
	}
}
