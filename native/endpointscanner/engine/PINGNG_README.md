# PingNG integration for the client-only Endpoint Scanner scanner

`EndpointScanPanel` exposes the scan controls supported by the app: timeout seconds, parallel jobs (default 30), IPv4/IPv6, and (for WireGuard-based scans) first reachable, all open ports, or all known ports. `EndpointScanOptions` forwards those selections through the explicit `--timeout-seconds`, `--parallel-jobs`, `--ip-family`, and `--port-scan-mode` arguments. Plain WireGuard scans also run a durability probe so endpoints that only complete a handshake are not selected. MASQUE/H2 omits the port mode because it does not use the WARP port sweep. The existing short flags remain available to other callers.

The Android integration also uses `tui.go` progress and endpoint events for `EndpointScanCli.kt`. The scan controls change only the requested scan parameters; they do not add an inbound proxy or listener.

Android Studio builds the four ABI scanner binaries automatically through buildEndpointScannerAndroid. The integration script is retained for maintainers.
