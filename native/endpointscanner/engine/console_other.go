//go:build !windows

package main

import "github.com/charmbracelet/bubbles/spinner"

const (
	glyphOK   = "✔"
	glyphFail = "✗"
)

var scanSpinner = spinner.Dot

func enableVirtualTerminal() {}
