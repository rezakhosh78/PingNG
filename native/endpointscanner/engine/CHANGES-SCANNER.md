# Latest revision: remove proxy and relay registration paths (2026-10-14)

This update removes optional proxy-style network paths from the scanner:
- Registration no longer accepts `-proxy` or `-relay`; it tries the Cloudflare
  API directly and then falls back to the scanner's WARP tunnel.
- MASQUE HTTP/2 connects directly to the selected endpoint and ignores proxy
  environment variables.
- The generated help message no longer instructs users to start a SOCKS server.

WG, AWG, MASQUE, MASQUE-H2, nested scanning, measurements, reports and client
configuration export remain. Exported client configurations contain tunnel
proxy data for external clients; the scanner does not run a TCP proxy service.
Its local UDP sockets are used to originate tunnel connections.

Static review confirms the removed flags, relay transport, environment proxy
handling and SOCKS launch hint have no production code paths. The Go toolchain is
not installed in this editing environment, so compilation and automated tests
could not be run for this revision. No antivirus scan, Android native build, or
live endpoint scan was performed. This source-only change does not establish
that a particular antivirus detection will disappear; no obfuscation or
signature bypass was used. External Android `libwg-go.so` and `libwarpmasque.so`
are not included in this archive and were not changed.

---

## Historical changes and validation notes

The sections below describe earlier revisions and are retained as history.

# Scanner edition: removal of the built-in proxy server

Source archive commit: b49ef5e8a466164a64d669951521b58944346bc8.

## Findings
The original socks.go opened a configurable TCP listener (default 127.0.0.1:1080), accepted unauthenticated SOCKS CONNECT requests, resolved client-selected destinations inside a tunnel and copied traffic in both directions. Binding to a non-loopback address was possible. This is a genuine proxy capability; it is not proof of malicious intent or proof of the specific antivirus detection's cause.

## Changes
- Removed the complete SOCKS server implementation, including its accept loop, handshake, arbitrary-destination dialing and bidirectional relay.
- Removed the socks command, server-only options and flag setup.
- Removed tests of the deleted implementation; added command-scope regression coverage.
- Updated documentation to reflect removal.
- Kept endpoint scanning, registration, measurements and configuration export. The scan -port option is preserved.

## Remaining capabilities and limits
WG/AWG/MASQUE and nested scan tunnels remain because existing measurements depend on them. Registration can still use a user-supplied outbound proxy. Those are not the removed inbound SOCKS server. go.mod/go.sum remain unchanged: indirect SOCKS modules in a dependency graph do not establish that their server code is linked into an executable. Dependency pruning requires go mod tidy and inspection of go list -deps with the actual toolchain; no dependency removal is claimed here.

No packing, obfuscation, antivirus exclusions or signature-oriented alterations were applied. This change reduces functionality, not security visibility. No guarantee of antivirus acceptance is made. A false-positive assessment requires the actual detected binary, its SHA-256, build inputs and vendor review.

## Validation
Static review confirms that the removed server symbols have no remaining production references and the main-package production sources contain no net.Listen TCP server or io.Copy proxy relay. Go is not installed in the editing environment; compilation and the Go tests have NOT been run. On a machine with the Go version required by go.mod, run:

    go test ./...
    go build -o endpointscanner .

The existing GitHub test workflow runs go test ./... when triggered. No endpoint scan or third-party network test was run here.

## Android integration
This archive is the upstream Go project, not an Android Studio project. It contains neither app/libs/arm64-v8a/libendpointscanner.so nor the Gradle task that produces it. No Android binary is supplied. Rebuild the native component from this source using the application's existing build integration; a stale bundled binary will retain its old behavior. Verify ABI, executable-vs-JNI format, invocation arguments and packaging against the Android project before replacing anything.

## Second pass: scanner-only command surface
Removed find-junk and find-sni command implementations, their exclusive flags and tests; removed online update lookup/cache and corresponding tests. Kept shared i1Note/i1NoteFor helpers byte-for-byte in scan_notes.go because scan reporting and registration call them. Retained register and version for account provisioning and integration compatibility. Removed upstream installer/release/container assets and replaced documentation with a scanner-specific README.

Static comparison against the preceding edition verifies all surviving production function bodies are unchanged except CLI main (which no longer invokes the update checker). The complete setupScanFlags function is unchanged. The engine, protocol code, registration, workers, measurements and report logic are unchanged. No Go compiler/test execution or on-device validation was possible. The earlier build commands remain applicable.


## Explicit scan-option aliases

The scanner accepts descriptive flag names for endpoint-scan controls so host apps can pass them directly: `--timeout-seconds`, `--parallel-jobs` (default 30), `--ip-family ipv4|ipv6`, and `--port-scan-mode first-reachable|all-open-ports|all-known-ports`. Existing flags (`-t`/`-timeout`, `-jt`/`-tunnel-jobs`, `-6`/`-ipv6`, and `-sweep-ports`) remain supported.
