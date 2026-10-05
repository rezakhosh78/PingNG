<div align="center">

# 🚀 PingNG

### A v2rayNG fork with native Desync, WARP tools, MasterDNS, and Psiphon CDN Fronting

Control connection behavior per profile, configure WARP tunnels, customize TLS, and troubleshoot from one Android client.

![Android](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Root](https://img.shields.io/badge/Root-Not%20Required-2EA44F)
![Desync](https://img.shields.io/badge/Desync-Native-0066CC)
![Xray](https://img.shields.io/badge/Xray--core-26.9.9-blue)

**English** · [فارسی](README_FA.md)

</div>

---

## 🌟 Why PingNG?

PingNG keeps the familiar v2rayNG experience and adds profile-aware Desync, WARP tunnel options, MasterDNS, and Psiphon CDN Fronting. Settings are stored with the relevant profile and applied when it connects.

### At a glance

| Feature | v2rayNG | PingNG |
|---|:---:|:---:|
| Native Android Desync | — | ✅ |
| Per-profile Desync settings | — | ✅ |
| WARP Plus (Outer + Inner) | — | ✅ |
| WARP WireGuard | — | ✅ |
| WARP MASQUE/H2 | — | ✅ |
| MasterDNS | — | ✅ |
| Psiphon over MasterDNS | — | ✅ |
| FinalMask controls and search for WARP profiles | — | ✅ |
| Psiphon CDN Fronting on profiles | — | ✅ |
| Psiphon over WARP and WARP Plus | — | ✅ |
| WARP key registration through a proxy | — | ✅ |
| Smart custom Desync parameter editor | — | ✅ |
| Random multi-domain Fake SNI | — | ✅ |
| `unsafe` TLS fingerprint (PattNG) | — | ✅ |
| Editable Cipher Suites (PattNG) | — | ✅ |
| Traffic usage graph | — | ✅ |
| Connect through an HTTP proxy | — | ✅ |

## 🛡️ Desync Engine

The Desync engine runs natively on Android and does not require root access. Configure a Desync profile for a supported connection, and PingNG applies it when that profile starts. WARP MASQUE/H2 can route Desync through its outer TLS connection.

### Built-in profiles

| Profile | Intended use |
|---|---|
| **Off** | Start the configuration without Desync |
| **Light** | Minimal processing and broad compatibility |
| **Balanced** | A practical balance for everyday use |
| **Severe** | Stronger processing for more restricted networks |
| **Adaptive** | Behavior adjusted to the connection |
| **Custom** | Control the available parameters manually |

### Custom methods

- `Split`
- `Disorder`
- `Fake SNI`
- `Out of Band`
- `Disorder + Out of Band`

Only options relevant to the selected method are shown. Depending on the method, you can adjust values such as **Position**, **Fake TTL**, **Fake SNI**, **TLS Record Position**, and **Timeout**.

### Connection flow

```mermaid
flowchart LR
    A[Selected profile] --> B[Native Desync]
    B --> C[Local proxy route]
    C --> D[Xray connection]
```

If Desync is enabled, the engine must start and attach successfully. Otherwise, the VPN does not start, preventing a silent connection without the selected Desync behavior.

## 🌐 WARP

PingNG provides three WARP options:

- **WARP Plus** — Outer and Inner WARP profiles with endpoint discovery modes, verification, and FinalMask settings.
- **WARP WireGuard** — A single-hop WireGuard-based WARP profile with automatic account/key generation and endpoint scanning.
- **WARP MASQUE/H2** — A standalone MASQUE tunnel over HTTP/2 with endpoint scanning and configurable SNI.

### Endpoint scanning and FinalMask

WARP scanning supports selectable modes and Custom endpoints. Verification uses endpoints discovered by the scan and reports progress from the candidates being tested. FinalMask fields and **Find FinalMask Setting** are available for WARP Plus and WARP WireGuard profiles.

### WARP key registration proxy

WARP profiles include a **Proxy** choice for account/key registration. **Auto** tries direct registration first, then falls back to the bundled **Proxy-1** VLESS profile when needed. Other available PingNG profiles can also be selected. The bundled registration proxy is used internally and is not added to the main configuration list.

## 🛡️ Psiphon and CDN Fronting

Psiphon can be enabled per configuration, including WARP and WARP Plus profiles. Choose **Auto**, **Direct**, or **CDN Fronting**:

- Enter custom CDN IP addresses and server names (SNI).
- Select built-in CDN edge lists or use the default lists.
- Route Psiphon through the configured HTTP CONNECT proxy when required.

## 🌐 MasterDNS

**Add [MasterDNS]** appears at the end of the profile menu. The client includes binaries for common Android architectures. While DNS resolvers are tested, PingNG shows live progress in **completed/total** format, such as `230/450`.

The MasterDNS editor also includes **Psiphon Over MasterDNS**, with a title and description specific to this connection.

## 🎲 Random multi-domain Fake SNI

Add multiple domains to **Fake SNI**, separated by commas:

```text
hcaptcha.com, speedtest.net, example.com
```

PingNG automatically:

- Converts spaces, new lines, semicolons, and `|` characters to comma separators.
- Removes duplicate entries.
- Selects one SNI randomly for every new connection.
- Keeps the selected SNI unchanged for the lifetime of that connection.

## 🔐 Extended TLS controls

- Adds `unsafe` to the available TLS fingerprint options (PattNG).
- Provides an editable **Cipher Suites** field (PattNG).
- Stores Cipher Suites with the selected profile.
- Applies the value to the generated Xray configuration.
- Preserves the value during supported import, export, and sharing flows.

## 🧩 Supported configuration types

Per-profile Desync is available for supported TCP/TLS configurations, including:

`VMess` · `VLESS` · `Shadowsocks` · `SOCKS` · `HTTP` · `Trojan` · `WARP MASQUE/H2`

## ✅ Verification notes

- Desync settings are stored per configuration and applied to the selected connection path.
- Display names map to the native methods without changing their runtime flags.
- Random Fake SNI selection has been confirmed through runtime logs.
- Xray-core is set to **26.9.9**.

Testing on multiple Android versions, device vendors, and network conditions is recommended before release.

## 🙏 Credits

- Based on [2dust/v2rayNG](https://github.com/2dust/v2rayNG) and PattNG.
- PingNG development and interface: **ReZa Kh**.
- TLS improvements were inspired by contributions from the v2rayNG community.

## ⚠️ Disclaimer

Use this project only in accordance with the laws of your country and the terms of the services you connect to. You are responsible for your configuration and usage.

---

<div align="center">

Made with ❤️ for Iranian People

</div>
