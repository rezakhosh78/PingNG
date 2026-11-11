package masqueclient

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"math/big"
	"net"
	"testing"
	"time"

	"golang.org/x/net/http2"
)

func TestPeerKeyPinning(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	other, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{SerialNumber: big.NewInt(1), NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour)}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name    string
		peer    *ecdsa.PublicKey
		wantErr bool
	}{
		{"matching key", &key.PublicKey, false},
		{"different key", &other.PublicKey, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			cfg, err := PrepareTlsConfig(key, tc.peer, [][]byte{der}, "test.invalid", false)
			if err != nil {
				t.Fatal(err)
			}
			if cfg.VerifyPeerCertificate == nil {
				t.Fatal("public-key verifier missing")
			}
			if err := cfg.VerifyPeerCertificate([][]byte{der}, nil); (err != nil) != tc.wantErr {
				t.Fatalf("verification error = %v, wantError %v", err, tc.wantErr)
			}
			if err := cfg.VerifyPeerCertificate([][]byte{[]byte("invalid certificate")}, nil); err == nil {
				t.Fatal("malformed certificate accepted")
			}
		})
	}
}

func TestMissingEndpointRejected(t *testing.T) {
	for _, h2 := range []bool{false, true} {
		udp, tr, conn, _, err := ConnectTunnel(context.Background(), nil, nil, "https://example.invalid", nil, h2)
		if err == nil || udp != nil || tr != nil || conn != nil {
			t.Fatalf("missing endpoint accepted for HTTP2=%v", h2)
		}
	}
}

func TestHTTP2ClientUsesDirectTransport(t *testing.T) {
	t.Setenv("HTTPS_PROXY", "http://127.0.0.1:1")
	t.Setenv("HTTP_PROXY", "http://127.0.0.1:1")
	client, err := newHTTP2Client(&tls.Config{}, &net.TCPAddr{IP: net.ParseIP("192.0.2.1"), Port: 443})
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := client.Transport.(*http2.Transport); !ok {
		t.Fatalf("HTTP/2 client transport = %T, want direct http2.Transport", client.Transport)
	}
}
