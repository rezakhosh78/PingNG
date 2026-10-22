package main

import (
	"context"
	"testing"
	"time"
)

type poolTestTunnel struct{}

func (*poolTestTunnel) handshake(context.Context, string, time.Duration) bool { return false }
func (*poolTestTunnel) ports() []int                                          { return nil }
func (*poolTestTunnel) attempts() int                                         { return 1 }
func (*poolTestTunnel) stack() *ipStack                                       { return nil }
func (*poolTestTunnel) Close()                                                {}

// Each phase must start all requested jobs before one job can finish; this
// catches a later phase silently running on only its first prepared tunnel.
func TestTunnelPoolUsesParallelJobsInEveryPhase(t *testing.T) {
	for phase := 1; phase <= 2; phase++ {
		started := make(chan struct{}, 8)
		release := make(chan struct{})
		finished := make(chan error, 1)
		lastReady := 0
		go func() {
			finished <- runTunnelPoolWithFactory(4, protoRun{}, 8,
				func(ready, total int) { lastReady = ready },
				func(protoRun) (tunnel, error) { return &poolTestTunnel{}, nil },
				func(tn tunnel, i int) { started <- struct{}{}; <-release },
			)
		}()
		for worker := 0; worker < 4; worker++ {
			select {
			case <-started:
			case <-time.After(2 * time.Second):
				close(release)
				<-finished
				t.Fatalf("phase %d started only %d of 4 jobs", phase, worker)
			}
		}
		close(release)
		if err := <-finished; err != nil {
			t.Fatalf("phase %d: %v", phase, err)
		}
		if lastReady != 4 {
			t.Fatalf("phase %d prepared %d of 4 workers", phase, lastReady)
		}
	}
}
