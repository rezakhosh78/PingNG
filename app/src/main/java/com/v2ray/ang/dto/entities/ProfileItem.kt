package com.v2ray.ang.dto.entities

import com.v2ray.ang.AppConfig
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils

data class ProfileItem(
    val configVersion: Int = 4,
    val configType: EConfigType,
    var subscriptionId: String = "",
    var addedTime: Long = System.currentTimeMillis(),

    var remarks: String = "",
    var description: String? = null,
    /** DNS tunnel settings; legacy field names retained for stored-profile compatibility. */
    /** DNSTT resolver transport: udp, doh, or dot. */
    var dnsTunnelTransport: String? = null,
    /** DNSTT remote endpoint: SOCKS5 or SSH with local dynamic forwarding. */
    var dnsTunnelMode: String? = null,
    var dnsTunnelSshUsername: String? = null,
    var dnsTunnelSshPassword: String? = null,
    var dnsTunnelSshHost: String? = null,
    var dnsTunnelSshPort: Int? = null,
    /** DNSTT SSH keepalive interval in seconds; defaults to 5 seconds. */
    var dnsTunnelSshKeepaliveSeconds: Int? = null,
    /** Soar DNS-tunnel zone delegated to the server. */
    var soarZone: String? = null,
    /** Soar server Ed25519 public key, in base64 or hexadecimal form. */
    var soarServerKey: String? = null,
    /** Optional recursive resolvers, one IP[:port] per line. */
    var soarResolvers: String? = null,
    /** Discover bundled resolvers when configured resolvers are unavailable. */
    var soarDiscoveryEnabled: Boolean? = null,
    /** ISO 3166-1 alpha-2 resolver discovery country code. */
    var soarDiscoveryCountry: String? = null,
    /** Soar DNS-over-TCP path policy: auto, on, only, or off. */
    var soarTcpMode: String? = null,
    /** JSON encoded upstream Soar client and resolver discovery tuning. */
    var soarAdvanced: String? = null,
    var masterDnsDomain: String? = null,
    var masterDnsEncryptionKey: String? = null,
    var masterDnsMethod: Int? = null,
    var masterDnsAdvanced: String? = null,
    var masterDnsResolvers: String? = null,
    /** First successful MasterDNS scan is cached; later starts reuse its valid resolvers. */
    var masterDnsReuseValidResolvers: Boolean = false,
    var server: String? = null,
    var serverPort: String? = null,

    var pingNgProfile: String? = "Off",
    var pingNgDesyncArgs: String? = null,
    var psiphonEnabled: Boolean = false,
    /** Run compatible TLS-over-TCP profiles through the local Bepass SOCKS listener. */
    var bepassEnabled: Boolean = false,
    /** JSON-encoded user Bepass options; omitted fields use BepassSettings defaults. */
    var bepassSettingsJson: String? = null,
    /** Route this profile through the embedded Tor SOCKS hop. */
    var torEnabled: Boolean = false,
    /** Tor bridge policy: auto, first, never, or own. */
    var torBridgeMode: String? = null,
    /** Automatic Tor bridge sources: auto, only (relays), or off (BridgeDB only). */
    var torBridgeSources: String? = null,
    /** Tor bridge directives supplied by the user, one per line. */
    var torBridgeLines: String? = null,
    var psiphonRegion: String? = "ANY",
    /** Psiphon transport selection: auto, cdn (CDN fronting), or direct. */
    var psiphonMode: String? = "auto",
    /** Optional user-supplied CDN edge IPs used by the Psiphon fronting scan. */
    var psiphonCdnIps: String? = null,
    /** Optional SNI/server names paired with [psiphonCdnIps]. */
    var psiphonCdnSni: String? = null,
    /** Built-in Psiphon CDN edge lists to scan, comma-separated; null means all. */
    var psiphonCdnSets: String? = null,
    /** Non-visible component owned by a generated composite profile. */
    var managedBy: String? = null,
    /** Last verified egress country; kept with this profile instead of UI-only state. */
    var lastExitCountryCode: String? = null,
    /** Last verified egress IP, kept with the profile for stable display. */
    var lastExitIpAddress: String? = null,

    var password: String? = null,
    var method: String? = null,
    var flow: String? = null,
    var username: String? = null,

    var network: String? = null,
    var headerType: String? = null,
    var host: String? = null,
    var path: String? = null,
    var seed: String? = null,
    var kcpMtu: Int? = null,
    var kcpTti: Int? = null,

    var quicSecurity: String? = null,
    var quicKey: String? = null,
    var mode: String? = null,
    var serviceName: String? = null,
    var authority: String? = null,
    var xhttpMode: String? = null,
    var xhttpExtra: String? = null,
    var finalMask: String? = null,

    var security: String? = null,
    var sni: String? = null,
    var alpn: String? = null,
    var fingerPrint: String? = null,
    var cipherSuites: String? = null,
    var insecure: Boolean? = null,
    var echConfigList: String? = null,
    var verifyPeerCertByName: String? = null,
    var pinnedCA256: String? = null,

    var publicKey: String? = null,
    var shortId: String? = null,
    var spiderX: String? = null,
    var mldsa65Verify: String? = null,

    var secretKey: String? = null,
    /** Original AmneziaWG .conf, retained losslessly for AWG 1.x/2.x/3.x fields. */
    var amneziawgConfig: String? = null,
    /** WARP endpoint editor option; null keeps the default (enabled) for old profiles. */
    var autoScanEndpoint: Boolean? = null,
    /** Verified AWG WARP endpoints from the most recent scan, ordered by latency. */
    var awgEndpointCandidates: String? = null,
    /** Consume the editor's successful scan on the next connection. */
    var awgSkipAutoScanOnce: Boolean? = null,
    var preSharedKey: String? = null,
    var localAddress: String? = null,
    var reserved: String? = null,
    var mtu: Int? = null,
    /** WireGuard resolver list; null means use the app default. */
    var remoteDNS: String? = null,
    var warpKeepAlive: Int? = null,
    var warpEndpointCandidates: String? = null,
    var warpEndpointTestEnabled: Boolean? = null,
    var warpEndpointTestMode: String? = null,
    /** FinalMask for automatic WARP Plus selection, stored under its legacy profile key. */
    var warpFastFinalMask: String? = null,
    /** Legacy secondary FinalMask field retained when reading older saved profiles. */
    var warpAllFinalMask: String? = null,
    var warpFinalMaskEnabled: Boolean? = null,
    var warpInnerEndpointCandidates: String? = null,
    var warpOuterEndpointCandidates: String? = null,
    /** Last endpoint pair selected by the WARP Plus endpoint tester. */
    var warpSelectedEndpoint: String? = null,
    /** Last single-hop WARP WireGuard endpoint selected by its tester. */
    var warpWireGuardSelectedEndpoint: String? = null,
    /** WARP registration path: Auto, a built-in Proxy-1/Proxy-2, or a selected proxy profile GUID. */
    var warpRegistrationProxyGuid: String? = null,
    /** Internal registration proxy; never publish in profile lists. */
    var warpRegistrationInternalProxy: Boolean? = null,
    /** Skip one automatic endpoint retest after a FinalMask result is applied. */
    var warpSkipEndpointTestOnce: Boolean? = null,
    /** Candidate IPv4 endpoints for the WARP MASQUE/HTTP2 core. */
    var warpMasqueEndpointCandidates: String? = null,
    /** Last MASQUE endpoint selected by the automatic endpoint scan. */
    var warpMasqueSelectedEndpoint: String? = null,
    /** One-shot result scanned in the UI process so the service reuses it on start. */
    var warpMasqueScanPreparedOnce: Boolean? = null,
    /** User-editable MASQUE registration and transport settings. */
    var warpMasqueDeviceName: String? = null,
    var warpMasquePrimaryEndpoint: String? = null,
    var warpMasqueEndpointPort: Int? = null,
    var warpMasqueEndpointMode: String? = null,
    var warpMasqueSni: String? = null,
    var warpMasqueHttp2Enabled: Boolean? = null,
    var warpMasqueSocksBind: String? = null,
    var warpMasqueSocksPort: Int? = null,
    var warpMasqueDns: String? = null,
    /** Optional complete MASQUE JSON, useful when registration is blocked. */
    var warpMasqueConfigJson: String? = null,
    /** Binds the locally obtained MASQUE registration to this profile identity. */
    var warpMasqueRegistrationGuid: String? = null,

    var obfsPassword: String? = null,
    var portHopping: String? = null,
    var portHoppingInterval: String? = null,
    @Deprecated("Use pinnedCA256")
    var pinSHA256: String? = null,
    var bandwidthDown: String? = null,
    var bandwidthUp: String? = null,

    var policyGroupType: String? = null,
    var policyGroupSubscriptionId: String? = null,
    var policyGroupFilter: String? = null,
    var policyGroupTestOutbounds: Boolean? = null,
    var policyGroupFallbackTag: String? = null,
    var proxyChainProfiles: String? = null,

    var browserDialerMode: String? = null,
    /** Complete imported OpenVPN profile, including inline certificates and keys. */
    var openVpnConfig: String? = null,
) {

    companion object {
        fun create(configType: EConfigType): ProfileItem =
            ProfileItem(configType = configType)
    }

    fun getServerAddressAndPort(): String {
        if (server.isNullOrEmpty() && configType == EConfigType.CUSTOM) {
            return "${AppConfig.LOOPBACK}:${AppConfig.PORT_SOCKS}"
        }
        return "${Utils.getIpv6Address(server)}:$serverPort"
    }

    /**
     * Dedicated identity for "remove duplicate configurations".
     *
     * Ignores metadata that does not affect connection:
     * - configVersion
     * - subscriptionId
     * - addedTime
     * - remarks
     * - description
     *
     * All other fields, including configType, are included in the comparison.
     *
     * Returns a copy; the caller must not modify it further.
     */
    fun duplicateIdentity(): ProfileItem =
        copy(
            configVersion = 0,
            subscriptionId = "",
            addedTime = 0L,
            remarks = "",
            description = null
        )
}
