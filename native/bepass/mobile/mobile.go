// Package bepass exposes the Bepass SOCKS listener to PingNG's isolated
// Android runtime. The package intentionally exports only Start and Stop.
package bepass

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/bepass-org/bepass/config"
	bepassServer "github.com/bepass-org/bepass/server"
)

const startupTimeout = 12 * time.Second

var lifecycle struct {
	sync.Mutex
	running bool
	done    chan error
}

// Start starts the local SOCKS5/HTTP listener and returns only after its
// loopback port accepts connections. configJSON uses Bepass's config.json
// field names. BindAddress must be a loopback address supplied by PingNG.
func Start(configJSON string) error {
	lifecycle.Lock()
	defer lifecycle.Unlock()
	if lifecycle.running {
		return nil
	}

	var next config.Config
	if err := json.Unmarshal([]byte(configJSON), &next); err != nil {
		return fmt.Errorf("invalid Bepass configuration: %w", err)
	}
	bindHost, _, err := net.SplitHostPort(next.BindAddress)
	if err != nil || !strings.EqualFold(bindHost, "127.0.0.1") {
		return errors.New("Bepass BindAddress must use 127.0.0.1")
	}
	if next.DnsCacheTTL <= 0 {
		next.DnsCacheTTL = 1800
	}
	if next.DnsRequestTimeout <= 0 {
		next.DnsRequestTimeout = 10
	}
	config.G = &next

	lifecycle.done = make(chan error, 1)
	go func() {
		lifecycle.done <- bepassServer.Run(false)
	}()
	deadline := time.Now().Add(startupTimeout)
	for time.Now().Before(deadline) {
		conn, dialErr := net.DialTimeout("tcp", next.BindAddress, 150*time.Millisecond)
		if dialErr == nil {
			_ = conn.Close()
			lifecycle.running = true
			return nil
		}
		select {
		case runErr := <-lifecycle.done:
			_ = bepassServer.ShutDown()
			lifecycle.done = nil
			if runErr == nil {
				return errors.New("Bepass stopped before its SOCKS listener became ready")
			}
			return fmt.Errorf("Bepass failed to start: %w", runErr)
		default:
			time.Sleep(60 * time.Millisecond)
		}
	}
	_ = bepassServer.ShutDown()
	select {
	case <-lifecycle.done:
	case <-time.After(3 * time.Second):
	}
	lifecycle.done = nil
	return errors.New("timed out waiting for the Bepass SOCKS listener")
}

// Stop closes both the public SOCKS listener and Bepass's private HTTP
// adapter. It is safe to call more than once.
func Stop() error {
	lifecycle.Lock()
	defer lifecycle.Unlock()
	if lifecycle.done == nil {
		lifecycle.running = false
		return nil
	}

	shutdownErr := bepassServer.ShutDown()
	select {
	case runErr := <-lifecycle.done:
		if runErr != nil && !errors.Is(runErr, http.ErrServerClosed) && !errors.Is(runErr, net.ErrClosed) && shutdownErr == nil {
			shutdownErr = runErr
		}
	case <-time.After(3 * time.Second):
		if shutdownErr == nil {
			shutdownErr = errors.New("timed out waiting for Bepass to stop")
		}
	}
	lifecycle.done = nil
	lifecycle.running = false
	return shutdownErr
}
