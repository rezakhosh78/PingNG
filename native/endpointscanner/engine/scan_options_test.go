package main

import (
	"flag"
	"testing"
)

func TestScanOptionAliases(t *testing.T) {
	fs := flag.NewFlagSet("scan", flag.ContinueOnError)
	var opts options
	setupScanFlags(fs, &opts)
	if err := fs.Parse([]string{
		"-timeout-seconds", "7",
		"-parallel-jobs", "18",
		"-ip-family", "ipv6",
		"-port-scan-mode", "all-open-ports",
	}); err != nil {
		t.Fatal(err)
	}
	if err := applyPortScanMode(fs, &opts); err != nil {
		t.Fatal(err)
	}
	if err := applyIPFamily(fs, &opts); err != nil {
		t.Fatal(err)
	}
	if opts.timeoutSec != 7 || opts.tunnelParallel != 18 || !opts.ipv6 || opts.sweepPorts != sweepOpen {
		t.Fatalf("unexpected scan options: timeout=%d jobs=%d ipv6=%v sweep=%q", opts.timeoutSec, opts.tunnelParallel, opts.ipv6, opts.sweepPorts)
	}
}

func TestParallelJobsDefault(t *testing.T) {
	fs := flag.NewFlagSet("scan", flag.ContinueOnError)
	var opts options
	setupScanFlags(fs, &opts)
	if err := fs.Parse(nil); err != nil {
		t.Fatal(err)
	}
	if opts.tunnelParallel != 30 {
		t.Fatalf("parallel jobs default = %d, want 30", opts.tunnelParallel)
	}
}

func TestPortScanModeMappings(t *testing.T) {
	tests := []struct {
		mode string
		want string
	}{
		{"first-reachable", ""},
		{"all-open-ports", sweepOpen},
		{"all-known-ports", sweepAll},
	}
	for _, test := range tests {
		t.Run(test.mode, func(t *testing.T) {
			fs := flag.NewFlagSet("scan", flag.ContinueOnError)
			var opts options
			setupScanFlags(fs, &opts)
			if err := fs.Parse([]string{"-port-scan-mode", test.mode}); err != nil {
				t.Fatal(err)
			}
			if err := applyPortScanMode(fs, &opts); err != nil {
				t.Fatal(err)
			}
			if opts.sweepPorts != test.want {
				t.Fatalf("sweep mode = %q, want %q", opts.sweepPorts, test.want)
			}
		})
	}
}

func TestPortScanModeRejectsLegacyConflict(t *testing.T) {
	fs := flag.NewFlagSet("scan", flag.ContinueOnError)
	var opts options
	setupScanFlags(fs, &opts)
	if err := fs.Parse([]string{"-port-scan-mode", "first-reachable", "-sweep-ports", "all"}); err != nil {
		t.Fatal(err)
	}
	if err := applyPortScanMode(fs, &opts); err == nil {
		t.Fatal("expected conflicting port scan flags to fail")
	}
}

func TestParallelJobsAliasSurvivesNestedDefault(t *testing.T) {
	fs := flag.NewFlagSet("scan", flag.ContinueOnError)
	var opts options
	setupScanFlags(fs, &opts)
	if err := fs.Parse([]string{"-through", "1.2.3.4:2408", "-parallel-jobs", "9"}); err != nil {
		t.Fatal(err)
	}
	applyNestedJobs(fs, &opts)
	if opts.tunnelParallel != 9 {
		t.Fatalf("parallel jobs = %d, want 9", opts.tunnelParallel)
	}
}
