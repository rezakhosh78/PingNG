# OpenVPN 3 Android engine

PingNG packages the Android OpenVPN 3 client engine from `app/libs/openvpn3-api.jar` and
`app/libs/<abi>/libovpn3.so`. `app/src/main/java/com/v2ray/ang/service/OpenVpn3Native.kt`
connects that API to Android `VpnService`: it protects the transport socket, builds the TUN
from the server's addresses/routes/DNS/MTU, passes credentials through the client API, and
forwards connection state to the app.

## Engine provenance

- Android package: `network.mysterium.openvpn:icsopenvpn:0.7.55-myst`, AAR SHA-256
  `3540c8579b0056baac517cb6c930d9d9fb19cbbdf6216e4353fce5513aee50f6`.
- Build variant: `skeletonOvpn23Release` from `mysteriumnetwork/openvpn_dart`; the `skeleton`
  variant excludes the ics-openvpn Java UI/wrapper, and `ovpn23` uses OpenVPN 3 with OpenSSL.
- Core source: `schwabe/openvpn3`, commit
  `3e3cd518eadf0b8d9de441f095b841f93dd09fcc`, included in `core/` without its `.git` directory.
- TLS library: OpenSSL 3.4.1. Asio and LZ4 are linked into the native engine.
- `openvpn3-api.jar` contains only the 44 generated `net.openvpn.ovpn3` SWIG classes; the
  separate ics-openvpn `de.blinkt.*` wrapper is not packaged. Its SHA-256 is
  `b955c78322f29a442f6da380b98820a7b51a3fdf7292df2d4c003605d1fe096f`.

The selected OpenVPN 3 source is licensed under MPL-2.0. The source snapshot and license
notices are in `core/` and `licenses/`. The packaged native artifacts were checked for all four
ABIs; the 64-bit libraries use 16 KB aligned ELF load segments.

## Packaged binaries

| ABI | File size | SHA-256 |
| --- | ---: | --- |
| `arm64-v8a` | 8,288,424 | `134cb77e2bf1792eaf5531d1483bf91779e47185aa4b3bee020e9ce4e6de2580` |
| `armeabi-v7a` | 5,552,388 | `49ca681388b5e7831c5e9551df3c0943ffaae0929e29af93ad08616fb121e99e` |
| `x86` | 7,847,932 | `188312a31f9cfbae8aef9f42962f664ce840852da1b964ac3c4d16bc197ed820` |
| `x86_64` | 9,247,944 | `ed6e8cecb2479c69b7e044dea77a71e65f303c852e092e46b975bd51492b5df8` |

## Build and compatibility

The Android app build packages the checked-in engine libraries; it no longer downloads or
builds a second OpenVPN core through CMake. The OpenVPN 3 source is retained here for audit and
maintenance. The native engine can be rebuilt from the pinned `ics-openvpn` Android project and
its OpenVPN 3/OpenSSL submodules, then the generated SWIG API and `libovpn3.so` files can replace
the checked-in files above.

At runtime the app translates OpenVPN 2 `data-ciphers`, `ncp-ciphers`, and
`data-ciphers-fallback` lists to their first preferred cipher because this OpenVPN 3 client API
does not apply those directives. It keeps `tls-ciphersuites`, enables legacy OpenSSL algorithms
only when requested, and selects OpenVPN 3's asymmetric compression mode for profiles that
explicitly request compression. Excluded routes are handled natively on Android 13+ and through
OpenVPN 3 route splitting on older Android releases. OpenVPN 3 is not a drop-in
implementation of every OpenVPN 2 extension: unsupported mandatory features such as static-key
mode, `fragment`, management scripts, and some proxy modes still cannot be made compatible by
config rewriting.
