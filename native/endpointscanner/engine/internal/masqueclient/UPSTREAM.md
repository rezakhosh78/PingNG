# Source provenance

Copied from github.com/Diniboy1123/usque/api/masque.go at
v1.5.1-0.20260720063354-6aa03fc97d12 (commit 6aa03fc97d12).
MIT license retained in LICENSE.md. The initial isolated copy changed only the
package declaration; this scanner edition also removes HTTP/2 environment-proxy
handling from `newHTTP2Client` so it connects directly to the selected endpoint.

This package contains the client TLS and CONNECT-IP connection helpers used by
endpoint measurement. Separating it avoids importing unrelated usque API files
and their internal SOCKS server dependencies. TLS public-key pinning and QUIC
retry logic are retained. This is a maintained source snapshot; future upstream
client/security fixes must be reviewed here.
