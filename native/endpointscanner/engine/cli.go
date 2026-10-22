package main

import (
	"context"
	"flag"
	"fmt"
	"math"
	"os"
	"runtime/debug"
)

type command struct {
	name   string
	brief  string
	intro  []string
	groups []flagGroup
	setup  func(*flag.FlagSet, *options)
	run    func(context.Context, options) error
}

var commands = []command{
	{
		name:  "scan",
		brief: "scan WARP endpoint pools for working, low-latency endpoints",
		intro: []string{
			"Two-phase scan:",
			"  - Phase 1 finds which WARP ports get through this network",
			"  - Phase 2 verifies each endpoint's real exit colo through a WARP tunnel",
			"",
			"Working endpoints are reported grouped per subnet. ENDPOINT PING is the ICMP",
			"ping to the endpoint address; -tun-ping adds TUN PING, measured inside the tunnel.",
			"Needs a WARP account: run \"endpointscanner register\" first.",
			"",
			"-proto masque scans Cloudflare's MASQUE service instead: it has no endpoint",
			"pools, so the run covers two anycast addresses on a fixed set of ports and",
			"skips phase 1 - a MASQUE dial succeeds on ports that then pass no data, so",
			"only the full tunnel check tells them apart. Those endpoints flap, hence",
			"-masque-attempts, and the SNI is what gets them past DPI, hence -masque-sni.",
		},
		groups: []flagGroup{scanGroup, protoGroup, awgGroup, masqueGroup, nestGroup, outputGroup},
		setup:  setupScanFlags,
		run:    runScanCmd,
	},
	{
		name:  "register",
		brief: "register a WARP account and save it",
		intro: []string{
			"Writes a WARP account to the account file - every other command needs it.",
			"When the file already holds an account, its id and token are reused to",
			"rotate the keys instead of burning a new registration; -fresh forces a",
			"brand-new account.",
			"",
			"Tries the Cloudflare API directly, then falls back to registering through",
			"a WARP tunnel when the API is unreachable; AmneziaWG parameters below",
			"only shape that fallback.",
			"",
			"A MASQUE device is registered alongside, because \"-proto masque\" needs its",
			"own one. Failing to get it only prints a warning - the rest still works.",
		},
		groups: []flagGroup{registerGroup, awgGroup, plainGroup},
		setup:  setupRegisterFlags,
		run:    runRegisterCmd,
	},
	{
		name:  "version",
		brief: "print the endpointscanner version",
		intro: []string{"Prints the version alone, so a script can compare it as is."},
		setup: func(*flag.FlagSet, *options) {},
		run:   runVersionCmd,
	},
}

// gvisor allocates per packet, so a -f scan doubles the heap between GCs and the
// OOM killer wins on a small host; measured, the peak plateaus at this limit.
const defaultMemLimit = 32 << 20

func main() {
	// The runtime default is math.MaxInt64, so this leaves a GOMEMLIMIT set
	// from the environment alone.
	if debug.SetMemoryLimit(-1) == math.MaxInt64 {
		debug.SetMemoryLimit(defaultMemLimit)
	}
	stripLoaderArg()
	enableVirtualTerminal()
	errPal = palette{enabled: colorEnabled(os.Stderr)}

	if len(os.Args) < 2 {
		rootUsage(os.Stderr)
		os.Exit(2)
	}
	switch os.Args[1] {
	case "-h", "-help", "--help", "help":
		rootUsage(os.Stdout)
		os.Exit(0)
	}
	cmd := lookupCommand(os.Args[1])
	if cmd == nil {
		fmt.Fprintln(os.Stderr, errPal.fail(fmt.Sprintf("unknown command %q", os.Args[1])))
		fmt.Fprintln(os.Stderr)
		rootUsage(os.Stderr)
		os.Exit(2)
	}

	var opts options
	fs := flag.NewFlagSet(cmd.name, flag.ExitOnError)
	fs.Usage = func() { commandUsage(fs.Output(), *cmd, fs) }
	cmd.setup(fs, &opts)
	fs.Parse(os.Args[2:])
	applyCommonFlags(fs, &opts)

	showEmoji = opts.emoji

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	if err := cmd.run(ctx, opts); err != nil {
		fmt.Fprintln(os.Stderr, errPal.fail(err.Error()))
		os.Exit(1)
	}
}

func lookupCommand(name string) *command {
	for i, c := range commands {
		if c.name == name {
			return &commands[i]
		}
	}
	return nil
}
