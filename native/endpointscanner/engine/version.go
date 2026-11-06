package main

import (
	"context"
	"fmt"
	"runtime/debug"
	"strings"
)

// May be set by the build using -X main.version.
var version = "dev"

func runVersionCmd(context.Context, options) error {
	fmt.Println(resolveVersion())
	return nil
}

func resolveVersion() string {
	if version != "dev" {
		return version
	}
	// "go install ...@latest" embeds the module version; a plain "go build" gives "(devel)".
	if bi, ok := debug.ReadBuildInfo(); ok && bi.Main.Version != "" && bi.Main.Version != "(devel)" {
		return strings.TrimPrefix(bi.Main.Version, "v")
	}
	return version
}
