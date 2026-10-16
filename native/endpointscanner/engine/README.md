# Endpoint Scanner — scanner edition

This source edition retains `scan`, `register` and `version`.

## Build and run
Use the Go version required by go.mod (1.26.3 or a compatible newer toolchain).

```sh
go test ./...
go build -buildvcs=false -o endpointscanner .
./endpointscanner register
./endpointscanner scan
./endpointscanner scan -h
```

With an existing compatible account file, registration need not be repeated. For a custom account path, pass `-a FILE` to both register and scan.

All existing scan flags and their defaults are retained. WG, AWG, MASQUE and MASQUE-H2, port discovery, endpoint verification, in-tunnel ping, speed measurements, country/node filters, reports, config export, plain output, TUI and nested scanning remain. Use `scan -h` for authoritative option help.

Removed: built-in SOCKS server, registration proxy and relay options, MASQUE HTTP/2 environment-proxy support, automatic junk/SNI search commands, GitHub update checking and its cache, upstream binary installer, release/container packaging and obsolete documentation/screenshots. Registration tries the Cloudflare API directly, then uses the scanner's WARP tunnel fallback. Manual AWG and MASQUE parameters remain usable by scan.

Network and tunnel dependencies remain. This is functional scope reduction, not a claim that every dependency is minimal or that antivirus acceptance is established. See CHANGES-SCANNER.md for validation limits. Android build integration and a compiled native binary are not included.

Validation: Go 1.26.3 unit tests and Linux amd64 build passed. Android and live endpoint scanning remain untested. See the current status at the top of CHANGES-SCANNER.md.

## Isolated MASQUE client
The MASQUE client helpers now live in internal/masqueclient, with the original
MIT license and pinned upstream provenance. Unrelated usque SOCKS server packages
are no longer in the build dependency graph. The HTTP/2 client dials its selected
endpoint directly and does not honor proxy environment variables. Android and
live-network scanning still require validation in the consuming application.


### Endpoint scan controls for app integrations

Use `--timeout-seconds N` to set the per-request timeout and `--parallel-jobs N` to set phase 2 tunnel workers (default: 30). `--ip-family ipv4|ipv6` selects the endpoint pool family. `--port-scan-mode` accepts `first-reachable` (default), `all-open-ports` (sweeps ports discovered in phase 1), or `all-known-ports` (sweeps the complete known WARP port set and skips phase 1). Legacy flags remain accepted.
