package main

import (
	"bytes"
	"flag"
	"strings"
	"testing"
)

func TestScannerEditionCommands(t *testing.T) {
	for _, name := range []string{"socks", "find-junk", "find-sni"} {
		if lookupCommand(name) != nil {
			t.Fatalf("removed command available: %s", name)
		}
	}
	for _, name := range []string{"register", "scan", "version"} {
		if lookupCommand(name) == nil {
			t.Fatalf("missing command %s", name)
		}
	}
	var help bytes.Buffer
	rootUsage(&help)
	if strings.Contains(help.String(), "SOCKS5 proxy") {
		t.Fatal("help advertises a removed server")
	}
	fs := flag.NewFlagSet("scan", flag.ContinueOnError)
	var opts options
	lookupCommand("scan").setup(fs, &opts)
	if fs.Lookup("listen") != nil {
		t.Fatal("scan must not accept a proxy listener")
	}
	if fs.Lookup("port") == nil {
		t.Fatal("endpoint probe port option must remain available")
	}
}

func TestRegistrationHasNoProxyOrRelayFlags(t *testing.T) {
	fs := flag.NewFlagSet("register", flag.ContinueOnError)
	var opts options
	lookupCommand("register").setup(fs, &opts)
	for _, name := range []string{"proxy", "x", "relay"} {
		if fs.Lookup(name) != nil {
			t.Fatalf("register must not expose removed proxy option -%s", name)
		}
	}
	if fs.Lookup("fresh") == nil {
		t.Fatal("register must retain account refresh support")
	}
}
