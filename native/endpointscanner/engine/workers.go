package main

import (
	"fmt"
	"os"
	"sync"
)

func runTunnelPool(workers int, run protoRun, n int, ready func(int, int), fn func(tn tunnel, i int)) error {
	return runTunnelPoolWithFactory(workers, run, n, ready, newTunnel, fn)
}

func runTunnelPoolWithFactory(workers int, run protoRun, n int, ready func(int, int), factory func(protoRun) (tunnel, error), fn func(tn tunnel, i int)) error {
	if n == 0 {
		return nil
	}
	if workers < 1 {
		workers = 1
	}
	if workers > n {
		workers = n
	}

	// Prepare the requested workers before dispatching probes. Previously the
	// first worker could consume a short scan while the others were still being
	// constructed one by one, especially after entering the next phase.
	type setupResult struct {
		tn  tunnel
		err error
	}
	results := make(chan setupResult, workers)
	setupSlots := make(chan struct{}, min(workers, 8))
	for range workers {
		go func() {
			setupSlots <- struct{}{}
			tn, err := factory(run)
			<-setupSlots
			results <- setupResult{tn: tn, err: err}
		}()
	}
	tunnels := make([]tunnel, 0, workers)
	var setupError error
	for range workers {
		result := <-results
		if result.err != nil {
			if setupError == nil {
				setupError = result.err
			}
			fmt.Fprintf(os.Stderr, "tunnel setup failed: %v\n", result.err)
		} else {
			tunnels = append(tunnels, result.tn)
		}
		if ready != nil {
			ready(len(tunnels), workers)
		}
	}
	if len(tunnels) == 0 {
		return fmt.Errorf("no tunnels could be created: %w", setupError)
	}
	defer func() {
		for _, tn := range tunnels {
			tn.Close()
		}
	}()

	jobs := make(chan int)
	var wg sync.WaitGroup
	for _, tn := range tunnels {
		wg.Add(1)
		go func(tn tunnel) {
			defer wg.Done()
			for i := range jobs {
				fn(tn, i)
			}
		}(tn)
	}
	for i := 0; i < n; i++ {
		jobs <- i
	}
	close(jobs)
	wg.Wait()
	return nil
}
