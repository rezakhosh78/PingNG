package fragment

import (
	"bytes"
	"net"
	"testing"
)

func TestIsCloudflareWorkersHost(t *testing.T) {
	tests := []struct {
		host string
		want bool
	}{
		{host: "workers.dev", want: true},
		{host: "api.account.workers.dev", want: true},
		{host: "API.Account.WORKERS.DEV.", want: true},
		{host: "workers.dev.example.com", want: false},
		{host: "example.com", want: false},
		{host: "", want: false},
	}

	for _, test := range tests {
		t.Run(test.host, func(t *testing.T) {
			if got := isCloudflareWorkersHost(test.host); got != test.want {
				t.Fatalf("isCloudflareWorkersHost(%q) = %t, want %t", test.host, got, test.want)
			}
		})
	}
}

type recordingConn struct {
	net.Conn
	writes [][]byte
}

func (c *recordingConn) Write(data []byte) (int, error) {
	c.writes = append(c.writes, append([]byte(nil), data...))
	return len(data), nil
}

func TestWorkersClientHelloIsWrittenIntact(t *testing.T) {
	clientHello := testClientHello("api.account.workers.dev")
	conn := &recordingConn{}
	adapter := &Adapter{
		conn:         conn,
		isFirstWrite: true,
		BSL:          [2]int{1, 1},
		SL:           [2]int{1, 1},
		ASL:          [2]int{1, 1},
	}

	written, err := adapter.Write(clientHello)
	if err != nil {
		t.Fatalf("Adapter.Write() error = %v", err)
	}
	if written != len(clientHello) {
		t.Fatalf("Adapter.Write() = %d bytes, want %d", written, len(clientHello))
	}
	if len(conn.writes) != 1 {
		t.Fatalf("ClientHello made %d writes, want one intact write", len(conn.writes))
	}
	if !bytes.Equal(conn.writes[0], clientHello) {
		t.Fatal("ClientHello was changed")
	}
}

func TestOtherClientHelloStillUsesConfiguredFragments(t *testing.T) {
	clientHello := testClientHello("api.example.com")
	conn := &recordingConn{}
	adapter := &Adapter{
		conn:         conn,
		isFirstWrite: true,
		BSL:          [2]int{2, 2},
		SL:           [2]int{2, 2},
		ASL:          [2]int{2, 2},
	}

	if _, err := adapter.Write(clientHello); err != nil {
		t.Fatalf("Adapter.Write() error = %v", err)
	}
	if len(conn.writes) <= 1 {
		t.Fatalf("non-Worker ClientHello made %d write, want fragmentation", len(conn.writes))
	}
	var reassembled []byte
	for _, write := range conn.writes {
		reassembled = append(reassembled, write...)
	}
	if !bytes.Equal(reassembled, clientHello) {
		t.Fatal("fragmentation changed the ClientHello bytes")
	}
}

func testClientHello(host string) []byte {
	hostBytes := []byte(host)
	name := append([]byte{0, byte(len(hostBytes) >> 8), byte(len(hostBytes))}, hostBytes...)
	serverNames := append([]byte{byte(len(name) >> 8), byte(len(name))}, name...)
	serverNameExtension := append([]byte{0, 0, byte(len(serverNames) >> 8), byte(len(serverNames))}, serverNames...)

	body := []byte{0x03, 0x03}
	body = append(body, make([]byte, 32)...)
	body = append(body, 0, 0, 2, 0x13, 0x01, 1, 0)
	body = append(body, byte(len(serverNameExtension)>>8), byte(len(serverNameExtension)))
	body = append(body, serverNameExtension...)

	handshake := []byte{1, byte(len(body) >> 16), byte(len(body) >> 8), byte(len(body))}
	handshake = append(handshake, body...)
	record := []byte{22, 3, 1, byte(len(handshake) >> 8), byte(len(handshake))}
	return append(record, handshake...)
}
